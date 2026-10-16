package ru.virus.voicebridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParodyTest {

    static final long TARGET = 42;
    static final long OWNER = 1;

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
            // Проверку связи при запуске не записываем: она не реплика
            if (prompt.startsWith("Проверка связи")) {
                return "ок";
            }

            prompts.add(prompt);
            return answer.apply(prompt);
        };
    }

    static ParodySettings settings(int chance, int cooldown, int minSamples) {
        return settings(chance, cooldown, minSamples, "", 0);
    }

    static ParodySettings settings(int chance, int cooldown, int minSamples, String quiet, int daily) {
        return new ParodySettings(true, "T", "@grp", TARGET, "Вася",
                "anthropic", "KEY", "model", "", "", "", "",
                chance, cooldown, minSamples, "", "", "", OWNER, quiet, daily);
    }

    ParodySamples store;
    ParodyState state;

    void start(ParodySettings settings, Llm llm, String... samples) throws Exception {
        start(settings, llm, null, samples);
    }

    void start(ParodySettings settings, Llm llm, Clock clock, String... samples) throws Exception {
        store = new ParodySamples(dir.resolve("parody-samples.json"));

        for (var sample : samples) {
            store.add(sample);
        }

        state = new ParodyState(dir.resolve("parody-state.json"));
        state.load();

        parody = new Parody(settings, new Telegram("T", "", telegram.url()), llm, store, state);

        if (clock != null) {
            parody.setClock(clock);
        }

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
    void answersReplyToItselfDespiteZeroChance() throws Exception {
        start(settings(0, 60, 1), llm(p -> p.contains("ответ на его реплику") ? "сам такой" : "?"),
                "образец");

        // Шанс нулевой: сам он не встрянет, но на ответ себе ответит
        telegram.say(7, "Петя", "раз");
        assertNull(telegram.awaitSent(1, 800));

        parody.post("скажу", 0);
        assertNotNull(telegram.awaitSent(1, 3000));
        var mine = telegram.sentIds.getFirst();

        var question = telegram.say(FakeTelegram.CHAT, 7, "Петя", false, "чё сказал?",
                FakeTelegram.original(mine, 777, "Вася", true, "пародия: скажу"));

        var reply = telegram.awaitSent(2, 3000);
        assertNotNull(reply, "на ответ себе бот должен ответить");
        assertEquals(Parody.MARK + "сам такой", reply.get("text"));
        // Отвечает именно на вопрос Пети, цепочкой
        assertEquals(String.valueOf(question), reply.get("reply_to_message_id"));
    }

    @Test
    void answersMentionAndShowsTyping() throws Exception {
        start(settings(0, 60, 1), llm(p -> "тут я"), "образец");

        telegram.say(7, "Петя", "@Parody_Bot ты где");

        assertNotNull(telegram.awaitSent(1, 3000), "на упоминание бот должен ответить");
        assertEquals("typing", telegram.actions.getFirst().get("action"));
    }

    @Test
    void promptShowsWhatWasAnswered() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ок"), "образец");

        // Вопрос задаёт сам участник: на его реплики бот не встревает, и повод
        // ответить даст только Петя
        var question = telegram.say(TARGET, "Вася", "кто идёт в кино?");
        telegram.say(FakeTelegram.CHAT, 7, "Петя", false, "я",
                FakeTelegram.original(question, TARGET, "Вася", false, "кто идёт в кино?"));

        assertNotNull(telegram.awaitSent(1, 3000));
        var prompt = prompts.getFirst();
        assertTrue(prompt.contains("Петя (в ответ Вася на «кто идёт в кино?»): я"), prompt);
    }

    @Test
    void doesNotRepeatItself() throws Exception {
        start(settings(100, 0, 1), llm(p -> "Го катку!!"), "образец");

        telegram.say(7, "Петя", "раз");
        assertNotNull(telegram.awaitSent(1, 3000));

        // Та же реплика с другой пунктуацией — повтор
        Thread.sleep(200);
        telegram.say(7, "Петя", "два");
        assertNull(telegram.awaitSent(2, 1500), "бот повторился");
        assertTrue(prompts.getLast().contains("не повторяй"), prompts.getLast());
    }

    static Clock at(int hour) {
        var zone = ZoneId.of("Europe/Moscow");
        return Clock.fixed(LocalDateTime.of(2026, 10, 16, hour, 30).atZone(zone).toInstant(), zone);
    }

    @Test
    void targetCanSwitchOffAndItSticks() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ответ"), "образец");

        telegram.say(TARGET, "Вася", "/parody_off");
        var answer = telegram.awaitSent(1, 3000);
        assertNotNull(answer);
        assertTrue(answer.get("text").startsWith("⚙️"), answer.get("text"));
        assertTrue(!answer.get("text").startsWith(Parody.MARK));

        telegram.say(7, "Петя", "эй");
        telegram.say(TARGET, "Вася", "это не должно сохраниться");
        assertNull(telegram.awaitSent(2, 1500), "выключенный бот заговорил");
        assertTrue(!store.all().contains("это не должно сохраниться"), "выключенный бот копит речь");

        // После перезапуска — всё ещё выключен
        var reloaded = new ParodyState(dir.resolve("parody-state.json"));
        reloaded.load();
        assertTrue(reloaded.isPaused());
    }

    @Test
    void ownerTurnsBackOnFromPrivateChat() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ответ"), "образец");
        state.setPaused(true);

        telegram.say(OWNER, OWNER, "Хозяин", false, "/parody_on@parody_bot", null);
        var answer = telegram.awaitSent(1, 3000);
        assertNotNull(answer);
        assertEquals(String.valueOf(OWNER), answer.get("chat_id"), "ответ должен прийти в личку");
        assertTrue(!state.isPaused());
    }

    @Test
    void strangersCannotCommand() throws Exception {
        start(settings(0, 0, 1), llm(p -> "ответ"), "образец");

        telegram.say(7, "Петя", "/parody_forget");

        var answer = telegram.awaitSent(1, 3000);
        assertNotNull(answer);
        assertTrue(answer.get("text").contains("только"), answer.get("text"));
        assertEquals(1, store.size(), "посторонний стёр образцы");
    }

    @Test
    void commandForAnotherBotIsIgnored() throws Exception {
        start(settings(0, 0, 1), llm(p -> "ответ"), "образец");

        telegram.say(TARGET, "Вася", "/parody_off@other_bot");

        assertNull(telegram.awaitSent(1, 1500));
        assertTrue(!state.isPaused());
    }

    @Test
    void targetCanForgetEverything() throws Exception {
        start(settings(0, 0, 1), llm(p -> "ответ"), "раз", "два");

        telegram.say(TARGET, "Вася", "/parody_forget");

        assertNotNull(telegram.awaitSent(1, 3000));
        assertEquals(0, store.size());
        assertEquals("[]", Files.readString(dir.resolve("parody-samples.json")));
    }

    @Test
    void sayForcesLineEvenAtZeroChance() throws Exception {
        start(settings(0, 60, 1), llm(p -> "по заказу"), "образец");

        telegram.say(OWNER, "Хозяин", "/parody_say");

        var line = telegram.awaitSent(1, 3000);
        assertNotNull(line);
        assertEquals(Parody.MARK + "по заказу", line.get("text"));
        assertEquals(String.valueOf(FakeTelegram.CHAT), line.get("chat_id"));
    }

    @Test
    void tryGoesOnlyToPrivateChat() throws Exception {
        start(settings(0, 60, 1), llm(p -> "проба"), "образец");

        telegram.say(OWNER, OWNER, "Хозяин", false, "/parody_try", null);

        var line = telegram.awaitSent(1, 3000);
        assertNotNull(line);
        assertEquals(String.valueOf(OWNER), line.get("chat_id"));
        assertEquals(Parody.MARK + "проба", line.get("text"));
        assertEquals(0, state.sentToday(java.time.LocalDate.now()), "проба не должна идти в счёт");
    }

    @Test
    void tryInGroupIsRefused() throws Exception {
        start(settings(0, 60, 1), llm(p -> "проба"), "образец");

        telegram.say(OWNER, "Хозяин", "/parody_try");

        var line = telegram.awaitSent(1, 3000);
        assertNotNull(line);
        assertTrue(line.get("text").contains("личку"), line.get("text"));
        assertTrue(prompts.isEmpty());
    }

    @Test
    void promptCarriesStyleWhenEnoughSamples() throws Exception {
        var many = new String[12];
        java.util.Arrays.fill(many, 0, 6, "ну го)))");
        java.util.Arrays.fill(many, 6, 12, "ахах да");
        start(settings(100, 0, 1), llm(p -> "ок"), many);

        telegram.say(7, "Петя", "привет");

        assertNotNull(telegram.awaitSent(1, 3000));
        assertTrue(prompts.getFirst().contains("манера в цифрах"), prompts.getFirst());
    }

    @Test
    void statusTellsAboutBrokenModel() throws Exception {
        start(settings(0, 0, 1), (system, prompt, max) -> {
            throw new java.io.IOException("ключ не принят");
        }, "образец");

        telegram.say(OWNER, OWNER, "Хозяин", false, "/parody_status", null);

        var status = telegram.awaitSent(1, 3000);
        assertNotNull(status);
        assertTrue(status.get("text").contains("ИИ: ошибка: ключ не принят"), status.get("text"));
    }

    @Test
    void registersCommandMenu() throws Exception {
        start(settings(0, 0, 1), llm(p -> ""), "образец");

        var menu = net.dv8tion.jda.api.utils.data.DataArray.fromJson(telegram.commands);
        assertEquals("parody_say", menu.getObject(0).getString("command"));
        assertEquals(6, menu.length());
    }

    @Test
    void quietHoursKeepSilence() throws Exception {
        start(settings(100, 0, 1, "23-8", 0), llm(p -> "ответ"), at(3), "образец");

        telegram.say(7, "Петя", "не спится");
        assertNull(telegram.awaitSent(1, 1500), "бот заговорил ночью");
    }

    @Test
    void quietHoursLetDaytimeThrough() throws Exception {
        start(settings(100, 0, 1, "23-8", 0), llm(p -> "ответ"), at(14), "образец");

        telegram.say(7, "Петя", "день");
        assertNotNull(telegram.awaitSent(1, 3000));
    }

    @Test
    void dailyCapStopsAfterLimit() throws Exception {
        start(settings(100, 0, 1, "", 1), llm(p -> "ответ " + System.nanoTime()), "образец");

        telegram.say(7, "Петя", "раз");
        assertNotNull(telegram.awaitSent(1, 3000));

        Thread.sleep(200);
        telegram.say(7, "Петя", "два");
        assertNull(telegram.awaitSent(2, 1500), "дневной предел не сработал");
    }

    @Test
    void quietRangeAcrossMidnight() {
        var settings = settings(0, 0, 1, "23-8", 0);
        assertTrue(settings.isQuiet(23));
        assertTrue(settings.isQuiet(0));
        assertTrue(settings.isQuiet(7));
        assertTrue(!settings.isQuiet(8));
        assertTrue(!settings.isQuiet(22));

        var day = settings(0, 0, 1, "13-15", 0);
        assertTrue(day.isQuiet(14));
        assertTrue(!day.isQuiet(15));
        assertTrue(!settings(0, 0, 1, "", 0).isQuiet(3));
    }

    @Test
    void discordPeopleAreHeardButAnsweredInTelegram() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ответ"), "образец");

        parody.hearDiscord("Коля", "кто в войс?");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "на человека из Discord бот должен реагировать");
        assertEquals(String.valueOf(FakeTelegram.CHAT), sent.get("chat_id"));
        assertTrue(prompts.getFirst().contains("Коля (из Discord): кто в войс?"), prompts.getFirst());
    }

    @Test
    void discordCannotCommand() throws Exception {
        start(settings(0, 0, 1), llm(p -> "ответ"), "образец");

        parody.hearDiscord("Коля", "/parody_off");

        assertNull(telegram.awaitSent(1, 1000));
        assertTrue(!state.isPaused());
    }

    @Test
    void photoWithoutCaptionShowsInContext() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ответ"), "образец");

        telegram.sayPhoto(8, "Маша");
        Thread.sleep(300);
        telegram.say(7, "Петя", "ахах смотри");

        assertNotNull(telegram.awaitSent(1, 3000));
        assertTrue(prompts.getFirst().contains("Маша: [фото]"), prompts.getFirst());
    }

    @Test
    void failingModelIsGivenRest() throws Exception {
        start(settings(100, 0, 1), (system, prompt, max) -> {
            prompts.add(prompt);
            throw new java.io.IOException("ключ не принят");
        }, "образец");

        // Проверка при запуске уже упала — и бот выжидает, не спрашивая снова
        telegram.say(7, "Петя", "раз");
        Thread.sleep(800);
        telegram.say(7, "Петя", "два");
        Thread.sleep(800);

        assertEquals(1, prompts.size(), "после сбоя ИИ надо подождать, а не спрашивать снова");
        assertTrue(telegram.sent.isEmpty());
    }

    @Test
    void examplesMixFreshAndOldWithoutRepeats() throws Exception {
        start(settings(0, 0, 1), llm(p -> ""));

        var history = new java.util.ArrayList<String>();
        for (var i = 0; i < 300; i++) {
            history.add("старое " + i);
            history.add("ок");
        }
        history.add("самое свежее");

        var picked = parody.examples(history);

        assertEquals(80, picked.size());
        assertEquals(picked.size(), new java.util.HashSet<>(picked).size(), "повторы остались");
        assertTrue(picked.contains("самое свежее"));
        assertTrue(picked.contains("старое 299"), "свежие должны попадать всегда");
        assertTrue(picked.stream().anyMatch(x -> x.matches("старое ([0-9]|[1-9][0-9]|1[0-9][0-9])")),
                "должны попадать и давние");
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
