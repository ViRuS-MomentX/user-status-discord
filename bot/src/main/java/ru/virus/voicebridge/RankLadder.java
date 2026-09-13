package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Лестница рангов: какие роли выдаются, в каком порядке и за сколько монет.
 *
 * <p>Ступени задаются в config.properties ключами rank.1, rank.2 и так далее, от самой
 * дешёвой к самой дорогой — это и есть порядок повышения.
 */
public final class RankLadder {

    private static final Logger log = LoggerFactory.getLogger(RankLadder.class);

    /** Одна ступень: роль и её цена. */
    public static final class Step {
        private final String roleRef;
        private final int price;

        Step(String roleRef, int price) {
            this.roleRef = roleRef;
            this.price = price;
        }

        public int getPrice() {
            return price;
        }

        public Role resolve(Guild guild) {
            return Roles.find(guild, roleRef);
        }

        public String getRoleRef() {
            return roleRef;
        }
    }

    private final boolean enabled;
    private final String coinEmoji;
    private final int minutesPerCoin;
    private final String startRoleRef;
    private final List<Step> steps;

    private RankLadder(boolean enabled, String coinEmoji, int minutesPerCoin, String startRoleRef, List<Step> steps) {
        this.enabled = enabled;
        this.coinEmoji = coinEmoji;
        this.minutesPerCoin = minutesPerCoin;
        this.startRoleRef = startRoleRef;
        this.steps = Collections.unmodifiableList(steps);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Эмодзи монеты для вывода в сообщениях. */
    public String getCoinEmoji() {
        return coinEmoji;
    }

    /** Сколько минут в голосовом канале стоит одна монета. */
    public int getMinutesPerCoin() {
        return minutesPerCoin;
    }

    /** Ступени от дешёвой к дорогой. */
    public List<Step> getSteps() {
        return steps;
    }

    /**
     * Роль, которую носят до первого повышения. Может отсутствовать, если её не задали.
     */
    public Role resolveStartRole(Guild guild) {
        return Roles.find(guild, startRoleRef);
    }

    /**
     * Роль, которую участник носит сейчас, по числу сделанных повышений.
     *
     * @param rank ноль — стартовая роль, единица — первая ступень, и так далее
     */
    public Role resolveCurrentRole(Guild guild, int rank) {
        if (rank <= 0) {
            return resolveStartRole(guild);
        }
        return rank <= steps.size() ? steps.get(rank - 1).resolve(guild) : null;
    }

    /**
     * Проверяет, что все указанные роли действительно существуют на сервере, и пишет
     * в лог то, чего не нашлось.
     *
     * <p>Без этого опечатка в ID всплыла бы только в момент, когда кто-то нажмёт кнопку.
     *
     * @return <code>true</code>, если нашлись все роли
     */
    public boolean verify(Guild guild) {
        var ok = true;

        if (!startRoleRef.isEmpty() && resolveStartRole(guild) == null) {
            log.error("Стартовая роль не найдена: rank.start={}", startRoleRef);
            ok = false;
        }

        for (var i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (step.resolve(guild) == null) {
                log.error("Роль ступени не найдена: rank.{}={}", i + 1, step.getRoleRef());
                ok = false;
            }
        }

        if (ok && !steps.isEmpty()) {
            log.info("Ранговая система: {} ступеней, монета за {} минут.", steps.size(), minutesPerCoin);
        }

        return ok;
    }

    /**
     * Собирает лестницу из настроек. Ошибки в отдельной ступени не роняют бота: такая
     * ступень пропускается с руганью в лог.
     */
    public static RankLadder from(Properties props) {
        var enabled = Boolean.parseBoolean(props.getProperty("rank.enabled", "false").trim());

        var coin = props.getProperty("rank.coin", "").trim();
        if (coin.isEmpty()) {
            coin = "🪙"; // 🪙, если своё эмодзи не задали
        }

        var minutes = 30;
        var rawMinutes = props.getProperty("rank.minutes", "").trim();
        if (!rawMinutes.isEmpty()) {
            try {
                minutes = Integer.parseInt(rawMinutes);
            } catch (NumberFormatException e) {
                log.error("rank.minutes должно быть числом, а не «{}». Беру 30.", rawMinutes);
            }
        }
        if (minutes < 1) {
            log.error("rank.minutes не может быть меньше единицы. Беру 30.");
            minutes = 30;
        }

        var steps = new ArrayList<Step>();

        // Ступени идут подряд: первая же дырка в нумерации заканчивает лестницу
        for (var i = 1; ; i++) {
            var raw = props.getProperty("rank." + i, "").trim();
            if (raw.isEmpty()) {
                break;
            }

            var split = raw.lastIndexOf(':');
            if (split < 0) {
                log.error("rank.{} должно быть в виде «роль:цена», а не «{}». Ступень пропущена.", i, raw);
                continue;
            }

            try {
                var price = Integer.parseInt(raw.substring(split + 1).trim());
                steps.add(new Step(raw.substring(0, split).trim(), price));
            } catch (NumberFormatException e) {
                log.error("Цена в rank.{} не число: «{}». Ступень пропущена.", i, raw);
            }
        }

        if (enabled && steps.isEmpty()) {
            // Ровно то состояние, в котором сервер настраивают впервые: ступени ещё неизвестны,
            // потому что их ID как раз и собирают командой «ранг айди». Выключаться тут нельзя,
            // иначе до этой команды не добраться.
            log.warn("Ступени не заданы. Пока работает только команда «ранг айди» "
                    + "— она покажет ID ролей для настройки.");
        }

        return new RankLadder(enabled, coin, minutes, props.getProperty("rank.start", "").trim(), steps);
    }
}
