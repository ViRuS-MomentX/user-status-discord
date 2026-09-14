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
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.List;

/**
 * Панель управления плеером: сообщение с кнопками, которое не нужно вызывать заново.
 */
public final class MusicPanel extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MusicPanel.class);

    private static final String SKIP = "music:skip";
    private static final String STOP = "music:stop";
    private static final String ADD = "music:add";
    private static final String TRENDING = "music:trending";
    private static final String QUIETER = "music:quieter";
    private static final String LOUDER = "music:louder";
    private static final String REFRESH = "music:refresh";

    private static final String ADD_MODAL = "music:addmodal";
    private static final String ADD_FIELD = "query";

    /** Насколько двигается громкость за одно нажатие. */
    private static final int VOLUME_STEP = 10;

    private static final Color PANEL_COLOR = new Color(0x1DB954);

    private final long guildId;
    private final MusicRequests requests;

    /** Картинка-подсказка под карточкой: что означает каждая иконка. Пусто — без неё. */
    private final String legendUrl;

    public MusicPanel(long guildId, MusicRequests requests, String legendUrl) {
        this.guildId = guildId;
        this.requests = requests;
        this.legendUrl = legendUrl;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            return;
        }

        var text = event.getMessage().getContentRaw().trim().toLowerCase();

        if (text.equals("панель") || text.equals("плеер")) {
            event.getChannel()
                    .sendMessageEmbeds(buildPanel(event.getGuild()))
                    .setComponents(buttons())
                    .queue();
        }
    }

    /**
     * Ряд кнопок без подписей — только иконки.
     *
     * <p>Все одного стиля: цвет плитки в такой сетке только мешает, различать кнопки
     * должна сама иконка. Что какая делает, объясняет картинка в карточке.
     */
    private List<ActionRow> buttons() {
        return List.of(
                ActionRow.of(
                        Button.secondary(SKIP, Emoji.fromUnicode("\u23ED\uFE0F")),
                        Button.secondary(STOP, Emoji.fromUnicode("\uD83D\uDED1")),
                        Button.secondary(ADD, Emoji.fromUnicode("\u2795")),
                        Button.secondary(QUIETER, Emoji.fromUnicode("\uD83D\uDD09")),
                        Button.secondary(LOUDER, Emoji.fromUnicode("\uD83D\uDD0A"))),
                ActionRow.of(
                        Button.secondary(TRENDING, Emoji.fromUnicode("\uD83D\uDD25")),
                        Button.secondary(REFRESH, Emoji.fromUnicode("\uD83D\uDD04"))));
    }

    /**
     * Собирает саму карточку плеера.
     */
    private MessageEmbed buildPanel(Guild guild) {
        var queue = requests.getMusic().getQueue();
        var current = queue.current();
        var waiting = queue.waiting();

        var embed = new EmbedBuilder()
                .setColor(PANEL_COLOR)
                .setTitle("🎵 Плеер");

        if (current == null) {
            embed.setDescription("Сейчас ничего не играет.\n"
                    + "Зайди в голосовой канал и нажми «В очередь» или «Трендовое».");
        } else {
            embed.setDescription("**" + current.getInfo().title + "**\n"
                    + current.getInfo().author);
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

        if (!legendUrl.isEmpty()) {
            embed.setImage(legendUrl);
        }

        return embed.setFooter("Кнопки работают у всех, кто сидит в голосовом канале").build();
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

        // Кнопка добавления открывает окно ввода, а показать его можно только в ответ
        // на само нажатие — никаких подтверждений до этого быть не должно
        if (ADD.equals(id)) {
            if (!inVoice(member)) {
                event.reply("Сначала зайди в голосовой канал.").setEphemeral(true).queue();
                return;
            }

            event.replyModal(Modal.create(ADD_MODAL, "Добавить в очередь")
                    .addComponents(Label.of("Название песни или исполнитель",
                            TextInput.create(ADD_FIELD, TextInputStyle.SHORT)
                                    .setPlaceholder("Кино — Группа крови")
                                    .setRequired(true)
                                    .setMaxLength(200)
                                    .build()))
                    .build()).queue();
            return;
        }

        // Остальные кнопки правят саму панель на месте. deferEdit подтверждает нажатие
        // сразу: у Discord на это три секунды, а работа может занять больше.
        event.deferEdit().queue();
        var hook = event.getHook();

        switch (id) {
            case SKIP -> skip(guild, hook);
            case STOP -> {
                requests.getMusic().disconnect(guild);
                refresh(guild, hook);
            }
            case QUIETER -> {
                requests.getMusic().setVolume(requests.getMusic().getVolume() - VOLUME_STEP);
                refresh(guild, hook);
            }
            case LOUDER -> {
                requests.getMusic().setVolume(requests.getMusic().getVolume() + VOLUME_STEP);
                refresh(guild, hook);
            }
            case TRENDING -> trending(member, guild, hook);
            case REFRESH -> refresh(guild, hook);
            default -> { }
        }
    }

    private void skip(Guild guild, InteractionHook hook) {
        if (requests.getMusic().getQueue().current() == null) {
            hook.sendMessage("Сейчас ничего не играет.").setEphemeral(true).queue();
            return;
        }

        requests.getMusic().getQueue().next();
        refresh(guild, hook);
    }

    private void trending(Member member, Guild guild, InteractionHook hook) {
        if (!connect(member, guild, hook)) {
            return;
        }

        hook.sendMessage("Собираю чарт...").setEphemeral(true).queue();
        requests.submitTrending(text -> hook.sendMessage(text).setEphemeral(true)
                .queue(ok -> refresh(guild, hook), error -> { }));
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!ADD_MODAL.equals(event.getModalId())) {
            return;
        }

        var member = event.getMember();
        var guild = event.getGuild();
        var value = event.getValue(ADD_FIELD);

        if (member == null || guild == null || value == null) {
            return;
        }

        event.deferReply(true).queue();
        var hook = event.getHook();

        if (!connect(member, guild, hook)) {
            return;
        }

        requests.submit(value.getAsString().trim(), text -> hook.sendMessage(text).queue());
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

    private boolean inVoice(Member member) {
        var state = member.getVoiceState();
        return state != null && state.getChannel() != null;
    }

    /**
     * Перерисовывает панель в том же сообщении.
     */
    private void refresh(Guild guild, InteractionHook hook) {
        hook.editOriginalEmbeds(buildPanel(guild)).queue(ok -> { },
                error -> log.error("Не удалось обновить панель: {}", error.getMessage()));
    }
}
