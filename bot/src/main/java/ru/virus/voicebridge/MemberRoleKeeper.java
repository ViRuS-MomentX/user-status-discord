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
import java.util.List;
import java.util.Set;

/**
 * Раздаёт роли по принадлежности: ботам — общую, людям — их ступень с лестницы рангов.
 *
 * <p>Оба дела живут в одном классе нарочно. Каждое требует полного списка участников, а
 * запрашивать его дважды нельзя: два одновременных запроса не уживаются, и второй
 * отваливается по таймауту. Здесь список берётся один раз и обходится для обоих дел.
 */
public final class MemberRoleKeeper extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MemberRoleKeeper.class);

    private final long guildId;

    /** Роль для ботов. Пустая строка — ботам ничего не выдаём. */
    private final String botRoleRef;

    /** Лестница рангов. <code>null</code> — ранги выключены. */
    private final RankStore store;
    private final RankLadder ladder;

    public MemberRoleKeeper(long guildId, String botRoleRef, RankStore store, RankLadder ladder) {
        this.guildId = guildId;
        this.botRoleRef = botRoleRef;
        this.store = store;
        this.ladder = ladder;
    }

    /** Есть ли вообще чем заняться. */
    public boolean hasWork() {
        return !botRoleRef.isEmpty() || ladder != null;
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        var guild = event.getGuild();

        if (guild.getIdLong() != guildId) {
            return;
        }

        var botRole = botRoleRef.isEmpty() ? null : Roles.find(guild, botRoleRef);

        if (!botRoleRef.isEmpty() && botRole == null) {
            log.error("Роль для ботов не найдена: bots.role={}", botRoleRef);
        }

        var ladderRoles = collectLadderRoles(guild);

        guild.loadMembers().onSuccess(members -> {
            var bots = 0;
            var humans = 0;

            for (var member : members) {
                if (member.getUser().isBot()) {
                    if (botRole != null && !member.getRoles().contains(botRole) && assign(guild, member, botRole)) {
                        bots++;
                    }
                } else if (ensureRank(guild, member, ladderRoles)) {
                    humans++;
                }
            }

            if (botRole != null) {
                log.info("Роль «{}» выдана ботам: {}.", botRole.getName(), bots);
            }
            if (!ladderRoles.isEmpty()) {
                log.info("Роль ранга выдана участникам: {}.", humans);
            }
        }).onError(error -> log.error("Не удалось получить список участников: {}",
                error.toString()));
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        var guild = event.getGuild();

        if (guild.getIdLong() != guildId) {
            return;
        }

        if (event.getMember().getUser().isBot()) {
            if (!botRoleRef.isEmpty()) {
                var role = Roles.find(guild, botRoleRef);
                if (role != null) {
                    assign(guild, event.getMember(), role);
                }
            }
            return;
        }

        ensureRank(guild, event.getMember(), collectLadderRoles(guild));
    }

    /**
     * Выдаёт участнику роль его ранга, если ни одной ступени у него сейчас нет.
     *
     * <p>Имеющиеся ступени не трогаются: ранг могли выдать вручную или он мог остаться
     * от прежней версии бота, и сверка с балансами откатила бы людей назад.
     *
     * @return <code>true</code>, если роль была выдана
     */
    private boolean ensureRank(Guild guild, Member member, Set<Role> ladderRoles) {
        if (ladder == null || ladderRoles.isEmpty()) {
            return false;
        }

        for (var role : member.getRoles()) {
            if (ladderRoles.contains(role)) {
                return false;
            }
        }

        // Обычно это нулевой ранг и стартовая роль, но если человек уходил с сервера
        // и вернулся, его купленный ранг сохранился в балансах — вернём заслуженное.
        var role = ladder.resolveCurrentRole(guild, store.get(member.getId()).rank);

        return role != null && assign(guild, member, role);
    }

    /**
     * Выдаёт роль, если это вообще возможно.
     */
    private boolean assign(Guild guild, Member member, Role role) {
        // Discord не даёт выдавать роли не ниже собственной роли бота, и проверяет это
        // броском исключения прямо из вызова, мимо обработчика ошибок
        if (!guild.getSelfMember().canInteract(role)) {
            return false;
        }

        try {
            guild.addRoleToMember(member, role).queue(
                    ok -> { },
                    error -> log.error("Не удалось выдать роль «{}» участнику «{}»: {}",
                            role.getName(), member.getUser().getName(), error.getMessage()));
            return true;
        } catch (RuntimeException e) {
            log.error("Не удалось выдать роль «{}» участнику «{}»: {}",
                    role.getName(), member.getUser().getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Собирает все роли лестницы, включая стартовую: по этому набору видно, есть ли у
     * человека хоть какой-то ранг.
     */
    private Set<Role> collectLadderRoles(Guild guild) {
        if (ladder == null) {
            return Set.of();
        }

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
