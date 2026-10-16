package ru.virus.voicebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
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

    private static final long RETRY_PAUSE_MS = 15_000;

    private final ParodySettings settings;
    private final Telegram telegram;
    private final Llm llm;
    private final ParodySamples samples;
    private final Random random = new Random();

    private final Deque<String> recent = new ArrayDeque<>();
    private final SequencedSet<Long> own = new LinkedHashSet<>();

    /** Одна реплика за раз: пока ИИ думает, новые поводы пропускаются. */
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "parody-writer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile long lastSent;
    private volatile boolean running = true;
    private Thread worker;

    /** Группа числом: в обновлениях она приходит только так. */
    private String chatId;

    public Parody(ParodySettings settings, Telegram telegram, Llm llm, ParodySamples samples) {
        this.settings = settings;
        this.telegram = telegram;
        this.llm = llm;
        this.samples = samples;
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

    private void handle(TelegramMessage message) {
        // Личку и чужие группы не читаем: бот живёт в одной группе
        if (!message.chat().equals(chatId) || message.text().isBlank()) {
            return;
        }

        remember(message.author() + ": " + message.text());

        if (message.authorId() == settings.target()) {
            samples.add(message.text());
            // Самому участнику не отвечаем: разговор с собой выглядел бы странно
            return;
        }

        // Ботов только слушаем: иначе два бота заговорят друг друга до бесконечности
        if (message.fromBot()) {
            return;
        }

        boolean direct;

        synchronized (own) {
            direct = message.replyToId() != 0 && own.contains(message.replyToId());
        }

        var now = System.currentTimeMillis();
        var gap = direct ? REPLY_GAP_MS : settings.cooldownMinutes() * 60_000L;

        if (now - lastSent < gap) {
            return;
        }

        if (!direct && random.nextInt(100) >= settings.chance()) {
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
        String text;

        try {
            text = clean(llm.complete(system(), prompt(context, replyTo != 0), 300));
        } catch (Exception e) {
            log.warn("ИИ не ответил: {}", e.getMessage());
            return;
        }

        if (text.isBlank()) {
            return;
        }

        try {
            var id = telegram.sendText(chatId, MARK + text, replyTo);
            remember(settings.name() + ": " + text);

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
        var all = new ArrayList<>(samples.all());
        Collections.shuffle(all, random);

        var examples = new StringBuilder();

        for (var sample : all.subList(0, Math.min(EXAMPLES, all.size()))) {
            examples.append("- ").append(sample.replace('\n', ' ')).append('\n');
        }

        return "Образцы его настоящих сообщений:\n" + examples
                + "\nПоследние сообщения в чате (снизу — самое новое):\n" + context + "\n\n"
                + (direct
                    ? "Последнее сообщение — ответ на его реплику. Ответь так, как ответил бы "
                    : "Встрянь в разговор одной репликой — так, как написал бы ")
                + settings.name() + ".";
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
