package ru.virus.voicebridge;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Объявляет в канале новые записи из ленты сайта.
 *
 * <p>Раз в несколько минут читает ленту и сравнивает её с тем, что уже объявлял.
 * Опрос выбран потому, что сайт статический: сообщить о новой записи ему нечем,
 * а с обычным файлом договариваться не о чем.
 */
public final class PostsWatcher {

    private static final Logger log = LoggerFactory.getLogger(PostsWatcher.class);

    /**
     * Сколько записей объявляем за один заход.
     *
     * <p>Больше — значит с лентой случилось что-то несуразное: посты пересобрали,
     * поменяли заголовки, подставили другой адрес. Вываливать в канал два десятка
     * сообщений на такой случай нельзя, поэтому просто берём новое на учёт молча.
     */
    private static final int MAX_BURST = 5;

    /**
     * Цвет полосы по разделу записи.
     *
     * <p>Раздел есть не у всех постов — тем достаётся спокойный серый.
     */
    private static final Map<String, Color> COLORS = Map.of(
            "personal", new Color(0x1DB954),
            "game", new Color(0x9B59B6),
            "ai", new Color(0x00B0F4));

    private static final Color PLAIN_COLOR = new Color(0x4F545C);

    /**
     * Подписи разделов.
     *
     * <p>Те же слова, что на самой странице постов: карточка в Discord должна читаться
     * как её продолжение, а не как перевод с другого языка.
     */
    private static final Map<String, String> TAGS = Map.of(
            "personal", "личное",
            "game", "игра",
            "ai", "нейронки");

    private final JDA jda;
    private final long channelId;
    private final PostsFeed feed;
    private final Path state;
    private final int minutes;
    private final ScheduledExecutorService scheduler;

    /** Что уже объявляли. Хранится рядом с настройками, чтобы пережить перезапуск. */
    private final Set<String> seen = new LinkedHashSet<>();

    public PostsWatcher(JDA jda, long channelId, PostsFeed feed, Path state, int minutes) {
        this.jda = jda;
        this.channelId = channelId;
        this.feed = feed;
        this.state = state;
        this.minutes = minutes;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "posts-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        load();
        scheduler.scheduleAtFixedRate(this::tick, 10, minutes * 60L, TimeUnit.SECONDS);
        log.info("Слежу за лентой сайта: проверка раз в {} мин., канал {}.", minutes, channelId);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            check();
        } catch (Exception e) {
            // Планировщик молча снимает задачу с повтора, если из неё вылетело исключение.
            // Ловим всё: одна кривая запись не должна навсегда лишить канал новостей.
            log.error("Сбой при проверке ленты: {}", e.toString());
        }
    }

    private void check() {
        List<Post> posts;

        try {
            posts = feed.load();
        } catch (IOException e) {
            log.warn("Лента сайта не прочиталась: {}", e.getMessage());
            return;
        }

        if (posts.isEmpty()) {
            return;
        }

        // Первый запуск: объявлять всё, что написано за год, незачем — берём на учёт
        // и говорим только о том, что появится дальше
        if (seen.isEmpty()) {
            posts.forEach(post -> seen.add(post.key()));
            save();
            log.info("Лента взята на учёт: {} записей. Объявлять буду только новые.", posts.size());
            return;
        }

        var fresh = new ArrayList<Post>();

        for (var post : posts) {
            if (!seen.contains(post.key())) {
                fresh.add(post);
            }
        }

        if (fresh.isEmpty()) {
            return;
        }

        // Порядок публикации — от старых к новым: так лента в канале читается сверху вниз
        fresh.sort(Comparator.comparing(Post::key));

        if (fresh.size() > MAX_BURST) {
            fresh.forEach(post -> seen.add(post.key()));
            save();
            log.warn("В ленте сразу {} новых записей — похоже, её пересобрали. "
                    + "Ничего не отправляю, беру на учёт.", fresh.size());
            return;
        }

        var channel = jda.getChannelById(GuildMessageChannel.class, channelId);

        if (channel == null) {
            log.error("Канал {} не найден — не могу объявить новые записи. "
                    + "Проверь posts.channel и права бота.", channelId);
            return;
        }

        for (var post : fresh) {
            channel.sendMessageEmbeds(card(post)).queue(
                    ok -> { },
                    error -> log.error("Не удалось объявить «{}»: {}", post.title(), error.getMessage()));
            seen.add(post.key());
        }

        save();
        log.info("Объявлено новых записей: {}.", fresh.size());
    }

    /**
     * Собирает карточку записи.
     */
    private MessageEmbed card(Post post) {
        var feedUrl = feed.site() + "/posts.html";

        var card = new EmbedBuilder()
                .setColor(COLORS.getOrDefault(post.category(), PLAIN_COLOR))
                .setAuthor("virus / интерактив", feedUrl, feed.site() + "/icons/favicon-32x32.png")
                .setTitle(fit(post.title().isEmpty() ? "Новая запись" : post.title(),
                        MessageEmbed.TITLE_MAX_LENGTH), feedUrl);

        if (!post.text().isEmpty()) {
            card.setDescription(fit(post.text(), MessageEmbed.DESCRIPTION_MAX_LENGTH));
        }

        if (!post.image().isEmpty()) {
            card.setImage(feed.site() + "/" + encodePath(post.image()));
        }

        // Время отдаём моментом, а не строкой: Discord покажет его в часовом поясе
        // читающего и сам подпишет «сегодня» или «вчера»
        var published = post.published();

        if (published != null) {
            card.setTimestamp(published);
        }

        var tag = TAGS.getOrDefault(post.category(), post.category());

        if (!tag.isEmpty()) {
            card.setFooter("#" + tag);
        }

        return card.build();
    }

    /**
     * Укорачивает текст до того, что принимает Discord.
     *
     * <p>Длинный пост иначе не просто не отправится: карточка откажется собираться
     * с исключением, и вместе с ней встанет всё слежение за лентой.
     */
    private static String fit(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    /**
     * Готовит путь к картинке для ссылки.
     *
     * <p>Имена файлов на сайте русские, а в ссылке кириллица должна быть закодирована:
     * Discord ходит за картинкой сам и адрес с буквами как есть не разберёт.
     */
    private static String encodePath(String path) {
        var parts = path.split("/");
        var out = new StringBuilder();

        for (var part : parts) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            // URLEncoder делает форму для полей, а не для пути: пробел там плюс
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }

        return out.toString();
    }

    private void load() {
        if (!Files.isRegularFile(state)) {
            return;
        }

        try {
            seen.addAll(Files.readAllLines(state, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList());
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", state.toAbsolutePath(), e.getMessage());
        }
    }

    private void save() {
        try {
            Files.write(state, seen, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Без файла бот просто заново возьмёт ленту на учёт — записи не задвоятся,
            // но и о пропущенных не расскажет
            log.warn("Не удалось записать {}: {}", state.toAbsolutePath(), e.getMessage());
        }
    }
}
