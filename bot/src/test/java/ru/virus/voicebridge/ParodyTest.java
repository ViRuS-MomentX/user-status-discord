package ru.virus.voicebridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParodyTest {

    static final long TARGET = 42;

    @TempDir
    Path dir;

    FakeTelegram telegram;
    Parody parody;
    final List<String> prompts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws Exception {
        telegram = new FakeTelegram();
    }

    @AfterEach
    void down() {
        if (parody != null) {
            parody.stop();
        }
        telegram.close();
    }

    /** Модель, которая отвечает заданным и запоминает, о чём её спросили. */
    Llm llm(Function<String, String> answer) {
        return (system, prompt, maxTokens) -> {
            prompts.add(prompt);
            return answer.apply(prompt);
        };
    }

    static ParodySettings settings(int chance, int cooldown, int minSamples) {
        return new ParodySettings(true, "T", "@grp", TARGET, "Вася",
                "anthropic", "KEY", "model", "", "", "", "",
                chance, cooldown, minSamples, "", "", "");
    }

    void start(ParodySettings settings, Llm llm, String... samples) throws Exception {
        var store = new ParodySamples(dir.resolve("parody-samples.json"));

        for (var sample : samples) {
            store.add(sample);
        }

        parody = new Parody(settings, new Telegram("T", "", telegram.url()), llm, store);
        parody.start();
        // Дать боту узнать группу, прежде чем в неё писать
        Thread.sleep(300);
    }

    @Test
    void repliesWithMarkAndLearnsFromTarget() throws Exception {
        start(settings(100, 0, 2), llm(p -> "Вася: «го катку)))»"), "всем ку");

        telegram.say(TARGET, "Вася", "я лег спать короч");
        telegram.say(7, "Петя", "кто в доту?");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "бот должен был ответить");
        assertEquals(Parody.MARK + "го катку)))", sent.get("text"));
        assertEquals(String.valueOf(FakeTelegram.CHAT), sent.get("chat_id"));

        // Его собственная реплика стала образцом, а чужая — нет
        var prompt = prompts.getFirst();
        assertTrue(prompt.contains("- я лег спать короч"), prompt);
        assertTrue(!prompt.contains("- кто в доту?"), prompt);
    }

    @Test
    void ignoresTargetBotsAndOtherChats() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ответ"), "образец");

        telegram.say(TARGET, "Вася", "сам себе не отвечаю");
        telegram.say(FakeTelegram.CHAT, 8, "Бот", true, "я бот", null);
        telegram.say(999, 7, "Петя", false, "это личка", null);

        assertNull(telegram.awaitSent(1, 1500), "бот ответил тому, кому не должен");
    }

    @Test
    void waitsForEnoughSamples() throws Exception {
        start(settings(100, 0, 5), llm(p -> "ответ"), "один");

        telegram.say(7, "Петя", "привет");

        assertNull(telegram.awaitSent(1, 1500));
        assertTrue(prompts.isEmpty(), "ИИ не должен был вызываться");
    }

    @Test
    void cooldownHoldsSecondLine() throws Exception {
        start(settings(100, 10, 1), llm(p -> "ответ"), "образец");

        telegram.say(7, "Петя", "раз");
        assertNotNull(telegram.awaitSent(1, 3000));

        telegram.say(7, "Петя", "два");
        assertNull(telegram.awaitSent(2, 1500), "пауза после реплики не сработала");
    }

    @Test
    void silentModelSendsNothing() throws Exception {
        start(settings(100, 0, 1), llm(p -> "-"), "образец");

        telegram.say(7, "Петя", "привет");

        assertNull(telegram.awaitSent(1, 1500));
        assertEquals(1, prompts.size());
    }

    @Test
    void cleanStripsWhatModelAdds() {
        assertEquals("йо", Parody.clean("пародия: йо", "Вася"));
        assertEquals("йо", Parody.clean("Пародия: йо", "Вася"));
        assertEquals("йо", Parody.clean("Вася: йо", "Вася"));
        assertEquals("йо", Parody.clean("«йо»", "Вася"));
        assertEquals("йо", Parody.clean("\"йо\"", "Вася"));
        assertEquals("", Parody.clean("-", "Вася"));
        assertEquals("", Parody.clean("  —  ", "Вася"));
        assertEquals("ну «да» и что", Parody.clean("ну «да» и что", "Вася"));
    }
}
