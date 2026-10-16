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
 * <p>Каждая реплика начинается с {@link #MARK}. Пометка зашита в код, а не в
 * настройки: бот выглядит как участник, и без неё в группе думали бы, что это
 * написал он сам.
 */
public final class Parody {

    private static final Logger log = LoggerFactory.getLogger(Parody.class);

    /** С чего начинается каждая реплика. */
    static final String MARK = "пародия: ";

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

    /** Как мост подписывает перенесённое из Discord: «Имя · Discord», дальше текст. */
    private static final String BRIDGED = " · Discord";

    private final ParodySettings settings;
    private final Telegram telegram;
    private final Llm llm;
    private final ParodySamples samples;
    private final ParodyState state;
    private final Random random = new Random();

    /** Часы подменяются в тестах: тихие часы и счёт за сутки зависят от них. */
    private Clock clock = Clock.systemDefaultZone();

    private final Deque<String> recent = new ArrayDeque<>();
    private final SequencedSet<Long> own = new LinkedHashSet<>();
    private final Deque<String> said = new ArrayDeque<>();

    /** Одна реплика за раз: пока ИИ думает, новые поводы пропускаются. */
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "parody-writer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile long lastSent;

    /** До какого времени ИИ не трогаем после сбоя, и сколько сбоев было подряд. */
    private volatile long llmBlockedUntil;
    private int llmFailures;
    private volatile boolean running = true;
    private Thread worker;

    /** Группа числом: в обновлениях она приходит только так. */
    private String chatId;

    /** «@имя» бота строчными: по нему узнаём, что к нему обратились. */
    private String mention = "";

    public Parody(ParodySettings settings, Telegram telegram, Llm llm, ParodySamples samples,
                  ParodyState state) {
        this.settings = settings;
        this.telegram = telegram;
        this.llm = llm;
        this.samples = samples;
        this.state = state;
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
                chatId = settings.chat().startsWith("@")
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
            } catch (Exception e) {
                log.warn("Пародийный бот не достучался до Telegram: {}. Пробую через {} с.",
                        e.getMessage(), RETRY_PAUSE_MS / 1000);

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

                log.warn("Telegram не ответил пародийному боту: {}. Пробую через {} с.",
                        e.getMessage(), RETRY_PAUSE_MS / 1000);

                if (!pause()) {
                    return;
                }
            }
        }
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

    private void handle(TelegramMessage incoming) {
        var message = unbridge(incoming);
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

        // Выключенный бот не копит и речь участника: раз попросили остановиться,
        // собирать его сообщения дальше тоже незачем
        if (state.isPaused()) {
            return;
        }

        if (message.authorId() == settings.target()) {
            samples.add(message.text());
            // Самому участнику не отвечаем: разговор с собой выглядел бы странно
            return;
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
        var gap = direct ? REPLY_GAP_MS : settings.cooldownMinutes() * 60_000L;

        if (now - lastSent < gap) {
            return;
        }

        if (!direct && random.nextInt(100) >= settings.chance()) {
            return;
        }

        if (settings.isQuiet(LocalTime.now(clock).getHour()) || overDaily()
                || now < llmBlockedUntil) {
            return;
        }

        if (samples.size() < settings.minSamples()) {
            log.debug("Образцов пока {} из {} — молчу.", samples.size(), settings.minSamples());
            return;
        }

        if (!busy.compareAndSet(false, true)) {
            return;
        }

        lastSent = now;
        var context = context();

        writer.execute(() -> {
            try {
                speak(context, direct ? message.messageId() : 0);
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * Сообщение, которое мост перенёс из Discord, — от лица того, кто его написал.
     *
     * <p>В Telegram его прислал бот моста, и без разбора ИИ видел бы безымянного
     * бота, а пародия молчала бы в ответ живым людям с той стороны.
     */
    static TelegramMessage unbridge(TelegramMessage message) {
        if (!message.fromBot()) {
            return message;
        }

        var text = message.text();
        var newline = text.indexOf('\n');
        var head = newline < 0 ? text : text.substring(0, newline);

        if (!head.endsWith(BRIDGED) || head.length() == BRIDGED.length()) {
            return message;
        }

        var author = head.substring(0, head.length() - BRIDGED.length()).trim() + " (из Discord)";
        var body = newline < 0 ? "" : text.substring(newline + 1).trim();

        return new TelegramMessage(message.updateId(), message.messageId(), message.replyToId(),
                message.chat(), message.authorId(), author, body, message.fileId(),
                message.fileName(), false, message.replyToAuthor(), message.replyToText());
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
                log.info("{} стёр образцы речи: {}.", message.author(), count);
                answer(message, "Стёрто сообщений: " + count + ". Пока не наберётся "
                        + settings.minSamples() + " новых, бот молчит.");
            }
            case "/parody_say" -> {
                if (state.isPaused()) {
                    answer(message, "Пародия выключена — сначала /parody_on.");
                } else if (samples.size() == 0) {
                    answer(message, "Пока не по чему пародировать: нет ни одного сообщения "
                            + settings.name() + ".");
                } else if (busy.compareAndSet(false, true)) {
                    lastSent = clock.millis();
                    var context = context();

                    writer.execute(() -> {
                        try {
                            speak(context, 0);
                        } finally {
                            busy.set(false);
                        }
                    });
                }
            }
            case "/parody_status" -> answer(message, status());
            default -> answer(message, """
                    /parody_say — сказать что-нибудь сейчас
                    /parody_off — выключить
                    /parody_on — включить
                    /parody_forget — стереть собранные сообщения
                    /parody_status — как дела""");
        }

        return true;
    }

    private String status() {
        var lines = new ArrayList<String>();
        lines.add(state.isPaused() ? "Выключена." : "Включена.");
        lines.add("Сообщений " + settings.name() + " собрано: " + samples.size()
                + (samples.size() < settings.minSamples()
                    ? " (начну с " + settings.minSamples() + ")" : "") + ".");
        lines.add("Встреваю с вероятностью " + settings.chance() + "%, не чаще раза в "
                + settings.cooldownMinutes() + " мин.");
        lines.add("Сегодня реплик: " + state.sentToday(LocalDate.now(clock))
                + (settings.daily() > 0 ? " из " + settings.daily() : "") + ".");

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

    private void remember(String line) {
        synchronized (recent) {
            recent.addLast(line);

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

    private void speak(String context, long replyTo) {
        try {
            // Пока ИИ думает, в группе видно «печатает…» — как у живого человека
            telegram.typing(chatId);
        } catch (Exception e) {
            log.debug("«Печатает» не показалось: {}", e.getMessage());
        }

        String text;

        try {
            text = clean(llm.complete(system(), prompt(context, replyTo != 0), 300));
            llmFailures = 0;
        } catch (Exception e) {
            // Неверный ключ или кончился лимит сам не пройдёт, а долбить API на
            // каждое сообщение группы — только засорять лог
            llmFailures++;
            var pause = Math.min(LLM_PAUSE_MS << Math.min(llmFailures - 1, 10), LLM_PAUSE_LIMIT_MS);
            llmBlockedUntil = clock.millis() + pause;
            log.warn("ИИ не ответил: {}. Следующая попытка не раньше чем через {} мин.",
                    e.getMessage(), pause / 60_000);
            return;
        }

        if (text.isBlank() || repeats(text)) {
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
            var id = telegram.sendText(chatId, MARK + text, replyTo);
            state.countSent(LocalDate.now(clock));
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

    private static String normalize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", " ").trim();
    }

    private String system() {
        var name = settings.name();
        return "Ты пишешь пародийные реплики для группового чата в Telegram в манере участника "
                + "по имени " + name + ". Он сам попросил об этой пародии, и каждая реплика "
                + "публикуется с пометкой «пародия». Перенимай его манеру по образцам: словарь, "
                + "сленг, длину фраз, пунктуацию, регистр, эмодзи, юмор, типичные словечки. "
                + "Пиши так, как он ответил бы на последние сообщения чата: обычно одна короткая "
                + "реплика. Выводи только текст реплики — без имени, кавычек, пометок и пояснений. "
                + "Не выдумывай о нём личных фактов, событий, признаний и мнений о реальных людях, "
                + "которых нет в образцах. Без угроз, травли и оскорблений участников. "
                + "Если сказать нечего, ответь одним символом «-».";
    }

    private String prompt(String context, boolean direct) {
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

        return "Образцы его настоящих сообщений:\n" + examples
                + (recentOwn.isEmpty() ? ""
                    : "\nТвои недавние реплики-пародии — не повторяй их и не пересказывай:\n" + recentOwn)
                + "\nПоследние сообщения в чате (снизу — самое новое):\n" + context + "\n\n"
                + (direct
                    ? "Последнее сообщение — ответ на его реплику. Ответь так, как ответил бы "
                    : "Встрянь в разговор одной репликой — так, как написал бы ")
                + settings.name() + ".";
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

    /** Снимает то, что модель иногда добавляет сама: имя, пометку, кавычки. */
    static String clean(String text, String name) {
        var result = text.trim();

        if (result.equals("-") || result.equals("—")) {
            return "";
        }

        var lower = result.toLowerCase(java.util.Locale.ROOT);

        if (lower.startsWith(MARK.trim())) {
            result = result.substring(MARK.trim().length()).trim();
        } else if (!name.isBlank() && lower.startsWith(name.toLowerCase(java.util.Locale.ROOT) + ":")) {
            result = result.substring(name.length() + 1).trim();
        }

        if (result.length() >= 2 && (result.startsWith("\"") && result.endsWith("\"")
                || result.startsWith("«") && result.endsWith("»"))) {
            result = result.substring(1, result.length() - 1).trim();
        }

        return result;
    }

    private String clean(String text) {
        return clean(text, settings.name());
    }
}
