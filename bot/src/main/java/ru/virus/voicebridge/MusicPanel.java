package ru.virus.voicebridge;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Панель управления плеером: сообщение с кнопками, которое не нужно вызывать заново.
 *
 * <p>Кнопки без подписей — различать их должна иконка. Что какая делает, объясняет
 * картинка, ссылку на которую можно задать в настройках.
 */
public final class MusicPanel extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MusicPanel.class);

    // Первый ряд: управление воспроизведением
    private static final String QUIETER = "music:quieter";
    private static final String PREVIOUS = "music:previous";
    private static final String PAUSE = "music:pause";
    private static final String NEXT = "music:next";
    private static final String LOUDER = "music:louder";

    // Второй ряд: готовые подборки
    private static final String WINTER = "music:winter";
    private static final String SPRING = "music:spring";
    private static final String CHARTS = "music:charts";
    private static final String AUTUMN = "music:autumn";
    private static final String SUMMER = "music:summer";

    // Третий ряд: заказы и управление очередью
    private static final String OWN = "music:own";
    private static final String ARTIST = "music:artist";
    private static final String STOP = "music:stop";
    private static final String CODE = "music:code";
    private static final String CLEAR = "music:clear";

    private static final String OWN_MODAL = "music:ownmodal";
    private static final String ARTIST_MODAL = "music:artistmodal";
    private static final String FIELD = "query";

    /** Насколько двигается громкость за одно нажатие. */
    private static final int VOLUME_STEP = 10;

    private static final Color PANEL_COLOR = new Color(0x1DB954);

    /**
     * Сколько ждать перед перерисовкой панели.
     *
     * <p>Пауза не для красоты: пока грузится плейлист, очередь пополняется пятнадцать раз
     * подряд, и без неё бот пятнадцать раз правил бы одно сообщение — Discord за такое
     * придерживает запросы.
     */
    private static final long REFRESH_DELAY_MS = 2000;

    /** Под этим именем подсказка уезжает в Discord и под ним же ищется в карточке. */
    private static final String LEGEND_NAME = "panel-legend.png";

    private final long guildId;
    private final MusicRequests requests;
    private final PanelSettings settings;
    private final PanelIcons icons;

    /** Картинка-подсказка на диске: бот прикладывает её сам, хостинг не нужен. */
    private final Path legend;

    /**
     * Куда Discord положил приложенную подсказку.
     *
     * <p>При перерисовке карточку правят на месте, файл заново не отправляют — значит
     * ссылаться на него надо уже по адресу, а не по имени вложения.
     */
    private volatile String legendUrl;

    /**
     * Где висит последняя панель. Новая заменяет её, чтобы в голосовом чате не
     * копились одинаковые сообщения.
     */
    private volatile long lastChannelId = 0;
    private volatile long lastMessageId = 0;

    /** Через что править уже отправленную панель: события плеера приходят без канала. */
    private volatile net.dv8tion.jda.api.JDA jda;

    private final ScheduledExecutorService refresher =
            Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "panel-refresh");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicBoolean waiting = new AtomicBoolean();

    public MusicPanel(long guildId, MusicRequests requests, PanelSettings settings, PanelIcons icons,
                      Path legend) {
        this.guildId = guildId;
        this.requests = requests;
        this.settings = settings;
        this.icons = icons;
        this.legend = legend;

        // Очередь живёт своей жизнью: трек кончается сам, плейлист догружается фоном.
        // Без этого панель показывала бы состояние на момент нажатия кнопки.
        requests.getMusic().getQueue().setOnChange(this::scheduleRefresh);
    }

    /**
     * Просит перерисовать панель, слив частые правки в одну.
     */
    private void scheduleRefresh() {
        if (waiting.compareAndSet(false, true)) {
            refresher.schedule(() -> {
                waiting.set(false);
                redraw();
            }, REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Перерисовывает панель там, где она висит.
     */
    private void redraw() {
        var connection = jda;
        var messageId = lastMessageId;

        if (connection == null || messageId == 0) {
            return;
        }

        var channel = connection.getChannelById(GuildMessageChannel.class, lastChannelId);

        if (channel == null) {
            return;
        }

        // Панель могли удалить руками — молчим, на следующей команде выйдет новая
        channel.editMessageEmbedsById(messageId, buildPanel(shownImage()))
                .setComponents(buttons())
                .queue(ok -> { }, error -> { });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            return;
        }

        var text = event.getMessage().getContentRaw().trim().toLowerCase();

        if (text.equals("панель") || text.equals("плеер")) {
            post(event.getChannel().asGuildMessageChannel(), null);
        }
    }

    /**
     * Публикует панель, убрав предыдущую.
     *
     * @param requester кого упомянуть; <code>null</code>, если панель вызвали вручную
     */
    public void post(GuildMessageChannel channel, Member requester) {
        removeOld(channel.getGuild());

        var attached = legendUpload();
        // Пока файл едет вместе с сообщением, ссылаться на него можно только по имени
        var embed = buildPanel(attached != null ? "attachment://" + LEGEND_NAME : settings.image());

        var action = requester == null
                ? channel.sendMessageEmbeds(embed)
                : channel.sendMessage(requester.getAsMention() + " включает музыку").setEmbeds(embed);

        if (attached != null) {
            action = action.setFiles(attached);
        }

        action.setComponents(buttons()).queue(message -> {
            jda = message.getJDA();
            lastChannelId = channel.getIdLong();
            lastMessageId = message.getIdLong();
            legendUrl = message.getAttachments().isEmpty()
                    ? null
                    : message.getAttachments().get(0).getUrl();
        }, error -> log.error("Не удалось опубликовать панель: {}", error.getMessage()));
    }

    /**
     * Открывает файл подсказки, если он лежит на месте.
     *
     * @return <code>null</code>, если файла нет — тогда в ход идёт ссылка из настроек
     */
    private FileUpload legendUpload() {
        if (!Files.isRegularFile(legend)) {
            return null;
        }

        try {
            return FileUpload.fromData(legend, LEGEND_NAME);
        } catch (Exception e) {
            log.error("Не удалось приложить подсказку {}: {}", legend.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /** Чем показывать подсказку в уже отправленной карточке. */
    private String shownImage() {
        var uploaded = legendUrl;
        return uploaded != null ? uploaded : settings.image();
    }

    /**
     * Показывает панель в текстовом чате голосового канала.
     */
    public void postInVoice(Guild guild, AudioChannel channel, Member requester) {
        if (channel instanceof GuildMessageChannel voiceChat) {
            post(voiceChat, requester);
        }
    }

    private void removeOld(Guild guild) {
        if (lastMessageId == 0) {
            return;
        }

        var channel = guild.getChannelById(GuildMessageChannel.class, lastChannelId);
        var messageId = lastMessageId;

        lastMessageId = 0;

        if (channel != null) {
            // Старую панель могли удалить руками — тогда молчим, это не беда
            channel.deleteMessageById(messageId).queue(ok -> { }, error -> { });
        }
    }

    /**
     * Три ряда по пять иконок.
     *
     * <p>Иконка паузы меняется на «продолжить», когда воспроизведение остановлено:
     * кнопка одна, и по ней должно быть видно, что она сделает.
     */
    private List<ActionRow> buttons() {
        var paused = requests.getMusic().getQueue().isPaused();

        return List.of(
                ActionRow.of(
                        icon(QUIETER, "quieter", "🔉"),
                        icon(PREVIOUS, "previous", "⏮️"),
                        paused ? icon(PAUSE, "play", "▶️") : icon(PAUSE, "pause", "⏸️"),
                        icon(NEXT, "next", "⏭️"),
                        icon(LOUDER, "louder", "🔊")),
                ActionRow.of(
                        icon(WINTER, "winter", "🎄"),
                        icon(SPRING, "spring", "🌸"),
                        icon(CHARTS, "charts", "🔥"),
                        icon(AUTUMN, "autumn", "🍂"),
                        icon(SUMMER, "summer", "☀️")),
                ActionRow.of(
                        icon(OWN, "own", "🔍"),
                        icon(ARTIST, "artist", "🎤"),
                        icon(STOP, "stop", "🛑"),
                        icon(CODE, "code", "🔢"),
                        icon(CLEAR, "clear", "🗑️")));
    }

    /**
     * Кнопка с иконкой сервера, а пока её нет — со стандартным символом.
     */
    private Button icon(String id, String key, String fallback) {
        return Button.secondary(id, icons.get(key, fallback));
    }

    /**
     * Собирает саму карточку плеера.
     */
    private MessageEmbed buildPanel(String image) {
        var queue = requests.getMusic().getQueue();
        var current = queue.current();
        var waiting = queue.waiting();

        var embed = new EmbedBuilder()
                .setColor(PANEL_COLOR)
                .setTitle("🎵 Плеер");

        if (current == null) {
            embed.setDescription("Сейчас ничего не играет.");
        } else {
            embed.setDescription((queue.isPaused() ? "⏸️ " : "")
                    + "**" + current.getInfo().title + "**\n" + current.getInfo().author);
        }

        embed.addField("В очереди", waiting.size() + " треков", true);
        embed.addField("Громкость", requests.getMusic().getVolume() + "%", true);

        if (!waiting.isEmpty()) {
            var next = new StringBuilder();
            // Три строки — столько влезает, не превращая панель в простыню
            for (var i = 0; i < Math.min(3, waiting.size()); i++) {
                next.append(i + 1).append(". ").append(waiting.get(i).getInfo().title).append('\n');
            }
            embed.addField("Дальше", next.toString(), false);
        }

        if (image != null && !image.isEmpty()) {
            embed.setImage(image);
        }

        return embed.build();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        var id = event.getComponentId();

        if (!id.startsWith("music:")) {
            return;
        }

        var member = event.getMember();
        var guild = event.getGuild();

        if (member == null || guild == null || guild.getIdLong() != guildId) {
            return;
        }

        // Кнопки с окном ввода нельзя подтверждать заранее: окно показывается только
        // в ответ на само нажатие, и любой ответ до него закрывает эту возможность
        if (OWN.equals(id) || ARTIST.equals(id)) {
            openModal(event, member, id);
            return;
        }

        // Остальные правят панель на месте. deferEdit подтверждает нажатие сразу:
        // у Discord на это три секунды, а работа может занять больше.
        event.deferEdit().queue();
        var hook = event.getHook();

        switch (id) {
            case QUIETER -> volume(-VOLUME_STEP, hook);
            case LOUDER -> volume(VOLUME_STEP, hook);
            case PREVIOUS -> previous(hook);
            case PAUSE -> pause(hook);
            case NEXT -> skip(hook);
            case WINTER -> playlist(member, guild, hook, settings.winter(), "новогоднее");
            case SPRING -> playlist(member, guild, hook, settings.spring(), "весеннее");
            case AUTUMN -> playlist(member, guild, hook, settings.autumn(), "осеннее");
            case SUMMER -> playlist(member, guild, hook, settings.summer(), "летнее");
            case CHARTS -> charts(member, guild, hook);
            case STOP -> {
                requests.getMusic().disconnect(guild);
                refresh(hook);
            }
            case CLEAR -> {
                requests.getMusic().getQueue().clearQueue();
                refresh(hook);
            }
            case CODE -> hook.sendMessage("Плейлисты по коду пока не подключены.")
                    .setEphemeral(true).queue();
            default -> { }
        }
    }

    private void openModal(ButtonInteractionEvent event, Member member, String id) {
        if (member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            event.reply("Сначала зайди в голосовой канал.").setEphemeral(true).queue();
            return;
        }

        var own = OWN.equals(id);

        event.replyModal(Modal.create(own ? OWN_MODAL : ARTIST_MODAL,
                        own ? "Указать свою" : "Указать исполнителя")
                .addComponents(Label.of(own ? "Название песни" : "Имя исполнителя",
                        TextInput.create(FIELD, TextInputStyle.SHORT)
                                .setPlaceholder(own ? "Кино — Группа крови" : "Кино")
                                .setRequired(true)
                                .setMaxLength(200)
                                .build()))
                .build()).queue();
    }

    private void volume(int delta, InteractionHook hook) {
        requests.getMusic().setVolume(requests.getMusic().getVolume() + delta);
        refresh(hook);
    }

    private void pause(InteractionHook hook) {
        if (requests.getMusic().getQueue().current() == null) {
            hook.sendMessage("Сейчас ничего не играет.").setEphemeral(true).queue();
            return;
        }

        requests.getMusic().getQueue().togglePause();
        refresh(hook);
    }

    private void skip(InteractionHook hook) {
        if (requests.getMusic().getQueue().current() == null) {
            hook.sendMessage("Сейчас ничего не играет.").setEphemeral(true).queue();
            return;
        }

        requests.getMusic().getQueue().next();
        refresh(hook);
    }

    private void previous(InteractionHook hook) {
        if (!requests.getMusic().getQueue().previous()) {
            hook.sendMessage("Раньше ничего не играло.").setEphemeral(true).queue();
            return;
        }

        refresh(hook);
    }

    private void playlist(Member member, Guild guild, InteractionHook hook, String term, String label) {
        if (!connect(member, guild, hook)) {
            return;
        }

        requests.submitTerm(term, label, text -> notice(hook, text));
    }

    private void charts(Member member, Guild guild, InteractionHook hook) {
        if (!connect(member, guild, hook)) {
            return;
        }

        requests.submitTrending(text -> notice(hook, text));
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        var own = OWN_MODAL.equals(event.getModalId());

        if (!own && !ARTIST_MODAL.equals(event.getModalId())) {
            return;
        }

        var member = event.getMember();
        var guild = event.getGuild();
        var value = event.getValue(FIELD);

        if (member == null || guild == null || value == null) {
            return;
        }

        event.deferReply(true).queue();
        var hook = event.getHook();

        if (!connect(member, guild, hook)) {
            return;
        }

        var query = value.getAsString().trim();

        if (own) {
            requests.submit(query, text -> hook.sendMessage(text).queue());
        } else {
            requests.submitArtist(query, text -> hook.sendMessage(text).queue());
        }

        // Панель показываем там, где сидит заказавший, с упоминанием его самого
        var state = member.getVoiceState();
        if (state != null && state.getChannel() != null) {
            postInVoice(guild, state.getChannel(), member);
        }
    }

    /**
     * Сообщение о ходе дела плюс обновление панели.
     */
    private void notice(InteractionHook hook, String text) {
        hook.sendMessage(text).setEphemeral(true).queue(ok -> refresh(hook), error -> { });
    }

    /**
     * Подключается к каналу нажавшего, если бот ещё не в голосовом.
     *
     * @return <code>false</code>, если подключаться некуда — сообщение уже отправлено
     */
    private boolean connect(Member member, Guild guild, InteractionHook hook) {
        var state = member.getVoiceState();

        if (state == null || state.getChannel() == null) {
            hook.sendMessage("Сначала зайди в голосовой канал.").setEphemeral(true).queue();
            return false;
        }

        requests.getMusic().connect(guild, state.getChannel());
        return true;
    }

    /**
     * Перерисовывает панель в том же сообщении.
     */
    private void refresh(InteractionHook hook) {
        hook.editOriginalEmbeds(buildPanel(shownImage())).setComponents(buttons()).queue(ok -> { },
                error -> log.error("Не удалось обновить панель: {}", error.getMessage()));
    }
}
