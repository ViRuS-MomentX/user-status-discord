package ru.virus.voicebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;

/**
 * Выход через HTTP-прокси для тех, кому сервис напрямую недоступен.
 *
 * <p>Общий для Telegram и для ИИ: оба бывают закрыты ровно там, где живёт бот.
 */
final class HttpProxy {

    private static final Logger log = LoggerFactory.getLogger(HttpProxy.class);

    private HttpProxy() {
    }

    /**
     * Настраивает выход через прокси.
     *
     * <p>Строка вида «хост:порт» или «логин:пароль@хост:порт»: у платных прокси вход
     * почти всегда по паролю, и без него настройка была бы бесполезной ровно там,
     * где она нужнее всего.
     *
     * @param who кого заворачиваем — для записи в лог
     * @param key какая настройка задала прокси — чтобы ошибка указывала, где править
     */
    static void apply(HttpClient.Builder builder, String proxy, String who, String key) {
        var address = proxy;
        String user = null;
        String password = null;

        var at = proxy.lastIndexOf('@');

        if (at > 0) {
            var credentials = proxy.substring(0, at);
            address = proxy.substring(at + 1);

            var colon = credentials.indexOf(':');

            if (colon > 0) {
                user = credentials.substring(0, colon);
                password = credentials.substring(colon + 1);
            }
        }

        var colon = address.lastIndexOf(':');

        if (colon <= 0) {
            log.error("{} должен быть «хост:порт» или "
                    + "«логин:пароль@хост:порт», а не «{}». Иду напрямую.", key, proxy);
            return;
        }

        int port;

        try {
            port = Integer.parseInt(address.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            log.error("В {} порт должен быть числом: «{}». Иду напрямую.", key, proxy);
            return;
        }

        builder.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(address.substring(0, colon).trim(), port)));

        if (user == null) {
            log.info("{} через прокси {}.", who, address);
            return;
        }

        // Java по умолчанию не даёт слать пароль прокси при защищённом соединении.
        // Запрет разумный для чужих сетей, но здесь прокси выбрал сам хозяин бота
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        var login = user;
        var secret = password == null ? "" : password;

        builder.authenticator(new java.net.Authenticator() {
            @Override
            protected java.net.PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.PROXY
                        ? new java.net.PasswordAuthentication(login, secret.toCharArray())
                        : null;
            }
        });

        log.info("{} через прокси {} под именем {}.", who, address, login);
    }
}
