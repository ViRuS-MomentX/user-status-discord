package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Модель на своём компьютере через Ollama (https://ollama.com).
 *
 * <p>Ни ключа, ни лимитов, ни блокировок: запрос не выходит за пределы ПК, и
 * переписка участника никуда не уходит. Цена — видеокарта и то, что бот
 * отвечает, только пока запущена Ollama.
 */
public final class OllamaLlm implements Llm {

    public static final String DEFAULT_URL = "http://127.0.0.1:11434";
    public static final String DEFAULT_MODEL = "gemma3:12b";

    /** Рассуждения «думающих» моделей (qwen3, deepseek-r1) — в чат им нельзя. */
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?(</think>|$)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final String chatUrl;
    private final String model;

    /** @param url адрес Ollama; пусто — эта же машина, порт 11434 */
    public OllamaLlm(String model, String url) {
        this.model = model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.chatUrl = chatUrl(url);
    }

    /**
     * Выгружает модель из видеопамяти сразу, не дожидаясь keep_alive:
     * пародию остановили — играм и прочему память нужнее.
     */
    @Override
    public void release() {
        var body = DataObject.empty().put("model", model).put("keep_alive", 0);

        try {
            http.send(HttpRequest.newBuilder(URI.create(chatUrl.replace("/api/chat", "/api/generate")))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            org.slf4j.LoggerFactory.getLogger(OllamaLlm.class)
                    .info("Модель {} выгружена из Ollama — видеопамять свободна.", model);
        } catch (IOException e) {
            // Ollama уже не работает — и выгружать нечего
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String chatUrl(String url) {
        var base = url == null || url.isBlank() ? DEFAULT_URL : url.trim();

        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        return base.endsWith("/api/chat") ? base : base + "/api/chat";
    }

    @Override
    public String complete(String system, String prompt, int maxTokens) throws IOException {
        var body = DataObject.empty()
                .put("model", model)
                .put("stream", false)
                // Держать модель в памяти полчаса: первая загрузка — секунды, а то и минута
                .put("keep_alive", "30m")
                .put("messages", DataArray.empty()
                        .add(DataObject.empty().put("role", "system").put("content", system))
                        .add(DataObject.empty().put("role", "user").put("content", prompt)))
                .put("options", DataObject.empty()
                        .put("num_predict", maxTokens)
                        // По умолчанию окно у Ollama маленькое, и длинная подсказка
                        // с образцами молча обрезалась бы с начала
                        .put("num_ctx", 8192)
                        .put("temperature", 1.0)
                        // Без штрафа маленькие модели гоняют по кругу одну заготовку
                        .put("repeat_penalty", 1.15)
                        .put("repeat_last_n", 256));

        HttpResponse<String> answer;

        try {
            answer = http.send(HttpRequest.newBuilder(URI.create(chatUrl))
                            // Запас на первую загрузку модели в видеопамять
                            .timeout(Duration.ofSeconds(180))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new IOException("Ollama не отвечает на " + chatUrl.replace("/api/chat", "")
                    + " — запусти приложение Ollama (или команду «ollama serve»)");
        } catch (HttpTimeoutException e) {
            throw new IOException("Ollama думала дольше 3 минут — модель " + model
                    + " для этого ПК тяжеловата, возьми поменьше (например gemma3:4b)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("запрос прерван");
        }

        var error = "";

        try {
            error = DataObject.fromJson(answer.body()).getString("error", "");
        } catch (RuntimeException ignored) {
            // не JSON — ниже скажем как есть
        }

        if (answer.statusCode() == 404 || error.contains("not found")) {
            throw new IOException("в Ollama нет модели " + model + " — скачай её командой «ollama pull "
                    + model + "»");
        }

        if (answer.statusCode() != 200) {
            throw new IOException("Ollama ответила " + answer.statusCode()
                    + (error.isBlank() ? "" : ": " + error));
        }

        var text = DataObject.fromJson(answer.body()).getObject("message").getString("content", "");
        return THINKING.matcher(text).replaceAll("").trim();
    }
}
