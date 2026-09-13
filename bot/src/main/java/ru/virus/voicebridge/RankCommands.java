package ru.virus.voicebridge;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Команды ранговой системы и кнопка повышения.
 */
public final class RankCommands extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RankCommands.class);

    /** Идентификатор кнопки. Сообщение с ней живёт вечно, так что он должен пережить перезапуск. */
    private static final String BUTTON_UP = "rank:up";
    private static final String BUTTON_BALANCE = "rank:balance";

    private static final Color EMBED_COLOR = new Color(0x8B5CF6);

    private final long guildId;
    private final RankStore store;
    private final RankLadder ladder;

    public RankCommands(long guildId, RankStore store, RankLadder ladder) {
        this.guildId = guildId;
        this.store = store;
        this.ladder = ladder;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            return;
        }

        var text = event.getMessage().getContentRaw().trim().toLowerCase();

        if (text.equals("эмбед ранк")) {
            postLadder(event);
        } else if (text.equals("баланс")) {
            postBalance(event);
        } else if (text.equals("ранг айди")) {
            postIds(event);
        }
    }

    /**
     * Выводит ID всех ролей и коды эмодзи сервера — то, что нужно вписать в config.properties.
     *
     * <p>Собирать это руками через правый клик по каждой роли долго и легко ошибиться,
     * а бот и так всё это видит.
     */
    private void postIds(MessageReceivedEvent event) {
        var member = event.getMember();

        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.getMessage().reply("Эту команду может использовать только управляющий сервером.").queue();
            return;
        }

        var guild = event.getGuild();
        var lines = new ArrayList<String>();

        lines.add("# Роли сервера (сверху самые высокие)");
        for (var role : guild.getRoles()) {
            if (!role.isPublicRole()) { // @everyone в конфиге не нужна
                lines.add(role.getId() + "  " + role.getName());
            }
        }

        var emojis = guild.getEmojis();
        if (!emojis.isEmpty()) {
            lines.add("");
            lines.add("# Эмодзи сервера");
            for (var emoji : emojis) {
                lines.add(emoji.getAsMention() + "  :" + emoji.getName() + ":");
            }
        }

        sendChunked(event, lines);
    }

    /**
     * Отправляет список блоками, укладываясь в лимит длины сообщения Discord.
     */
    private void sendChunked(MessageReceivedEvent event, List<String> lines) {
        // Лимит сообщения — 2000 символов, оставляем запас на обрамление блока кода
        final var limit = 1900;
        var chunk = new StringBuilder();

        for (var line : lines) {
            if (chunk.length() + line.length() + 1 > limit) {
                event.getChannel().sendMessage("```\n" + chunk + "```").queue();
                chunk.setLength(0);
            }
            chunk.append(line).append('\n');
        }

        if (chunk.length() > 0) {
            event.getChannel().sendMessage("```\n" + chunk + "```").queue();
        }
    }

    /**
     * Публикует сообщение с лестницей рангов и кнопкой.
     */
    private void postLadder(MessageReceivedEvent event) {
        var member = event.getMember();

        // Кнопку раздаёт роли, так что вешать её на канал может только тот, кто и так
        // управляет сервером.
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.getMessage().reply("Эту команду может использовать только управляющий сервером.").queue();
            return;
        }

        if (ladder.getSteps().isEmpty()) {
            event.getMessage().reply("Ступени рангов ещё не заданы. Напиши «ранг айди», "
                    + "впиши роли в config.properties и перезапусти бота.").queue();
            return;
        }

        var up = Button.success(BUTTON_UP, "Повысить ранг").withEmoji(Emoji.fromUnicode("🚀"));
        var balance = Button.secondary(BUTTON_BALANCE, "Баланс").withEmoji(Emoji.fromUnicode("💰"));

        event.getChannel()
                .sendMessageEmbeds(buildLadderEmbed(event.getGuild()))
                .setComponents(ActionRow.of(up, balance))
                .queue();
    }

    /**
     * Отвечает участнику его балансом и текущим положением на лестнице.
     */
    private void postBalance(MessageReceivedEvent event) {
        var member = event.getMember();
        if (member == null) {
            return;
        }

        event.getMessage().replyEmbeds(buildBalanceEmbed(member, event.getGuild())).queue();
    }

    /**
     * Собирает карточку с балансом участника.
     */
    private MessageEmbed buildBalanceEmbed(Member member, Guild guild) {
        var entry = store.get(member.getId());
        var current = ladder.resolveCurrentRole(guild, entry.rank);

        var embed = new EmbedBuilder()
                .setColor(EMBED_COLOR)
                .setAuthor(member.getEffectiveName(), null, member.getEffectiveAvatarUrl())
                .addField("Баланс", entry.coins + " " + ladder.getCoinEmoji(), true)
                .addField("Текущий ранг", current == null ? "нет" : current.getAsMention(), true);

        if (entry.rank < ladder.getSteps().size()) {
            var next = ladder.getSteps().get(entry.rank);
            var nextRole = next.resolve(guild);
            var missing = next.getPrice() - entry.coins;

            embed.addField("Следующий ранг",
                    (nextRole == null ? "?" : nextRole.getAsMention()) + " — " + next.getPrice() + " " + ladder.getCoinEmoji()
                            + (missing > 0 ? "\nне хватает " + missing + " " + ladder.getCoinEmoji() : "\nуже можно повышаться"),
                    false);
        } else {
            embed.addField("Следующий ранг", "выше уже некуда", false);
        }

        // Минуты, ещё не превратившиеся в монету: иначе непонятно, идёт ли вообще начисление
        embed.setFooter("до следующей монеты: " + (ladder.getMinutesPerCoin() - entry.minutes) + " мин");

        return embed.build();
    }

    /**
     * Собирает эмбед с лестницей.
     */
    private MessageEmbed buildLadderEmbed(Guild guild) {
        var coin = ladder.getCoinEmoji();

        // На картинке лестница идёт сверху вниз от самого дорогого ранга, а внутри
        // ступени хранятся в порядке повышения, от дешёвого. Поэтому переворачиваем.
        var top = new ArrayList<>(ladder.getSteps());
        Collections.reverse(top);

        var path = new StringBuilder();
        for (var i = 0; i < top.size(); i++) {
            var step = top.get(i);
            var role = step.resolve(guild);
            path.append(i + 1).append(". ")
                    .append(role == null ? "?" : role.getAsMention())
                    .append(" — ").append(step.getPrice()).append(' ').append(coin).append('\n');
        }

        var embed = new EmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle("🏆 Ранговая система")
                .setDescription("Выберите свой путь рангов и поднимайтесь по лестнице статуса. "
                        + "Каждый следующий ранг заменяет предыдущий и списывает стоимость в " + coin + ".")
                .addField("🔗 Путь рангов", path.toString(), false);

        var start = ladder.resolveStartRole(guild);
        if (start != null) {
            embed.addField("🌱 Стартовая роль", start.getAsMention(), false);
        }

        embed.addField("⚙️ Как работает",
                "• повышение идёт по очереди\n"
                        + "• предыдущая роль снимается автоматически\n"
                        + "• цена списывается сразу после подтверждения\n"
                        + "• монета за " + ladder.getMinutesPerCoin()
                        + " минут в голосовом канале, если в нём есть кто-то ещё",
                false);

        return embed.setFooter("Повышение доступно по кнопке ниже • роль меняется автоматически").build();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        var member = event.getMember();
        var guild = event.getGuild();

        if (member == null || guild == null || guild.getIdLong() != guildId) {
            return;
        }

        // Ответ виден только нажавшему: иначе кнопка под общим сообщением
        // засыпала бы канал чужими балансами.
        if (BUTTON_BALANCE.equals(event.getComponentId())) {
            event.replyEmbeds(buildBalanceEmbed(member, guild)).setEphemeral(true).queue();
            return;
        }

        if (!BUTTON_UP.equals(event.getComponentId())) {
            return;
        }

        var entry = store.get(member.getId());

        if (entry.rank >= ladder.getSteps().size()) {
            event.reply("Ты уже на вершине, выше рангов нет.").setEphemeral(true).queue();
            return;
        }

        var step = ladder.getSteps().get(entry.rank);
        var nextRole = step.resolve(guild);

        if (nextRole == null) {
            log.error("Роль ступени {} не найдена, повышение невозможно.", entry.rank + 1);
            event.reply("Роль для следующего ранга не найдена на сервере. Скажи администратору.")
                    .setEphemeral(true).queue();
            return;
        }

        // Списываем до выдачи роли: иначе два быстрых нажатия успели бы пройти проверку
        // баланса оба. Если роль выдать не выйдет, деньги вернём ниже.
        if (!store.promote(member.getId(), step.getPrice())) {
            event.reply("Не хватает монет: нужно " + step.getPrice() + " " + ladder.getCoinEmoji()
                    + ", у тебя " + entry.coins + ".").setEphemeral(true).queue();
            return;
        }

        var previous = ladder.resolveCurrentRole(guild, entry.rank);

        List<Role> toRemove = new ArrayList<>();
        if (previous != null && !previous.equals(nextRole)) {
            toRemove.add(previous);
        }

        guild.modifyMemberRoles(member, Collections.singletonList(nextRole), toRemove).queue(
                ok -> {
                    store.save();
                    event.reply("Ранг повышен: " + nextRole.getAsMention() + ". Списано "
                            + step.getPrice() + " " + ladder.getCoinEmoji() + ".").setEphemeral(true).queue();
                    log.info("{} повысил ранг до «{}».", member.getUser().getName(), nextRole.getName());
                },
                error -> {
                    store.refund(member.getId(), step.getPrice());
                    store.save();
                    log.error("Не удалось выдать роль «{}»: {}", nextRole.getName(), error.getMessage());
                    event.reply("Не получилось выдать роль, монеты возвращены. "
                            + "Скорее всего у бота нет права «Управление ролями» или его роль ниже выдаваемой.")
                            .setEphemeral(true).queue();
                });
    }
}
