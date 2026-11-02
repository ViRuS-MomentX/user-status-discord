package ru.virus.manager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Облако Tuya — только чтобы один раз забрать «локальные ключи» ламп из
 * Smart Life (дальше лампы управляются по домашней сети). Нужен бесплатный
 * проект разработчика на iot.tuya.com с привязанным аккаунтом Smart Life —
 * так же делает мастер tinytuya. Подпись запросов — HMAC-SHA256, как в их
 * Cloud.py.
 */
final class TuyaCloud {

    /** Регионы: подпись → адрес. */
    static final Map<String, String> REGIONS = new java.util.LinkedHashMap<>();

    static {
        REGIONS.put("Европа (центр)", "openapi.tuyaeu.com");
        REGIONS.put("Европа (запад)", "openapi-weaz.tuyaeu.com");
        REGIONS.put("Америка (запад)", "openapi.tuyaus.com");
        REGIONS.put("Америка (восток)", "openapi-ueaz.tuyaus.com");
        REGIONS.put("Китай", "openapi.tuyacn.com");
        REGIONS.put("Индия", "openapi.tuyain.com");
        REGIONS.put("Сингапур", "openapi-sg.iotbing.com");
    }

    /** Устройство из облака. */
    record Device(String id, String name, String key, String category, String product, boolean online) {
    }

    private final String host;
    private final String accessId;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private String token;

    TuyaCloud(String host, String accessId, String secret) {
        this.host = host;
        this.accessId = accessId.strip();
        this.secret = secret.strip();
    }

    /** Подпись Tuya: HMAC-SHA256(secret, id [+ token] + t + "GET\n" + sha256("") + "\n\n" + путь) заглавными. */
    static String sign(String secret, String accessId, String token, long t, String path) {
        try {
            var empty = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
            var payload = accessId + (token == null ? "" : token) + t + "GET\n" + empty + "\n\n" + path;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<?, ?> get(String path) throws IOException, InterruptedException {
        var t = System.currentTimeMillis();
        var request = HttpRequest.newBuilder(URI.create("https://" + host + path)).timeout(Duration.ofSeconds(20))
                .header("client_id", accessId).header("sign", sign(secret, accessId, token, t, path))
                .header("t", String.valueOf(t)).header("sign_method", "HMAC-SHA256");

        if (token != null) {
            request.header("access_token", token);
        }

        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (!(Json.parse(response.body()) instanceof Map<?, ?> map)) {
            throw new IOException("облако Tuya ответило непонятно (" + response.statusCode() + ")");
        }

        if (!Boolean.TRUE.equals(map.get("success")) && String.valueOf(map.get("code")).startsWith("2884100")) {
            throw new IOException("в проекте не подключён IoT Core: на iot.tuya.com открой Cloud → Cloud Services → "
                    + "IoT Core → бесплатный Trial, затем в проекте Service API → Go to Authorize → IoT Core");
        }

        if (!Boolean.TRUE.equals(map.get("success"))) {
            throw new IOException("облако Tuya: " + map.get("msg") + " (код " + map.get("code") + ")");
        }

        return map;
    }

    /** Все устройства, привязанные к проекту (через аккаунт Smart Life), с их локальными ключами. */
    List<Device> devices() throws IOException, InterruptedException {
        var tokenReply = get("/v1.0/token?grant_type=1");
        token = String.valueOf(((Map<?, ?>) tokenReply.get("result")).get("access_token"));
        var devices = new ArrayList<Device>();
        String lastRow = null;

        for (var page = 0; page < 20; page++) {
            var path = "/v1.0/iot-01/associated-users/devices?" + (lastRow == null ? "" : "last_row_key=" + lastRow + "&")
                    + "size=50";
            var result = (Map<?, ?>) get(path).get("result");

            if (result.get("devices") instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> device) {
                        devices.add(new Device(String.valueOf(device.get("id")), String.valueOf(device.get("name")),
                                String.valueOf(device.get("local_key")), String.valueOf(device.get("category")),
                                String.valueOf(device.get("product_name")),
                                Boolean.TRUE.equals(device.get("online"))));
                    }
                }
            }

            if (!Boolean.TRUE.equals(result.get("has_more")) || result.get("last_row_key") == null) {
                break;
            }

            lastRow = String.valueOf(result.get("last_row_key"));
        }

        return devices;
    }

    /** Лампа ли это: категории освещения Tuya (dj — лампы, dd — ленты, fwd — атмосферные, dc — гирлянды, xdd — потолочные). */
    static boolean light(String category) {
        return List.of("dj", "dd", "fwd", "dc", "xdd", "gyd", "fsd", "tgq", "tgkg", "sxd").contains(category);
    }

    /** По имени: «Smart LED», «Lamp», «Лампа», «Лента» — тоже свет, даже если категория непривычная. */
    static boolean lightName(String name) {
        return name.matches("(?iu).*(led|lamp|light|bulb|strip|ламп|свет|лент|подсвет).*");
    }
}
