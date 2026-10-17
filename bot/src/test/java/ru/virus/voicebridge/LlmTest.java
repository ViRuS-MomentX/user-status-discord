package ru.virus.voicebridge;

import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmTest {

    record Call(String path, String authorization, String body) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    HttpServer server;

    /** Сервер, который на каждый путь отвечает тем, что вернёт функция: «код тело». */
    String serve(Function<String, String> reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            var auth = exchange.getRequestHeaders().getFirst("Authorization");

            if (auth == null) {
                auth = exchange.getRequestHeaders().getFirst("x-api-key");
            }

            calls.add(new Call(path, auth, body));
            var answer = reply.apply(path);
            var space = answer.indexOf(' ');
            var bytes = answer.substring(space + 1).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(answer.substring(0, space)), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    static String giga(String content, String finish) {
        return "200 {\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"},\"finish_reason\":\"" + finish + "\"}]}";
    }

    GigaChatLlm gigaChat(String url) {
        return new GigaChatLlm("Basic KEY", "GIGACHAT_API_PERS", "GigaChat",
                url + "/oauth", url + "/chat", "", null);
    }

    @Test
    void gigaChatReusesToken() throws Exception {
        var url = serve(path -> path.equals("/oauth")
                ? "200 {\"access_token\":\"TOK\",\"expires_at\":" + (System.currentTimeMillis() + 1_800_000) + "}"
                : giga(" го ", "stop"));
        var llm = gigaChat(url);

        assertEquals("го", llm.complete("s", "p", 10));
        assertEquals("го", llm.complete("s", "p", 10));

        assertEquals(3, calls.size(), "токен должен браться один раз");
        assertEquals("Basic KEY", calls.get(0).authorization(), "«Basic » не должно удваиваться");
        assertEquals("scope=GIGACHAT_API_PERS", calls.get(0).body());
        assertEquals("Bearer TOK", calls.get(1).authorization());

        var request = DataObject.fromJson(calls.get(1).body());
        assertEquals("system", request.getArray("messages").getObject(0).getString("role"));
    }

    @Test
    void gigaChatRenewsExpiredToken() throws Exception {
        // Истёк сразу — каждый запрос за новым
        var url = serve(path -> path.equals("/oauth")
                ? "200 {\"access_token\":\"TOK\",\"expires_at\":" + System.currentTimeMillis() + "}"
                : giga("да", "stop"));
        var llm = gigaChat(url);

        llm.complete("s", "p", 10);
        llm.complete("s", "p", 10);

        assertEquals(2, calls.stream().filter(c -> c.path().equals("/oauth")).count());
    }

    @Test
    void gigaChatBlacklistIsSilence() throws Exception {
        var url = serve(path -> path.equals("/oauth")
                ? "200 {\"access_token\":\"TOK\",\"expires_at\":" + (System.currentTimeMillis() + 1_800_000) + "}"
                : giga("Не люблю менять тему разговора", "blacklist"));

        assertEquals("", gigaChat(url).complete("s", "p", 10));
    }

    @Test
    void gigaChatOutOfTokensIsExplained() throws Exception {
        var url = serve(path -> path.equals("/oauth")
                ? "200 {\"access_token\":\"TOK\",\"expires_at\":" + (System.currentTimeMillis() + 1_800_000) + "}"
                : "402 {\"status\":402,\"message\":\"Payment Required\"}");

        var error = assertThrows(IOException.class, () -> gigaChat(url).complete("s", "p", 10));
        assertTrue(error.getMessage().contains("лимит"), error.getMessage());
    }

    @Test
    void gigaChatBadKeyIsExplained() throws Exception {
        var url = serve(path -> "401 {\"code\":6,\"message\":\"credentials doesn't match db data\"}");

        var error = assertThrows(IOException.class, () -> gigaChat(url).complete("s", "p", 10));
        assertTrue(error.getMessage().contains("parody.llm.key"), error.getMessage());
    }

    @Test
    void anthropicJoinsTextAndSkipsRefusal() throws Exception {
        var url = serve(path -> "200 {\"content\":[{\"type\":\"text\",\"text\":\"раз \"},"
                + "{\"type\":\"text\",\"text\":\"два\"}],\"stop_reason\":\"end_turn\"}");

        assertEquals("раз два", new AnthropicLlm("K", "m", url + "/v1/messages", "").complete("s", "p", 10));
        assertEquals("K", calls.getFirst().authorization());

        server.stop(0);
        calls.clear();
        var refusing = serve(path -> "200 {\"content\":[{\"type\":\"text\",\"text\":\"не буду\"}],"
                + "\"stop_reason\":\"refusal\"}");

        assertEquals("", new AnthropicLlm("K", "m", refusing + "/v1/messages", "").complete("s", "p", 10));
    }

    @Test
    void ollamaAnswersWithoutKeyAndHidesThinking() throws Exception {
        var url = serve(path -> "200 {\"message\":{\"role\":\"assistant\","
                + "\"content\":\"<think>надо коротко</think>\\nго катку\"},\"done\":true}");

        var text = new OllamaLlm("qwen3:8b", url + "/").complete("будь им", "что скажешь", 50);

        assertEquals("го катку", text);
        var call = calls.getFirst();
        assertEquals("/api/chat", call.path());
        var body = DataObject.fromJson(call.body());
        assertEquals("qwen3:8b", body.getString("model"));
        assertEquals(false, body.getBoolean("stream"));
        assertEquals(50, body.getObject("options").getInt("num_predict"));
        assertEquals("будь им", body.getArray("messages").getObject(0).getString("content"));
    }

    @Test
    void ollamaExplainsMissingModel() throws Exception {
        var url = serve(path -> "404 {\"error\":\"model 'gemma3:12b' not found\"}");

        var error = assertThrows(IOException.class, () -> new OllamaLlm("", url).complete("s", "p", 10));
        assertTrue(error.getMessage().contains("ollama pull gemma3:12b"), error.getMessage());
    }

    @Test
    void ollamaExplainsThatItIsNotRunning() throws Exception {
        // Порт, на котором точно никого: сервер подняли и сразу закрыли
        var url = serve(path -> "200 {}");
        server.stop(0);
        server = null;

        var error = assertThrows(IOException.class, () -> new OllamaLlm("m", url).complete("s", "p", 10));
        assertTrue(error.getMessage().contains("запусти приложение Ollama"), error.getMessage());
    }

    @Test
    void ollamaUrlIsNormalised() {
        assertEquals("http://127.0.0.1:11434/api/chat", OllamaLlm.chatUrl(""));
        assertEquals("http://pc:11434/api/chat", OllamaLlm.chatUrl("http://pc:11434/"));
        assertEquals("http://pc:11434/api/chat", OllamaLlm.chatUrl("http://pc:11434/api/chat"));
    }
}
