package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReporterTest {

    static final String TG = "1234567890:AAHabcdefghijklmnopqrstuvwxyz0123456789";
    /** Собран из частей, чтобы сканер секретов не принял выдуманный токен за настоящий. */
    static final String DISCORD = "MTIzNDU2Nzg5MDEy" + "MzQ1Njc4" + "." + "GaBcDe" + "." + "abcdefghijklmnopqrstuvwxyz123";
    static final String HOOK = "https://discord.com/api/webhooks/123456789/SeCrEtPaRt_abc-123";

    @TempDir
    Path home;

    @Test
    void reportHidesSecretsAndPrivateMessages() throws Exception {
        Files.writeString(home.resolve("config.properties"), """
                bot.token=%s
                bridge.discord.webhook=%s
                parody.llm.key=supersecretkey
                parody.llm.proxy=user:pass@1.2.3.4:8080
                parody.chance=100
                """.formatted(DISCORD, HOOK));
        Files.writeString(home.resolve("parody-samples.json"), "[\"личное сообщение\"]");
        Files.writeString(home.resolve("parody-state.json"), "{\"paused\":false}");
        Files.createDirectories(home.resolve("logs"));
        Files.writeString(home.resolve("logs/parody.log"), "ошибка с токеном " + TG + " и https://x.workers.dev/bot" + TG + "/getMe и " + HOOK + " и " + DISCORD);

        var supervisor = new Supervisor(home, "java");
        supervisor.load();
        var zip = Reporter.build(home, supervisor, home.resolve("config.properties"));

        var entries = new HashMap<String, String>();

        try (var file = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            file.stream().forEach(entry -> {
                try (var in = file.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        var all = String.join("\n", entries.values());
        assertFalse(all.contains("supersecretkey"), all);
        assertFalse(all.contains("SeCrEtPaRt"), all);
        assertFalse(all.contains(TG), all);
        assertFalse(all.contains("GaBcDe"), all);
        assertFalse(all.contains(":pass@"), all);
        assertFalse(all.contains("личное сообщение"), "переписка в отчёт не идёт");
        assertTrue(entries.get("config.properties").contains("parody.chance=100"));
        assertTrue(entries.get("config.properties").contains("***задан, 14 знаков***"));
        assertTrue(entries.containsKey("logs/parody.log"));
        assertTrue(entries.containsKey("parody-state.json"));
        assertTrue(entries.get("файлы-бота.txt").contains("parody-samples.json"), "список файлов без содержимого");
    }
}
