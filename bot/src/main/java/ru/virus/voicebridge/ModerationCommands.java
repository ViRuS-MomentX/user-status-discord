package ru.virus.voicebridge;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Команда очистки канала.
 */
public final class ModerationCommands extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ModerationCommands.class);

    /** За раз Discord позволяет удалить не больше сотни сообщений. */
    private static final int MAX_AT_ONCE = 100;

    /** Массовое удаление работает только со свежими сообщениями. */
    private static final Duration BULK_LIMIT = Duration.ofDays(14);

    private final long guildId;

    public ModerationCommands(long guildId) {
        this.guildId = guildId;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            return;
        }

        var text = event.getMessage().getContentRaw().trim();
        if (!text.toLowerCase().startsWith("очистить")) {
            return;
        }

        var member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MESSAGE_MANAGE)) {
            reply(event, "Для этого нужно право «Управление сообщениями».");
            return;
        }

        var argument = text.substring("очистить".length()).trim();
        int count;

        try {
            count = Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            reply(event, "Напиши сколько сообщений удалить, например: очистить 20");
            return;
        }

        if (count < 1 || count > MAX_AT_ONCE) {
            reply(event, "Можно удалить от 1 до " + MAX_AT_ONCE + " сообщений за раз.");
            return;
        }

        purge(event, count);
    }

    private void purge(MessageReceivedEvent event, int count) {
        if (!(event.getChannel() instanceof GuildMessageChannel)) {
            return;
        }

        var channel = (GuildMessageChannel) event.getChannel();

        if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            reply(event, "У меня нет права «Управление сообщениями» в этом канале.");
            return;
        }

        // Берём на одно больше: саму команду тоже убираем, и она не должна съедать лимит
        channel.getIterableHistory().takeAsync(count + 1).thenAccept(history -> {
            var cutoff = OffsetDateTime.now().minus(BULK_LIMIT);

            List<Message> fresh = new ArrayList<>();
            var skippedOld = 0;

            for (var message : history) {
                if (message.getTimeCreated().isBefore(cutoff)) {
                    // Discord запрещает массово удалять старое, а поштучно это сотни
                    // запросов и минуты ожидания — такие сообщения просто пропускаем
                    skippedOld++;
                } else {
                    fresh.add(message);
                }
            }

            if (fresh.isEmpty()) {
                reply(event, "Нечего удалять: все сообщения старше 14 дней.");
                return;
            }

            // Команда сама попала в список, поэтому из отчёта её вычитаем
            var deleted = fresh.size() - 1;
            var note = skippedOld > 0 ? " Пропущено старше 14 дней: " + skippedOld + "." : "";

            delete(channel, fresh).queue(
                    ok -> channel.sendMessage("Удалено сообщений: " + deleted + "." + note)
                            // Отчёт убирается сам, чтобы не оставлять мусор вместо мусора
                            .delay(5, TimeUnit.SECONDS)
                            .flatMap(Message::delete)
                            .queue(),
                    error -> {
                        log.error("Не удалось очистить канал: {}", error.getMessage());
                        channel.sendMessage("Не получилось удалить сообщения: " + error.getMessage()).queue();
                    });
        });
    }

    /**
     * Массовое удаление требует минимум двух сообщений, поэтому одно удаляем обычным способом.
     */
    private net.dv8tion.jda.api.requests.RestAction<Void> delete(GuildMessageChannel channel, List<Message> messages) {
        return messages.size() == 1 ? messages.get(0).delete() : channel.deleteMessages(messages);
    }

    private void reply(MessageReceivedEvent event, String text) {
        event.getMessage().reply(text).queue();
    }
}
