package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Клиент Messages API Anthropic — один запрос, больше пародии не нужно.
 *
 * <p>Как и с Telegram, без библиотеки: разбор JSON уже есть в JDA.
 */
public final class AnthropicLlm implements Llm {

    private static final String OFFICIAL = "https://api.anthropic.com/v1/messages";

    private final HttpClient http;
    private final String url;
    private final String key;
    private final String model;

    public AnthropicLlm(String key, String model, String url, String proxy) {
        this.key = key;
        this.model = model;
        this.url = url.isBlank() ? OFFICIAL : url.trim();

        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));

        if (!proxy.isBlank()) {
            HttpProxy.apply(builder, proxy.trim(), "ИИ", "parody.llm.proxy");
        }

        this.http = builder.build();
    }

    @Override
    public String complete(String system, String prompt, int maxTokens) throws IOException {
        var body = DataObject.empty()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", DataArray.empty().add(DataObject.empty()
                        .put("role", "user")
                        .put("content", prompt)));

        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("запрос прерван");
        }

        if (response.statusCode() != 200) {
            throw new IOException(explain(response.statusCode()) + said(response.body()));
        }

        var json = DataObject.fromJson(response.body());

        // Модель отказалась писать — реплики нет, а не «отказ» от лица участника
        if ("refusal".equals(json.getString("stop_reason", ""))) {
            return "";
        }

        var content = json.getArray("content");
        var text = new StringBuilder();

        for (var i = 0; i < content.length(); i++) {
            var part = content.getObject(i);

            if ("text".equals(part.getString("type", ""))) {
                text.append(part.getString("text", ""));
            }
        }

        return text.toString().trim();
    }

    /** Что сказал сервер, если сказал. */
    private static String said(String body) {
        try {
            var error = DataObject.fromJson(body).getObject("error");
            return " (" + error.getString("message", "") + ")";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String explain(int status) {
        return switch (status) {
            case 401 -> "ключ не принят — проверь parody.llm.key";
            case 403 -> "доступ запрещён — из этой страны API недоступен, нужен parody.llm.proxy";
            case 404 -> "нет такой модели или адреса — проверь parody.llm.model и parody.llm.url";
            case 429 -> "слишком часто или кончился баланс";
            case 529 -> "сервер перегружен";
            default -> "ответ " + status;
        };
    }
}
