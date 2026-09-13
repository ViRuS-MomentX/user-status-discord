package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Следит за тем, чтобы у каждого участника была роль с лестницы рангов.
 *
 * <p>Кто ничего не покупал — носит стартовую роль. Роль выдаётся при запуске всем, у
 * кого её нет, и новичкам в момент входа на сервер.
 *
 * <p>Уже имеющиеся ступени не трогаются. Снимать их было бы опаснее, чем полезно:
 * ранг могли выдать вручную или он мог остаться от прежней версии бота, и первый же
 * запуск откатил бы людей назад.
 */
public final class RankRoleKeeper extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RankRoleKeeper.class);

    private final long guildId;
    private final RankStore store;
    private final RankLadder ladder;

    public RankRoleKeeper(long guildId, RankStore store, RankLadder ladder) {
        this.guildId = guildId;
        this.store = store;
        this.ladder = ladder;
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        var guild = event.getGuild();

        if (guild.getIdLong() != guildId) {
            return;
        }

        var ladderRoles = collectLadderRoles(guild);

        if (ladderRoles.isEmpty()) {
            return;
        }

        guild.loadMembers().onSuccess(members -> {
            var given = 0;

            for (var member : members) {
                if (member.getUser().isBot()) {
                    continue;
                }

                if (ensureRank(guild, member, ladderRoles)) {
                    given++;
                }
            }

            log.info("Роль ранга выдана участникам: {}.", given);
        }).onError(error -> log.error("Не удалось получить список участников: {}. "
                + "Включён ли интент SERVER MEMBERS?", error.getMessage()));
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (event.getGuild().getIdLong() != guildId || event.getMember().getUser().isBot()) {
            return;
        }

        ensureRank(event.getGuild(), event.getMember(), collectLadderRoles(event.getGuild()));
    }

    /**
     * Выдаёт участнику роль его ранга, если ни одной ступени у него сейчас нет.
     *
     * @return <code>true</code>, если роль была выдана
     */
    private boolean ensureRank(Guild guild, Member member, Set<Role> ladderRoles) {
        for (var role : member.getRoles()) {
            if (ladderRoles.contains(role)) {
                return false;
            }
        }

        // Обычно это нулевой ранг и стартовая роль, но если человек уходил с сервера
        // и вернулся, его купленный ранг сохранился в балансах — вернём заслуженное.
        var rank = store.get(member.getId()).rank;
        var role = ladder.resolveCurrentRole(guild, rank);

        if (role == null || !guild.getSelfMember().canInteract(role)) {
            return false;
        }

        guild.addRoleToMember(member, role).queue(
                ok -> { },
                error -> log.error("Не удалось выдать роль «{}» участнику «{}»: {}",
                        role.getName(), member.getUser().getName(), error.getMessage()));

        return true;
    }

    /**
     * Собирает все роли лестницы, включая стартовую.
     *
     * <p>По этому набору проверяется, есть ли у человека хоть какой-то ранг. Считаем
     * один раз на обход: искать каждую роль заново для каждого участника — лишняя работа.
     */
    private Set<Role> collectLadderRoles(Guild guild) {
        Set<Role> roles = new HashSet<>();

        var start = ladder.resolveStartRole(guild);
        if (start != null) {
            roles.add(start);
        }

        for (var step : ladder.getSteps()) {
            var role = step.resolve(guild);
            if (role != null) {
                roles.add(role);
            }
        }

        if (roles.isEmpty()) {
            log.error("Ни одной роли лестницы не найдено — выдавать нечего.");
        }

        return roles;
    }
}
