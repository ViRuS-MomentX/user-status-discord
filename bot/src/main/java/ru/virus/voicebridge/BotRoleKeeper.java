package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Держит на всех ботах сервера одну общую роль.
 *
 * <p>Работает с двух сторон: при запуске проходит по уже добавленным ботам, а дальше
 * ловит новых в момент входа. Без первого прохода роль получали бы только те, кого
 * добавили после запуска.
 */
public final class BotRoleKeeper extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BotRoleKeeper.class);

    private final long guildId;
    private final String roleRef;

    public BotRoleKeeper(long guildId, String roleRef) {
        this.guildId = guildId;
        this.roleRef = roleRef;
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        var guild = event.getGuild();

        if (guild.getIdLong() != guildId) {
            return;
        }

        var role = Roles.find(guild, roleRef);
        if (role == null) {
            log.error("Роль для ботов не найдена: bots.role={}", roleRef);
            return;
        }

        // Проверяем положение роли один раз здесь, а не ловим исключение на каждом боте:
        // Discord не даёт выдавать роли, которые стоят не ниже собственной роли бота.
        if (!guild.getSelfMember().canInteract(role)) {
            log.error("Роль «{}» стоит не ниже роли самого бота, выдать её нельзя. "
                    + "Подними роль бота выше неё в настройках сервера.", role.getName());
            return;
        }

        // Список участников приходит отдельной пачкой по гейтвею, в кэше его ещё нет
        guild.loadMembers().onSuccess(members -> {
            var given = 0;

            for (var member : members) {
                if (member.getUser().isBot() && !member.getRoles().contains(role)) {
                    assign(guild, member, role);
                    given++;
                }
            }

            if (given > 0) {
                log.info("Роль «{}» выдана ботам: {}.", role.getName(), given);
            } else {
                log.info("Роль «{}» уже есть у всех ботов.", role.getName());
            }
        }).onError(error -> log.error("Не удалось получить список участников: {}. "
                + "Включён ли интент SERVER MEMBERS?", error.getMessage()));
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (event.getGuild().getIdLong() != guildId || !event.getMember().getUser().isBot()) {
            return;
        }

        var role = Roles.find(event.getGuild(), roleRef);
        if (role != null && event.getGuild().getSelfMember().canInteract(role)) {
            assign(event.getGuild(), event.getMember(), role);
        }
    }

    private void assign(Guild guild, Member member, Role role) {
        try {
            queueAssign(guild, member, role);
        } catch (RuntimeException e) {
            // Часть проверок JDA делает до отправки запроса и бросает исключение сразу,
            // мимо колбэка ошибки — без этого перехвата падал бы весь обход списка.
            log.error("Не удалось выдать роль «{}» боту «{}»: {}", role.getName(),
                    member.getUser().getName(), e.getMessage());
        }
    }

    private void queueAssign(Guild guild, Member member, Role role) {
        guild.addRoleToMember(member, role).queue(
                ok -> log.info("Боту «{}» выдана роль «{}».", member.getUser().getName(), role.getName()),
                error -> log.error("Не удалось выдать роль «{}» боту «{}»: {}. "
                                + "Скорее всего роль бота ниже выдаваемой или нет права «Управление ролями».",
                        role.getName(), member.getUser().getName(), error.getMessage()));
    }
}
