package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

/**
 * Поиск роли по тому, что написано в настройках.
 */
public final class Roles {

    /**
     * Ищет роль по ID или, если ссылка не число, по точному названию.
     *
     * <p>Основной способ — ID: названия ролей на сервере набиты длинными тире и
     * вертикальными чертами, руками такое не наберёшь без опечатки. Но если кому-то
     * удобнее название, пусть работает и оно.
     *
     * @return найденная роль или <code>null</code>
     */
    public static Role find(Guild guild, String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }

        if (ref.chars().allMatch(Character::isDigit)) {
            return guild.getRoleById(ref);
        }

        var byName = guild.getRolesByName(ref, false);
        return byName.isEmpty() ? null : byName.get(0);
    }

    private Roles() {
    }
}
