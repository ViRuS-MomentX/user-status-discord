package ru.virus.voicebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.SequencedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Пародийный бот: читает группу Telegram и время от времени вставляет реплику
 * в манере выбранного участника.
 *
 * <p>Пишет только в Telegram. В Discord его реплики не уходят: мост пропускает
 * всё, что прислали боты, а это бот.
 *
 * <p>Каждая реплика начинается с пометки — по умолчанию {@link #MARK}. Слова
 * можно поменять в parody.mark, но убрать пометку нельзя: бот выглядит как
 * участник, и без неё в группе думали бы, что это написал он сам.
 */
public final class Parody {

    private static final Logger log = LoggerFactory.getLogger(Parody.class);

    /** С чего начинается каждая реплика. */
    static final String MARK = ParodySettings.DEFAULT_MARK;

    /** Меню команд: Telegram подсказывает их при наборе «/». */
    private static final java.util.Map<String, String> COMMANDS;

    static {
        var commands = new java.util.LinkedHashMap<String, String>();
        commands.put("parody_say", "сказать что-нибудь сейчас");
        commands.put("parody_try", "пробная реплика только тебе, в личке");
        commands.put("parody_off", "выключить пародию");
        commands.put("parody_on", "включить пародию");
        commands.put("parody_forget", "стереть собранные сообщения");
        commands.put("parody_name", "ответом на сообщение: как называть этого человека");
        commands.put("parody_names", "кого как называть");
        commands.put("parody_status", "как дела");
        COMMANDS = java.util.Collections.unmodifiableMap(commands);
    }

    /** Сколько последних сообщений группы показывать ИИ, чтобы он понимал разговор. */
    private static final int CONTEXT = 20;

    /** Сколько образцов речи участника класть в подсказку. */
    private static final int EXAMPLES = 80;

    /** Даже на прямой ответ — не чаще, иначе двое людей утопят группу в репликах. */
    private static final long REPLY_GAP_MS = 20_000;

    /** Сколько своих сообщений помнить, чтобы узнавать ответы на них. */
    private static final int OWN_LIMIT = 200;

    /** Сколько своих последних реплик показывать ИИ, чтобы он не повторялся. */
    private static final int SAID_LIMIT = 10;

    /** Сколько знаков чужой реплики приводить, когда на неё отвечают. */
    private static final int QUOTE_LIMIT = 120;

    private static final long RETRY_PAUSE_MS = 15_000;

    /** Пауза после сбоя ИИ: минута, дальше вдвое длиннее с каждым сбоем подряд. */
    private static final long LLM_PAUSE_MS = 60_000;
    private static final long LLM_PAUSE_LIMIT_MS = 30 * 60_000;

    private final ParodySettings settings;
    private final Telegram telegram;
    private final Llm llm;
    private final ParodySamples samples;
    private final ParodyState state;
    private final ParodyNames names;

    /** Кто недавно писал — их прозвища показываем ИИ в первую очередь. */
    private final Deque<Long> activeIds = new ArrayDeque<>();
    private final Random random = new Random();

    /** Часы подменяются в тестах: тихие часы и счёт за сутки зависят от них. */
    private Clock clock = Clock.systemDefaultZone();

    private final Deque<String> recent = new ArrayDeque<>();
    private final SequencedSet<Long> own = new LinkedHashSet<>();
    private final Deque<String> said = new ArrayDeque<>();

    /** Одна реплика за раз: пока ИИ думает, новые поводы ждут в {@link #pending}. */
    private final AtomicBoolean busy = new AtomicBoolean();

    /**
     * Что пришло, пока ИИ думал, и на что ответить надо. Хранится только
     * последнее: на пять сообщений подряд человек тоже отвечает одной репликой.
     */
    private final java.util.concurrent.atomic.AtomicReference<Waiting> pending =
            new java.util.concurrent.atomic.AtomicReference<>();

    private record Waiting(TelegramMessage message, boolean direct) {
    }
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "parody-writer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile long lastSent;

    /** Подсказка про закрытый Telegram уже была. */
    private volatile boolean blockAdvised;

    /** До какого времени ИИ не трогаем после сбоя, и сколько сбоев было подряд. */
    private volatile long llmBlockedUntil;
    private int llmFailures;

    /** Чем кончилась последняя попытка спросить ИИ — для /parody_status. */
    private volatile String llmHealth = "ещё не спрашивал";
    private volatile boolean running = true;
    private Thread worker;

    /** Группа числом: в обновлениях она приходит только так. */
    private volatile String chatId;

    /** «@имя» бота строчными: по нему узнаём, что к нему обратились. */
    private String mention = "";

    public Parody(ParodySettings settings, Telegram telegram, Llm llm, ParodySamples samples,
                  ParodyState state, ParodyNames names) {
        this.settings = settings;
        this.telegram = telegram;
        this.llm = llm;
        this.samples = samples;
        this.state = state;
        this.names = names;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public void start() {
        worker = new Thread(this::loop, "parody-from-telegram");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        writer.shutdownNow();

        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        long offset = 0;

        while (running && chatId == null) {
            try {
                var chat = settings.chat().startsWith("@")
                        ? telegram.chatId(settings.chat())
                        : settings.chat();

                mention = "@" + telegram.whoAmI().toLowerCase(java.util.Locale.ROOT);

                if (!telegram.readsAllGroupMessages()) {
                    log.warn("Пародийный бот видит в группе только обращённое к нему. Чтобы он "
                            + "читал переписку, у @BotFather: /setprivacy → бот → Disable, "
                            + "потом удали его из группы и добавь заново.");
                }

                // Старое не разбираем: отвечать на вчерашнее никто не просил
                offset = telegram.lastUpdateId(0);

                try {
                    telegram.setCommands(COMMANDS);
                } catch (Exception e) {
                    // Без меню команды всё равно работают — их просто не подскажут
                    log.debug("Меню команд не завелось: {}", e.getMessage());
                }

                // Последним: пока группа не узнана, цикл повторяет всё сначала, и
                // сбой на полпути не оставит бота без имени или без отметки
                chatId = chat;

                writer.execute(this::checkLlm);
            } catch (Exception e) {
                log.warn("Пародийный бот не достучался до Telegram: {}. Пробую через {} с.",
                        reason(e), RETRY_PAUSE_MS / 1000);
                adviseIfBlocked(e);

                if (!pause()) {
                    return;
                }
            }
        }

        while (running) {
            try {
                var messages = telegram.poll(offset);

                for (var message : messages) {
                    offset = Math.max(offset, message.updateId() + 1);
                    handle(message);
                }

                if (messages.isEmpty()) {
                    offset = telegram.lastUpdateId(offset);
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }

                if (String.valueOf(e.getMessage()).contains("Conflict")) {
                    // Обновления у бота одни: их забирает тот, кто спросил последним
                    log.warn("Обновления пародийного бота забирает кто-то ещё — скорее всего, "
                            + "вторая копия бота с тем же parody.telegram.token. Закрой её. "
                            + "Пробую через {} с.", RETRY_PAUSE_MS / 1000);
                } else {
                    log.warn("Telegram не ответил пародийному боту: {}. Пробую через {} с.",
                            reason(e), RETRY_PAUSE_MS / 1000);
                    adviseIfBlocked(e);
                }

                if (!pause()) {
                    return;
                }
            }
        }
    }

    /**
     * Обрыв рукопожатия, таймаут, сброс — так выглядит закрытый провайдером
     * api.telegram.org. Подсказываем выход один раз, а не каждые 15 секунд.
     */
    private void adviseIfBlocked(Exception e) {
        if (blockAdvised || !settings.api().isBlank() || !settings.proxy().isBlank()
                || !looksBlocked(reason(e))) {
            return;
        }

        blockAdvised = true;
        log.warn("Похоже, api.telegram.org у тебя закрыт провайдером. Пропиши в настройках "
                + "bridge.telegram.api — адрес пересыльщика на Cloudflare (папка cloudflare) — "
                + "и добавь в нём секрет PARODY_TOKEN с токеном пародийного бота. "
                + "Или задай HTTP-прокси в bridge.telegram.proxy.");
    }

    static boolean looksBlocked(String reason) {
        var text = reason.toLowerCase(java.util.Locale.ROOT);
        return text.contains("handshake") || text.contains("timed out") || text.contains("reset")
                || text.contains("соединение не установилось");
    }

    /** Что случилось — словами; у отказа в соединении текста нет вовсе, и в логе стояло бы «null». */
    static String reason(Exception e) {
        var message = e.getMessage();
        return message == null || message.isBlank()
                ? (e instanceof java.net.ConnectException ? "соединение не установилось"
                    : e.getClass().getSimpleName())
                : message;
    }

    private boolean pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void handle(TelegramMessage message) {
        var group = message.chat().equals(chatId);

        if (message.text().isBlank()) {
            // Фото или голосовое без подписи: отвечать не на что, но разговор без
            // них рвётся — «смотри» повисло бы без того, на что смотреть
            if (group && !message.fileName().isBlank()) {
                remember(message.author() + ": [" + attachment(message.fileName()) + "]");
            }
            return;
        }

        // Команды принимаются и в личке: выключить пародию тихо, не при всех
        if (message.text().startsWith("/") && command(message)) {
            return;
        }

        // В остальном личку и чужие группы не читаем: бот живёт в одной группе
        if (!group) {
            return;
        }

        remember(line(message));

        if (message.authorId() != 0 && !message.fromBot()) {
            names.seen(message.authorId(), message.author());
            noteActive(message.authorId());
        }

        if (message.replyToAuthorId() != 0) {
            names.seen(message.replyToAuthorId(), message.replyToAuthor());
        }

        // Выключенный бот не копит и речь участника: раз попросили остановиться,
        // собирать его сообщения дальше тоже незачем
        if (state.isPaused()) {
            return;
        }

        if (message.authorId() == settings.target()) {
            // Пересланное и присланное через инлайн-бота — не его слова
            if (!message.forwarded()) {
                samples.add(message.text());

                // Ответил кому-то и начал с обращения — так он этого человека и зовёт
                if (message.replyToAuthorId() != 0 && message.replyToAuthorId() != settings.target()) {
                    names.learn(message.replyToAuthorId(), message.text());
                }
            }

            // Самому участнику не отвечаем: разговор с собой выглядел бы странно.
            // Персонаж — не он, для персонажа это обычный собеседник
            if (!settings.isCharacter()) {
                return;
            }
        }

        // Ботов только слушаем: иначе два бота заговорят друг друга до бесконечности
        if (message.fromBot()) {
            return;
        }

        boolean replied;

        synchronized (own) {
            replied = message.replyToId() != 0 && own.contains(message.replyToId());
        }

        // Позвали по имени бота — тоже обращение
        var direct = replied || mention.length() > 1
                && message.text().toLowerCase(java.util.Locale.ROOT).contains(mention);

        var now = clock.millis();

        if (settings.isQuiet(LocalTime.now(clock).getHour()) || overDaily()
                || now < llmBlockedUntil) {
            return;
        }

        // Персонажу образцы не обязательны: манера письма — лишь приправа к характеру
        if (!settings.isCharacter() && samples.size() < settings.minSamples()) {
            log.debug("Образцов пока {} из {} — молчу.", samples.size(), settings.minSamples());
            return;
        }

        // Пока ИИ думает над прошлым, обращение не теряем, а откладываем: раньше
        // такие сообщения молча пропадали, и казалось, что бот отвечает выборочно
        if (busy.get()) {
            if (direct || alwaysAnswers()) {
                pending.set(new Waiting(message, direct));
            }

            return;
        }

        var gap = direct ? REPLY_GAP_MS : settings.cooldownMinutes() * 60_000L;

        if (now - lastSent < gap) {
            return;
        }

        if (!direct && random.nextInt(100) >= settings.chance()) {
            return;
        }

        reply(message, direct);
    }

    /** При вероятности 100% встревает во всё — значит, и молчать ему нельзя. */
    private boolean alwaysAnswers() {
        return settings.chance() >= 100;
    }

    /** Отвечает на сообщение; после ответа берётся за отложенное, если оно есть. */
    private void reply(TelegramMessage message, boolean direct) {
        if (!busy.compareAndSet(false, true)) {
            pending.set(new Waiting(message, direct));
            return;
        }

        lastSent = clock.millis();
        var context = context();

        // На обращение и на вопрос — ответом в цепочке, чтобы было видно, к чему
        // реплика; на прочее — просто в разговор, как пишут люди
        var threaded = direct || message.text().trim().endsWith("?");
        var replyTo = threaded ? message.messageId() : 0;

        writer.execute(() -> {
            try {
                speak(context, replyTo, direct, null);
            } finally {
                busy.set(false);
                var next = pending.getAndSet(null);

                if (next != null && running && !state.isPaused()) {
                    reply(next.message(), next.direct());
                }
            }
        });
    }

    /**
     * Сказанное в Discord — его пересказывает мост.
     *
     * <p>Сообщения моста в группе пародийный бот не видит: Telegram не показывает
     * ботам сообщения других ботов. Поэтому мост сообщает о них напрямую, и
     * пародия слышит и людей с той стороны. Отвечает она всё равно в Telegram.
     */
    public void hearDiscord(String author, String text) {
        var chat = chatId;

        // Команды пародии — только из Telegram: в Discord не видно, кто их дал
        if (chat == null || text.isBlank() || text.startsWith("/")) {
            return;
        }

        handle(new TelegramMessage(0, 0, 0, chat, 0, author + " (из Discord)", text,
                "", "", false, "", "", false, 0));
    }

    private static String attachment(String fileName) {
        if (fileName.startsWith("photo")) {
            return "фото";
        }
        if (fileName.startsWith("voice")) {
            return "голосовое";
        }
        if (fileName.startsWith("video")) {
            return "видео";
        }
        return "файл " + fileName;
    }

    private boolean overDaily() {
        return settings.daily() > 0 && state.sentToday(LocalDate.now(clock)) >= settings.daily();
    }

    /**
     * Команды управления. Слушаются хозяина бота и самого участника — последнего
     * особенно: передумал — выключает пародию и стирает своё, ни у кого не спрашивая.
     *
     * @return была ли это команда пародии
     */
    private boolean command(TelegramMessage message) {
        var word = message.text().trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        var at = word.indexOf('@');

        if (at > 0) {
            // «/parody_off@другой_bot» адресована не нам
            if (!("@" + word.substring(at + 1)).equals(mention)) {
                return false;
            }

            word = word.substring(0, at);
        }

        if (!word.startsWith("/parody")) {
            return false;
        }

        if (!settings.mayCommand(message.authorId())) {
            answer(message, "Пародией управляют только хозяин бота и сам " + settings.name() + ".");
            return true;
        }

        switch (word) {
            case "/parody_off" -> {
                state.setPaused(true);
                log.info("Пародию выключил {}.", message.author());
                answer(message, "Пародия выключена: бот молчит и не собирает сообщения. "
                        + "Включить — /parody_on.");
            }
            case "/parody_on" -> {
                state.setPaused(false);
                log.info("Пародию включил {}.", message.author());
                answer(message, "Пародия включена.");
            }
            case "/parody_forget" -> {
                var count = samples.clear();
                state.markForgotten();
                log.info("{} стёр образцы речи: {}.", message.author(), count);
                answer(message, "Стёрто сообщений: " + count + ". Пока не наберётся "
                        + settings.minSamples() + " новых, бот молчит. Чтобы он и новых не "
                        + "собирал — /parody_off.");
            }
            case "/parody_say" -> {
                if (state.isPaused()) {
                    answer(message, "Пародия выключена — сначала /parody_on.");
                } else if (samples.size() == 0 && !settings.isCharacter()) {
                    answer(message, "Пока не по чему пародировать: нет ни одного сообщения "
                            + settings.name() + ".");
                } else if (busy.compareAndSet(false, true)) {
                    lastSent = clock.millis();
                    var context = context();

                    writer.execute(() -> {
                        try {
                            speak(context, 0, false, null);
                        } finally {
                            busy.set(false);
                        }
                    });
                }
            }
            case "/parody_try" -> {
                if (samples.size() == 0 && !settings.isCharacter()) {
                    answer(message, "Пока не по чему пародировать: нет ни одного сообщения "
                            + settings.name() + ".");
                } else if (message.chat().equals(chatId)) {
                    answer(message, "Проба приходит только в личку — напиши мне её туда.");
                } else if (busy.compareAndSet(false, true)) {
                    var context = context();
                    var where = message.chat();

                    writer.execute(() -> {
                        try {
                            speak(context, 0, false, where);
                        } finally {
                            busy.set(false);
                        }
                    });
                }
            }
            case "/parody_name" -> nameCommand(message);
            case "/parody_names" -> {
                var report = names.report();
                answer(message, report.isEmpty()
                        ? "Пока никого. Ответь на сообщение человека: /parody_name Санёк"
                        : "Кого как называть:\n" + report);
            }
            case "/parody_status" -> answer(message, status());
            default -> answer(message, """
                    /parody_say — сказать что-нибудь сейчас
                    /parody_try — пробная реплика только тебе, в личке
                    /parody_off — выключить
                    /parody_on — включить
                    /parody_forget — стереть собранные сообщения
                    /parody_name Санёк — ответом на сообщение: так называть этого человека
                    /parody_name -Санёк — забыть это прозвище
                    /parody_names — кого как называть
                    /parody_status — как дела""");
        }

        return true;
    }

    /** «/parody_name Санёк» ответом на сообщение — запомнить, как зовут его автора. */
    private void nameCommand(TelegramMessage message) {
        var parts = message.text().trim().split("\\s+", 2);
        var who = message.replyToAuthorId();

        if (who == 0) {
            answer(message, "Ответь этой командой на сообщение человека: /parody_name Санёк");
            return;
        }

        if (parts.length < 2 || parts[1].isBlank()) {
            var known = names.nicknames(who);
            answer(message, known.isEmpty()
                    ? "Про " + message.replyToAuthor() + " пока ничего не знаю."
                    : message.replyToAuthor() + " — " + String.join(", ", known));
            return;
        }

        var nick = parts[1].strip();

        if (nick.length() > 40) {
            answer(message, "Слишком длинно для прозвища.");
            return;
        }

        names.seen(who, message.replyToAuthor());

        if (nick.startsWith("-")) {
            var forgotten = names.forget(who, nick.substring(1).strip());
            answer(message, forgotten ? "Забыл." : "Такого прозвища и не было.");
        } else {
            names.teach(who, nick);
            answer(message, "Запомнил: " + message.replyToAuthor() + " — «" + nick + "».");
        }
    }

    private void noteActive(long id) {
        synchronized (activeIds) {
            activeIds.remove(id);
            activeIds.addFirst(id);

            while (activeIds.size() > 12) {
                activeIds.removeLast();
            }
        }
    }

    private String status() {
        var lines = new ArrayList<String>();
        lines.add(state.isPaused() ? "Выключена." : "Включена.");
        if (settings.isCharacter()) {
            lines.add("Персонаж: " + settings.name() + (settings.target() == 0 ? ""
                    : ", образцов манеры письма: " + samples.size()) + ".");
        } else {
            lines.add("Сообщений " + settings.name() + " собрано: " + samples.size()
                    + (samples.size() < settings.minSamples()
                        ? " (начну с " + settings.minSamples() + ")" : "") + ".");
        }
        lines.add("Встреваю с вероятностью " + settings.chance() + "%, не чаще раза в "
                + settings.cooldownMinutes() + " мин.");
        lines.add("Сегодня реплик: " + state.sentToday(LocalDate.now(clock))
                + (settings.daily() > 0 ? " из " + settings.daily() : "") + ".");

        var blocked = llmBlockedUntil - clock.millis();
        lines.add("ИИ: " + llmHealth
                + (blocked > 0 ? " — жду ещё " + Math.max(1, blocked / 60_000) + " мин." : "."));

        if (!settings.quiet().isBlank()) {
            lines.add("Тихие часы: " + settings.quiet()
                    + (settings.isQuiet(LocalTime.now(clock).getHour()) ? " — сейчас как раз они." : "."));
        }

        return String.join("\n", lines);
    }

    /**
     * Ответ на команду. Без пометки «пародия»: это не реплика участника, а
     * служебное сообщение, — зато со значком, чтобы его ни с чем не спутать.
     */
    private void answer(TelegramMessage message, String text) {
        try {
            telegram.sendText(message.chat(), "⚙️ " + text, message.messageId());
        } catch (Exception e) {
            log.warn("Ответ на команду не ушёл: {}", e.getMessage());
        }
    }

    /** Строка переписки для ИИ: кто, кому отвечает и что сказал. */
    private String line(TelegramMessage message) {
        var line = new StringBuilder(message.author());

        if (!message.replyToAuthor().isBlank()) {
            var quote = message.replyToText().replace('\n', ' ');

            if (quote.length() > QUOTE_LIMIT) {
                quote = quote.substring(0, QUOTE_LIMIT) + "…";
            }

            line.append(" (в ответ ").append(message.replyToAuthor());

            if (!quote.isBlank()) {
                line.append(" на «").append(quote).append("»");
            }

            line.append(")");
        }

        return line.append(": ").append(message.text()).toString();
    }

    /** Длиннее строку переписки не держим: простыня вытеснила бы из подсказки весь разговор. */
    private static final int LINE_LIMIT = 300;

    private void remember(String line) {
        var cut = line.length() > LINE_LIMIT ? line.substring(0, LINE_LIMIT) + "…" : line;

        synchronized (recent) {
            recent.addLast(cut);

            while (recent.size() > CONTEXT) {
                recent.removeFirst();
            }
        }
    }

    private String context() {
        synchronized (recent) {
            return String.join("\n", recent);
        }
    }


    /**
     * Пробный запрос при запуске. Неверный ключ или нет сертификата — лучше
     * узнать сразу, а не через час, когда бот впервые решит встрять.
     */
    private void checkLlm() {
        try {
            llm.complete("Отвечай одним словом.", "Проверка связи. Ответь: ок", 10);
            llmHealth = "отвечает";
            log.info("ИИ на связи — пародия готова.");
        } catch (Exception e) {
            log.error("ИИ не ответил на проверку: {}. Пока это не исправлено, пародия будет молчать.",
                    e.getMessage());
            failed(e);
        }
    }

    /**
     * Сбой ИИ: неверный ключ или кончился лимит сам не пройдёт, а долбить API
     * на каждое сообщение группы — только засорять лог. Ждём минуту и вдвое
     * дольше с каждым сбоем подряд.
     *
     * @return сколько ждать, мс
     */
    private long failed(Exception e) {
        llmFailures++;
        var pause = Math.min(LLM_PAUSE_MS << Math.min(llmFailures - 1, 10), LLM_PAUSE_LIMIT_MS);
        llmBlockedUntil = clock.millis() + pause;
        llmHealth = "ошибка: " + e.getMessage();
        return pause;
    }

    /**
     * @param direct обратились ли к нему самому — ответом на реплику или по имени
     * @param preview куда отправить пробную реплику вместо группы; {@code null} —
     *                обычная реплика в группу
     */
    private void speak(String context, long replyTo, boolean direct, String preview) {
        try {
            // Пока ИИ думает, в группе видно «печатает…» — как у живого человека
            telegram.typing(preview == null ? chatId : preview);
        } catch (Exception e) {
            log.debug("«Печатает» не показалось: {}", e.getMessage());
        }

        // К нему обратились — промолчать нельзя: вторая попытка, если первая не годится
        var must = direct || preview != null || alwaysAnswers();
        // Лишняя попытка — на случай, если модель списала реплику из образцов
        var attempts = must ? 3 : 2;
        var text = "";
        var why = "";

        for (var attempt = 1; attempt <= attempts; attempt++) {
            var ask = prompt(context, direct)
                    + (attempt > 1 ? "\n\nПрошлый вариант не подошёл (" + why + "). Напиши другую короткую реплику." : "");

            try {
                text = polish(clean(llm.complete(system(must), ask, 300)));
                llmFailures = 0;
                llmHealth = "отвечает";
            } catch (Exception e) {
                var pause = failed(e);
                log.warn("ИИ не ответил: {}. Следующая попытка не раньше чем через {} мин.",
                        e.getMessage(), pause / 60_000);
                return;
            }

            why = text.isBlank() ? "пусто — ИИ промолчал или сработал фильтр"
                    : repeats(text) ? "повтор недавней реплики"
                    : copies(text, samples.all()) ? "это слово в слово его старое сообщение, а нужна новая "
                        + "фраза в его манере по теме разговора" : "";

            // Промолчать без обращения — можно: тогда и переспрашивать незачем
            if (why.isEmpty() || (text.isBlank() && !must)) {
                break;
            }

            log.info("Пародия промолчала бы: {}{}.", why, attempt < attempts ? " — пробую ещё раз" : "");
        }

        if (preview != null) {
            // Проба не идёт в счёт: её видит только спросивший
            try {
                telegram.sendText(preview, text.isBlank() ? "⚙️ ИИ решил промолчать." : settings.mark() + text, 0);
            } catch (Exception e) {
                log.warn("Пробная реплика не ушла: {}", e.getMessage());
            }
            return;
        }

        if (!why.isEmpty()) {
            return;
        }

        post(text, replyTo);
    }

    /**
     * Отправляет реплику с пометкой и запоминает её как свою.
     *
     * @param replyTo на что отвечаем; 0 — просто в группу
     */
    void post(String text, long replyTo) {
        try {
            var id = telegram.sendText(chatId, settings.mark() + text, replyTo);
            state.countSent(LocalDate.now(clock));
            // Хозяин должен видеть, что бот говорит от лица участника
            log.info("Пародия сказала: {}", text);
            remember(settings.name() + ": " + text);

            synchronized (said) {
                said.addLast(text);

                while (said.size() > SAID_LIMIT) {
                    said.removeFirst();
                }
            }

            synchronized (own) {
                own.add(id);

                while (own.size() > OWN_LIMIT) {
                    own.removeFirst();
                }
            }
        } catch (Exception e) {
            log.warn("Реплика пародийного бота не ушла: {}", e.getMessage());
        }
    }

    /** Сказал ли бот это недавно слово в слово. */
    private boolean repeats(String text) {
        var normalized = normalize(text);

        synchronized (said) {
            return said.stream().anyMatch(old -> normalize(old).equals(normalized));
        }
    }

    /** Короче — «ахах», «го», «ок»: такие повторы у живого человека в порядке вещей. */
    static final int COPY_MIN = 12;

    /**
     * Списана ли реплика из образцов: совпадает с его старым сообщением или
     * почти целиком из него состоит. Модели так «пародируют», когда ленятся, —
     * а нужна новая фраза в его манере.
     */
    static boolean copies(String text, List<String> samples) {
        var reply = normalize(text);

        if (reply.length() < COPY_MIN) {
            return false;
        }

        for (var sample : samples) {
            var old = normalize(sample);

            if (old.length() < COPY_MIN) {
                continue;
            }

            if (old.equals(reply) || old.contains(reply)
                    || (reply.contains(old) && old.length() * 10 >= reply.length() * 6)) {
                return true;
            }
        }

        return false;
    }

    private static String normalize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", " ").trim();
    }

    /**
     * Последняя доводка: эмодзи в конце реплики убираются всегда — модели
     * любят дописывать их от себя, и на живого человека это не похоже.
     * Эмодзи внутри фразы остаются, если это его манера.
     */
    private String polish(String text) {
        return text.isBlank() ? text : ParodyStyle.stripTrailingEmoji(text).strip();
    }

    /** @param must обратились к нему — молчать нельзя */
    private String system(boolean must) {
        if (settings.isCharacter()) {
            return characterSystem(must);
        }

        var name = settings.name();
        var emoji = ParodyStyle.emojiShare(samples.all()) >= 10
                ? "Эмодзи — только внутри фразы и только как у него в образцах, в конце реплики их не ставь. "
                : "Эмодзи не ставь: он ими почти не пользуется. ";
        return "Ты пишешь пародийные реплики для группового чата в Telegram в манере участника "
                + "по имени " + name + ". Он сам попросил об этой пародии, и каждая реплика "
                + "публикуется с пометкой, что это пародия. Перенимай его манеру по образцам: словарь, "
                + "сленг, длину фраз, пунктуацию, регистр, юмор, типичные словечки. "
                + "Образцы — только чтобы понять, КАК он пишет: не копируй их, не повторяй "
                + "дословно и не склеивай реплику из их кусков. Каждый раз сочиняй новую фразу, "
                + "которую он мог бы написать именно сейчас, по теме последних сообщений. " + emoji
                + "Пиши так, как он ответил бы на последние сообщения чата: обычно одна короткая "
                + "реплика. Выводи только текст реплики — без имени, кавычек, пометок и пояснений. "
                + "Не выдумывай о нём личных фактов, событий, признаний и мнений о реальных людях, "
                + "которых нет в образцах. Без угроз, травли и оскорблений участников. "
                + "Людей называй так, как называет их он, — список ниже. "
                + (must ? "К тебе обратились — обязательно ответь хотя бы короткой репликой."
                        : "Если вставить в разговор нечего, ответь одним символом «-».");
    }

    /**
     * Свой персонаж: характер задаёт хозяин, а рамки — здесь. Грубить и
     * материться персонажу можно, это стёб между друзьями; травить людей за
     * то, кто они есть, угрожать и сочинять о них небылицы — нет.
     */
    private String characterSystem(boolean must) {
        var name = settings.name();
        var hasStyle = settings.target() != 0 && samples.size() > 0;
        return "Ты — " + name + ", персонаж-бот в групповом чате друзей в Telegram. Ты никого из "
                + "участников не изображаешь и не выдаёшь себя за реального человека. "
                + "Твой характер:\n" + settings.character() + "\n\n"
                + "Держись этого характера в каждой реплике. "
                + (hasStyle
                    ? "Форму сообщений — длину, пунктуацию, регистр, опечатки, сленг — бери из "
                      + "образцов ниже. Это сообщения другого человека: бери у него только то, КАК "
                      + "он пишет, а не что — не его мнения, не факты о нём и не его фразы. "
                      + "Не копируй образцы и не склеивай реплику из их кусков. "
                    : "")
                + "Пиши как в чате: обычно одна короткая реплика, по теме последних сообщений, "
                + "каждый раз новая. Эмодзи в конце не ставь. Выводи только текст реплики — "
                + "без имени, кавычек и пояснений. "
                + "Грубость, подколки и мат — в духе характера, как дружеский стёб. Но без "
                + "оскорблений за национальность, веру, ориентацию, здоровье и внешность, без угроз, "
                + "без оскорблений чьих-то родных, не доводи одного человека — не повторяй ему одну "
                + "и ту же подколку. Небылицы рассказывай смешные и про себя; о реальных участниках "
                + "фактов не выдумывай и вредных советов не давай. "
                + (must ? "К тебе обратились — обязательно ответь хотя бы короткой репликой."
                        : "Если вставить в разговор нечего, ответь одним символом «-».");
    }

    private String prompt(String context, boolean direct) {
        if (settings.isCharacter()) {
            return characterPrompt(context, direct);
        }

        var examples = new StringBuilder();

        for (var sample : examples(samples.all())) {
            examples.append("- ").append(sample.replace('\n', ' ')).append('\n');
        }

        var recentOwn = new StringBuilder();

        synchronized (said) {
            for (var line : said) {
                recentOwn.append("- ").append(line.replace('\n', ' ')).append('\n');
            }
        }

        var style = ParodyStyle.describe(samples.all());

        List<Long> active;

        synchronized (activeIds) {
            active = List.copyOf(activeIds);
        }

        var known = names.describe(active, 15);

        return (style.isEmpty() ? "" : "Его манера в цифрах — держись её:\n" + style + "\n\n")
                + (known.isEmpty() ? "" : "Как он называет людей в чате:\n" + known + "\n\n")
                + "Образцы его настоящих сообщений — для манеры, не для копирования:\n" + examples
                + (recentOwn.isEmpty() ? ""
                    : "\nТвои недавние реплики-пародии — не повторяй их и не пересказывай:\n" + recentOwn)
                + "\nПоследние сообщения в чате (снизу — самое новое):\n" + context + "\n\n"
                + (direct
                    ? "Последнее сообщение — ответ на его реплику. Ответь так, как ответил бы "
                    : "Встрянь в разговор одной репликой — так, как написал бы ")
                + settings.name() + ".";
    }

    private String characterPrompt(String context, boolean direct) {
        var examples = new StringBuilder();

        if (settings.target() != 0) {
            for (var sample : examples(samples.all())) {
                examples.append("- ").append(sample.replace('\n', ' ')).append('\n');
            }
        }

        var recentOwn = new StringBuilder();

        synchronized (said) {
            for (var line : said) {
                recentOwn.append("- ").append(line.replace('\n', ' ')).append('\n');
            }
        }

        var style = settings.target() == 0 ? "" : ParodyStyle.describe(samples.all());
        return (style.isEmpty() ? "" : "Форма сообщений в цифрах — держись её:\n" + style + "\n\n")
                + (examples.isEmpty() ? ""
                    : "Образцы формы сообщений (чужие — только для манеры письма, не копировать):\n" + examples)
                + (recentOwn.isEmpty() ? ""
                    : "\nТвои недавние реплики — не повторяй их и не пересказывай:\n" + recentOwn)
                + "\nПоследние сообщения в чате (снизу — самое новое):\n" + context + "\n\n"
                + (direct
                    ? "Последнее сообщение обращено к тебе. Ответь как " + settings.name() + "."
                    : "Встрянь в разговор одной репликой как " + settings.name() + ".");
    }

    /**
     * Какие образцы показать: половина — самые свежие, чтобы ИИ знал его
     * нынешние словечки, половина — вразброс по всей истории. Повторы
     * («ок», «ахах») убираются: десять одинаковых строк манеры не прибавят.
     */
    List<String> examples(List<String> all) {
        var distinct = new ArrayList<>(new LinkedHashSet<>(all.reversed()));

        if (distinct.size() <= EXAMPLES) {
            Collections.shuffle(distinct, random);
            return distinct;
        }

        var chosen = new ArrayList<>(distinct.subList(0, EXAMPLES / 2));
        var older = new ArrayList<>(distinct.subList(EXAMPLES / 2, distinct.size()));
        Collections.shuffle(older, random);
        chosen.addAll(older.subList(0, EXAMPLES - chosen.size()));
        Collections.shuffle(chosen, random);
        return chosen;
    }

    /** Строка, которая начинается чужой репликой: «Петя: …», «Коля (из Discord): …». */
    private static final java.util.regex.Pattern SPEAKER = java.util.regex.Pattern.compile(
            "^\\s*\\p{L}[\\p{L}\\p{N} ._-]{0,30}(\\s*\\([^)]{1,20}\\))?:\\s");

    /** Длиннее — уже не реплика в чате, а простыня. */
    static final int LONGEST_REPLY = 400;

    /**
     * Снимает то, что модель иногда добавляет сама: имя, пометку, кавычки.
     *
     * <p>И главное — обрезает, если модель пустилась сочинять разговор дальше,
     * за других: «Петя: …» на следующей строке — это уже слова, приписанные
     * живому человеку, и в чат они уйти не должны.
     */
    static String clean(String text, String name) {
        return clean(text, name, MARK);
    }

    static String clean(String text, String name, String mark) {
        var result = text.trim();

        if (result.equals("-") || result.equals("—")) {
            return "";
        }

        var lower = result.toLowerCase(java.util.Locale.ROOT);

        var ownMark = mark.trim().toLowerCase(java.util.Locale.ROOT);

        // Пустая пометка (у персонажа) — не пометка: иначе «начинается с пустоты»
        // совпадало бы всегда, и дописанную моделью «пародия:» никто бы не срезал
        if (!ownMark.isEmpty() && lower.startsWith(ownMark)) {
            result = result.substring(ownMark.length()).trim();
        } else if (lower.startsWith(MARK.trim())) {
            result = result.substring(MARK.trim().length()).trim();
        }

        lower = result.toLowerCase(java.util.Locale.ROOT);

        if (!name.isBlank() && lower.startsWith(name.toLowerCase(java.util.Locale.ROOT) + ":")) {
            result = result.substring(name.length() + 1).trim();
        }

        var lines = result.split("\\R");
        var kept = new StringBuilder();

        for (var i = 0; i < lines.length; i++) {
            if (i > 0 && SPEAKER.matcher(lines[i]).find()) {
                break;
            }

            if (!kept.isEmpty()) {
                kept.append('\n');
            }

            kept.append(lines[i]);
        }

        result = kept.toString().trim();

        if (result.length() >= 2 && (result.startsWith("\"") && result.endsWith("\"")
                || result.startsWith("«") && result.endsWith("»"))) {
            result = result.substring(1, result.length() - 1).trim();
        }

        if (result.length() > LONGEST_REPLY) {
            // Режем по последнему пробелу, чтобы не рвать слово пополам
            var space = result.lastIndexOf(' ', LONGEST_REPLY - 1);
            result = result.substring(0, space > LONGEST_REPLY / 2 ? space : LONGEST_REPLY - 1).trim() + "…";
        }

        return result.equals("-") || result.equals("—") ? "" : result;
    }

    private String clean(String text) {
        return clean(text, settings.name(), settings.mark());
    }
}
