package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Клиент GigaChat от Сбера.
 *
 * <p>Вход в два шага: ключом авторизации из личного кабинета бот берёт токен,
 * который живёт полчаса, и уже с ним спрашивает модель. Токен держим, пока не
 * истечёт, — за новым на каждую реплику ходить незачем.
 *
 * <p>Серверы Сбера подписаны сертификатом НУЦ Минцифры, которого нет среди тех,
 * кому Java доверяет сама. Его файл подкладывается рядом с настройками, и
 * доверие к нему распространяется только на этого клиента, а не на весь бот.
 */
public final class GigaChatLlm implements Llm {

    private static final Logger log = LoggerFactory.getLogger(GigaChatLlm.class);

    private static final String OAUTH = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final String CHAT = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions";

    /** Берём новый токен чуть заранее, чтобы он не истёк на полпути. */
    private static final long TOKEN_MARGIN_MS = 60_000;

    private final HttpClient http;
    private final String key;
    private final String scope;
    private final String model;
    private final String oauthUrl;
    private final String chatUrl;

    private String token = "";
    private long tokenExpires;

    /**
     * @param key ключ авторизации (Authorization key) из личного кабинета
     * @param scope GIGACHAT_API_PERS для физлиц, GIGACHAT_API_B2B или _CORP для компаний
     * @param url свой адрес чата вместо официального; пусто — официальный
     * @param cert файл сертификата НУЦ Минцифры; если его нет — только обычное доверие
     */
    public GigaChatLlm(String key, String scope, String model, String url, String proxy, Path cert) {
        this(key, scope, model, OAUTH, url.isBlank() ? CHAT : url.trim(), proxy, cert);
    }

    GigaChatLlm(String key, String scope, String model, String oauthUrl, String chatUrl,
                String proxy, Path cert) {
        // Ключ копируют из кабинета вместе с «Basic » — не страшно, снимем сами
        var trimmed = key.trim();
        this.key = trimmed.regionMatches(true, 0, "Basic ", 0, 6) ? trimmed.substring(6).trim() : trimmed;
        this.scope = scope;
        this.model = model;
        this.oauthUrl = oauthUrl;
        this.chatUrl = chatUrl;

        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));

        if (cert != null) {
            if (Files.isRegularFile(cert)) {
                try {
                    builder.sslContext(trusting(cert));
                    log.info("Сертификат для GigaChat: {}", cert.getFileName());
                } catch (IOException | GeneralSecurityException e) {
                    log.error("Не прочитался сертификат {}: {}", cert, e.getMessage());
                }
            } else {
                log.warn("Нет файла сертификата {}. Без него Java не доверит серверам Сбера — "
                        + "скачай «Корневой сертификат» на https://www.gosuslugi.ru/crt и положи "
                        + "рядом с config.properties.", cert.getFileName());
            }
        }

        if (!proxy.isBlank()) {
            HttpProxy.apply(builder, proxy.trim(), "GigaChat", "parody.llm.proxy");
        }

        this.http = builder.build();
    }

    @Override
    public String complete(String system, String prompt, int maxTokens) throws IOException {
        var body = DataObject.empty()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("messages", DataArray.empty()
                        .add(DataObject.empty().put("role", "system").put("content", system))
                        .add(DataObject.empty().put("role", "user").put("content", prompt)));

        var answer = send(HttpRequest.newBuilder(URI.create(chatUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());

        if (answer.statusCode() == 401) {
            // Токен отозвали раньше срока — в следующий раз возьмём новый
            synchronized (this) {
                token = "";
            }
        }

        if (answer.statusCode() != 200) {
            throw new IOException(explain(answer.statusCode()) + said(answer.body()));
        }

        var choices = DataObject.fromJson(answer.body()).getArray("choices");

        if (choices.isEmpty()) {
            return "";
        }

        var choice = choices.getObject(0);

        // Сработал фильтр: вместо реплики пришла заготовка вроде «Не люблю менять
        // тему разговора». От лица участника она звучала бы нелепо — молчим
        if ("blacklist".equals(choice.getString("finish_reason", ""))) {
            log.debug("GigaChat ответил заготовкой фильтра — пропускаю.");
            return "";
        }

        return choice.getObject("message").getString("content", "").trim();
    }

    /** Токен доступа; берёт новый, когда старый на исходе. */
    private synchronized String token() throws IOException {
        if (!token.isEmpty() && System.currentTimeMillis() < tokenExpires - TOKEN_MARGIN_MS) {
            return token;
        }

        var answer = send(HttpRequest.newBuilder(URI.create(oauthUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + key)
                // Сбер требует номер запроса, любой уникальный
                .header("RqUID", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)))
                .build());

        if (answer.statusCode() != 200) {
            throw new IOException("вход в GigaChat не удался: "
                    + (answer.statusCode() == 400 || answer.statusCode() == 401
                        ? "проверь parody.llm.key и parody.llm.scope"
                        : "ответ " + answer.statusCode())
                    + said(answer.body()));
        }

        var json = DataObject.fromJson(answer.body());
        token = json.getString("access_token", "");
        // Срок приходит в миллисекундах от начала эпохи; если нет — считаем полчаса
        tokenExpires = json.getLong("expires_at", System.currentTimeMillis() + 30 * 60_000L);

        if (token.isEmpty()) {
            throw new IOException("GigaChat не выдал токен");
        }

        return token;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (javax.net.ssl.SSLHandshakeException e) {
            throw new IOException("сервер Сбера не прошёл проверку сертификата — нужен файл "
                    + "из parody.llm.cert (https://www.gosuslugi.ru/crt)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("запрос прерван");
        }
    }

    /**
     * Доверие к обычным центрам сертификации плюс к сертификату из файла.
     *
     * <p>Обычные остаются: иначе через прокси или свой адрес в parody.llm.url
     * перестало бы открываться всё остальное.
     */
    private static SSLContext trusting(Path cert) throws IOException, GeneralSecurityException {
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);

        try (InputStream in = Files.newInputStream(cert)) {
            var index = 0;

            // В файле может лежать и корневой, и промежуточный — берём все
            for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("gigachat-" + index++, certificate);
            }
        }

        var extra = trustManager(store);
        var system = trustManager(null);

        var combined = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    extra.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                var issuers = new ArrayList<X509Certificate>(java.util.List.of(system.getAcceptedIssuers()));
                issuers.addAll(java.util.List.of(extra.getAcceptedIssuers()));
                return issuers.toArray(new X509Certificate[0]);
            }
        };

        var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { combined }, null);
        return context;
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);

        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }

        throw new GeneralSecurityException("нет X509TrustManager");
    }

    private static String said(String body) {
        try {
            var json = DataObject.fromJson(body);
            var message = json.getString("message", "");
            return message.isBlank() ? "" : " (" + message + ")";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String explain(int status) {
        return switch (status) {
            case 401 -> "токен не принят — проверь parody.llm.key";
            case 402 -> "кончился лимит токенов — загляни в личный кабинет GigaChat";
            case 404 -> "нет такой модели — проверь parody.llm.model";
            case 429 -> "слишком часто";
            default -> "ответ " + status;
        };
    }
}
