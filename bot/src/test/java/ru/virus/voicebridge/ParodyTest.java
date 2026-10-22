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
    static final List<Long> BANNED = List.of(66L);

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
                chance, cooldown, minSamples, "", "", "", OWNER, quiet, daily,
                ParodySettings.DEFAULT_MARK, "", BANNED);
    }

    ParodySamples store;
    ParodyState state;
    ParodyNames names;

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

        names = new ParodyNames(dir.resolve("parody-memory/names.json"));
        names.load();
        parody = new Parody(settings, new Telegram("T", "", telegram.url()), llm, store, state, names);

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
        // При 100% молчать нельзя — были ещё попытки, но и они пустые
        assertEquals(4, prompts.size());
    }

    @Test
    void fullChanceDoesNotStaySilentAfterTyping() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        start(settings(100, 0, 1), llm(p -> calls.incrementAndGet() == 1 ? "-" : "ну да"), "образец");

        telegram.say(7, "Петя", "как дела");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "при вероятности 100% бот не должен молчать после «печатает»");
        assertEquals(Parody.MARK + "ну да", sent.get("text"));
    }

    @Test
    void messageDuringThinkingIsAnsweredAfterwards() throws Exception {
        var first = new java.util.concurrent.CountDownLatch(1);
        start(settings(100, 0, 1), llm(p -> {
            if (first.getCount() > 0) {
                try {
                    // Пока он думает над первым, приходят ещё два
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                first.countDown();
                return "первый";
            }

            return "второй";
        }), "образец");

        telegram.say(7, "Петя", "раз");
        Thread.sleep(200);
        telegram.say(7, "Петя", "два");
        telegram.say(8, "Коля", "три");

        var sent = telegram.awaitSent(2, 4000);
        assertNotNull(sent, "сообщения, пришедшие пока ИИ думал, потерялись");
        assertEquals(Parody.MARK + "второй", sent.get("text"));
        // Отложенное — одно, последнее: на пачку отвечает одной репликой
        assertNull(telegram.awaitSent(3, 1200));
        assertTrue(prompts.get(1).contains("три"), prompts.get(1));
    }

    @Test
    void mentionIsAnsweredEvenIfModelFirstStaysSilent() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        start(settings(0, 60, 1), llm(p -> calls.incrementAndGet() == 1 ? "-" : "да тут я"), "образец");

        telegram.say(7, "Петя", "@parody_bot ты где");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "на обращение бот обязан ответить");
        assertEquals(Parody.MARK + "да тут я", sent.get("text"));
        assertTrue(prompts.get(1).contains("Прошлый вариант не подошёл"), prompts.get(1));
    }

    @Test
    void trailingEmojiIsDroppedWhenHeRarelyUsesThem() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ну го катку 😂🔥"), "ну го", "да", "ок", "кто тут");

        telegram.say(7, "Петя", "раз");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent);
        assertEquals(Parody.MARK + "ну го катку", sent.get("text"));
    }

    @Test
    void trailingEmojiIsDroppedEvenWhenHeUsesThem() throws Exception {
        start(settings(100, 0, 1), llm(p -> "го 😂 катку 🔥"), "ахах 😂", "го 🔥", "да 😎", "ок");

        telegram.say(7, "Петя", "раз");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent);
        assertEquals(Parody.MARK + "го 😂 катку", sent.get("text"));
    }

    @Test
    void learnsNicknamesFromHisAddressesAndUsesThem() throws Exception {
        start(settings(0, 0, 1), llm(p -> "ок"), "образец");

        for (var i = 0; i < ParodyNames.SURE; i++) {
            var question = telegram.say(7, "Пётр Иванов", "кто идёт?");
            telegram.say(FakeTelegram.CHAT, TARGET, "Вася", false, "Петька, я иду",
                    FakeTelegram.original(question, 7, "Пётр Иванов", false, "кто идёт?"));
        }

        Thread.sleep(800);
        assertEquals(java.util.List.of("Петька"), names.nicknames(7));

        telegram.say(OWNER, "Хозяин", "/parody_say");
        assertNotNull(telegram.awaitSent(1, 3000));
        // В переписке он теперь сразу подписан прозвищем — отдельного списка нет
        assertTrue(prompts.getLast().contains("Петька: кто идёт?"), prompts.getLast());
        assertTrue(!prompts.getLast().contains("Пётр Иванов"), prompts.getLast());
    }

    @Test
    void nicknameCanBeTaughtByCommand() throws Exception {
        start(settings(0, 0, 1), llm(p -> "ок"), "образец");

        var message = telegram.say(9, "Александр", "привет");
        telegram.say(FakeTelegram.CHAT, OWNER, "Хозяин", false, "/parody_name Санёк",
                FakeTelegram.original(message, 9, "Александр", false, "привет"));

        var answer = telegram.awaitSent(1, 3000);
        assertNotNull(answer);
        assertTrue(answer.get("text").contains("Санёк"), answer.get("text"));
        assertEquals(java.util.List.of("Санёк"), names.nicknames(9));

        // И переживает перезапуск
        var again = new ParodyNames(dir.resolve("parody-memory/names.json"));
        again.load();
        assertEquals(java.util.List.of("Санёк"), again.nicknames(9));
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
        assertTrue(prompts.getLast().contains("отличаться от них"), prompts.getLast());
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
        assertEquals(10, menu.length());
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
    void forwardedIsNotHisWords() throws Exception {
        start(settings(0, 0, 1), llm(p -> ""), "образец");

        telegram.sayForwarded(TARGET, "Вася", "чужая цитата из канала");
        telegram.say(TARGET, "Вася", "а это моё");
        Thread.sleep(800);

        assertEquals(java.util.List.of("образец", "а это моё"), store.all());
    }

    @Test
    void answersQuestionInThreadButNotStatement() throws Exception {
        start(settings(100, 0, 1), llm(p -> "ответ " + System.nanoTime()), "образец");

        var question = telegram.say(7, "Петя", "кто в доту?");
        var first = telegram.awaitSent(1, 3000);
        assertNotNull(first);
        assertEquals(String.valueOf(question), first.get("reply_to_message_id"));
        assertTrue(!prompts.getFirst().contains("ответ на его реплику"),
                "вопрос в чат — не обращение к нему, подсказка не должна врать");

        Thread.sleep(200);
        telegram.say(7, "Петя", "я пошёл");
        var second = telegram.awaitSent(2, 3000);
        assertNotNull(second);
        assertNull(second.get("reply_to_message_id"), "на простую реплику — не цепочкой");
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

    @Test
    void cleanCutsMadeUpLinesOfOthers() {
        assertEquals("го катку", Parody.clean("го катку\nПетя: давай\nВася: ок", "Вася"));
        assertEquals("го", Parody.clean("Вася: го\nКоля (из Discord): я за", "Вася"));
        assertEquals("раз\nдва", Parody.clean("раз\nдва", "Вася"), "свои две строки остаются");
        assertEquals("ну короче вот: всё", Parody.clean("ну короче вот: всё", "Вася"),
                "двоеточие в первой строке — не чужая реплика");
    }

    @Test
    void cleanLimitsLength() {
        var wall = "слово ".repeat(200);
        var cut = Parody.clean(wall, "Вася");

        assertTrue(cut.length() <= Parody.LONGEST_REPLY + 1, String.valueOf(cut.length()));
        assertTrue(cut.endsWith("слово…"), cut.substring(cut.length() - 10));
    }

    @Test
    void copiedSampleIsRejectedAndNewLineSent() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        start(settings(100, 0, 1), llm(p -> calls.incrementAndGet() == 1
                ? "я сегодня опять не выспался короче" : "го лучше вечером катнём"),
                "я сегодня опять не выспался короче", "ахах");

        telegram.say(7, "Петя", "кто в доту");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent);
        assertEquals(Parody.MARK + "го лучше вечером катнём", sent.get("text"));
        assertTrue(prompts.get(1).contains("слово в слово"), prompts.get(1));
    }

    @Test
    void recognisesCopies() {
        var samples = List.of("я сегодня опять не выспался короче", "ахах", "го");
        assertTrue(Parody.copies("Я сегодня опять не выспался, короче!", samples));
        assertTrue(Parody.copies("опять не выспался короче", samples), "кусок старого сообщения");
        assertTrue(Parody.copies("я сегодня опять не выспался короче)", samples));
        assertTrue(!Parody.copies("ахах", samples), "короткие словечки повторять можно");
        assertTrue(!Parody.copies("сегодня как раз выспался, го катку", samples));
    }

    static ParodySettings character(long styleUser) {
        return new ParodySettings(true, "T", "@grp", styleUser, "Дед",
                "anthropic", "KEY", "model", "", "", "", "",
                100, 0, 50, "", "", "", OWNER, "", 0, "", "ворчливый дед, всех зовёт обезьянами", List.of());
    }

    @Test
    void characterTalksWithoutMarkAndWithoutSamples() throws Exception {
        start(character(0), llm(p -> "эх вы обезьяны"));

        telegram.say(7, "Петя", "кто в доту");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "персонажу образцы не нужны");
        assertEquals("эх вы обезьяны", sent.get("text"), "у персонажа пометки нет — он никого не изображает");
        assertTrue(prompts.getFirst().contains("как Дед"), prompts.getFirst());
    }

    @Test
    void characterAnswersStyleUserAndLearnsOnlyForm() throws Exception {
        start(character(TARGET), llm(p -> "ну и чё"), "пишу вот так мелко");

        telegram.say(TARGET, "Лантри", "всем привет");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "для персонажа автор образцов — обычный собеседник");
        assertTrue(prompts.getFirst().contains("пишу вот так мелко"), prompts.getFirst());
        assertTrue(prompts.getFirst().contains("только для манеры письма"), prompts.getFirst());
    }

    @Test
    void characterNeverStartsWithParodyMark() throws Exception {
        assertEquals("ну чё, обезьяны", Parody.clean("пародия: Дед: ну чё, обезьяны", "Дед", ""));
        start(character(0), llm(p -> "пародия: ну чё, обезьяны"));

        telegram.say(7, "Петя", "привет");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent);
        assertEquals("ну чё, обезьяны", sent.get("text"));
    }

    @Test
    void ownerTypesInConsoleAndItGoesToGroup() throws Exception {
        start(character(0), llm(p -> "ну"));

        parody.sayFromConsole("всем привет из консоли");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent);
        assertEquals("всем привет из консоли", sent.get("text"), "у персонажа без пометки — как есть");
        // Группа — числом, если бот уже узнал её, или по @имени, как в настройках
        assertTrue(List.of(String.valueOf(FakeTelegram.CHAT), "@grp").contains(sent.get("chat_id")));
    }

    @Test
    void ownerListOfNamesAndJokesReachesPrompt() throws Exception {
        var file = dir.resolve("parody-people.json");
        Files.writeString(file, "{\"people\":[{\"id\":\"7\",\"name\":\"Петруха\",\"jokes\":\"про то, что он вечно опаздывает\"},"
                + "{\"id\":\"8\",\"name\":\"Колян\",\"jokes\":\"про кота\"},"
                + "{\"id\":\"0\",\"name\":\"никто\"}]}");
        start(character(0), llm(p -> "ну"));
        parody.setPeople(new PeopleBook(file));

        telegram.say(7, "Петя", "кто в доту");

        assertNotNull(telegram.awaitSent(1, 3000));
        var prompt = prompts.getFirst();
        assertTrue(prompt.contains("«Петруха»: шутить над ним можно так: про то, что он вечно опаздывает"), prompt);
        assertTrue(prompt.contains("Петруха: кто в доту"), "в переписке — так, как велел хозяин: " + prompt);
        assertTrue(!prompt.contains("никто"), "строки без ID пропускаются");
        assertTrue(!prompt.contains("Колян"), "кто не писал — того в подсказке нет, чтобы ИИ его не звал");
    }

    @Test
    void idCommandShowsAuthorOfRepliedMessage() throws Exception {
        start(settings(0, 0, 1), llm(p -> ""), "образец");

        telegram.say(FakeTelegram.CHAT, 7, "Посторонний", false, "/id",
                FakeTelegram.original(99, 555, "Лантри", false, "привет всем"));
        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent, "ID узнать может каждый, не только хозяин");
        assertTrue(sent.get("text").contains("ID Лантри: 555"), sent.get("text"));

        telegram.say(7, "Посторонний", "/id");
        var own = telegram.awaitSent(2, 3000);
        assertNotNull(own);
        assertTrue(own.get("text").contains("Твой ID: 7"), own.get("text"));
    }

    @Test
    void addressToSomeoneWhoIsNotTalkingIsRewritten() throws Exception {
        var file = dir.resolve("parody-people.json");
        Files.writeString(file, "{\"people\":[{\"id\":\"555\",\"name\":\"Санёк\",\"jokes\":\"\"}]}");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        start(character(0), llm(p -> calls.incrementAndGet() == 1 ? "Санёк, ты где пропал" : "ну и чё, обезьяны"));
        parody.setPeople(new PeopleBook(file));

        telegram.say(7, "Петя", "кто в доту");

        var sent = telegram.awaitSent(1, 3000);
        assertNotNull(sent);
        assertEquals("ну и чё, обезьяны", sent.get("text"));
        assertTrue(prompts.get(1).contains("в разговоре не участвует"), prompts.get(1));
    }

    @Test
    void samplesWithAddressesAreNotShownAndOldChatIsForgotten() throws Exception {
        assertTrue(Parody.namesSomeone("санёк, го в доту"));
        assertTrue(Parody.namesSomeone("ты где @vasya"));
        assertTrue(!Parody.namesSomeone("ну, я хз"));
        assertTrue(!Parody.namesSomeone("го в доту"));
        assertEquals("Петя", Parody.lastAuthor("Коля: ку\nПетя (в ответ Коля на «ку»): привет"));

        var now = new java.util.concurrent.atomic.AtomicLong(LocalDateTime.of(2026, 1, 1, 12, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now.get()); }
        };
        var replies = new java.util.concurrent.atomic.AtomicInteger();
        start(character(TARGET), llm(p -> "ну " + replies.incrementAndGet()), clock, "санёк, го в доту", "пишу вот так");

        telegram.say(8, "Колян", "давно это было");
        assertNotNull(telegram.awaitSent(1, 3000));
        now.addAndGet(2 * 60 * 60_000L);
        telegram.say(7, "Петя", "кто тут");

        assertNotNull(telegram.awaitSent(2, 3000));
        var prompt = prompts.getLast();
        assertTrue(!prompt.contains("санёк"), "образец с обращением не показывается");
        assertTrue(!prompt.contains("Колян"), "сообщение двухчасовой давности — уже не разговор");
        assertTrue(prompt.contains("Самое новое сообщение написал «Петя»"), prompt);
    }

    @Test
    void sameTemplateWithAnotherNameIsNotNew() {
        var recent = List.of("Содиум, ты че, как вайбкодер, опять завис", "го катку");
        assertNotNull(Parody.samey("•FroSt•, ты че, как свинья, нажрался", recent));
        assertNotNull(Parody.samey("Рома Смотрун, ты че, как сторож", recent));
        assertNotNull(Parody.samey("ты че, как медуза", recent));
        assertNull(Parody.samey("кто опять сервер уронил, признавайтесь", recent));
        assertNull(Parody.samey("ну", recent));
    }

    @Test
    void ownRepliesSurviveRestart() throws Exception {
        var file = java.nio.file.Files.createTempDirectory("said").resolve("state.json");
        var state = new ParodyState(file);
        state.load();

        for (var i = 0; i < 12; i++) {
            state.addSaid("реплика " + i, 10);
        }

        var again = new ParodyState(file);
        again.load();
        assertEquals(10, again.said().size());
        assertEquals("реплика 11", again.said().getLast());
    }

    @Test
    void banCommandWorksForAnyone() throws Exception {
        start(settings(0, 0, 1), llm(p -> "-"), "образец");

        // Простой участник — не админ и не хозяин
        telegram.say(7, "Петя", Parody.BAN);
        var done = telegram.awaitSent(1, 3000);
        assertNotNull(done);
        assertTrue(done.get("text").contains("Забанен"), done.toString());
        assertEquals("66", telegram.bans.getFirst().get("user_id"));
        assertEquals(String.valueOf(FakeTelegram.CHAT), telegram.bans.getFirst().get("chat_id"));

        // И сам тот, кого банят
        telegram.say(66, "Пинхед", Parody.BAN);
        assertNotNull(telegram.awaitSent(2, 3000));
        assertEquals(2, telegram.bans.size());
    }

    @Test
    void peopleAreLabelledByTheirCallNames() throws Exception {
        var book = dir.resolve("parody-people.json");
        Files.writeString(book, "{\"people\":[{\"id\":\"7\",\"name\":\"Санёк\",\"jokes\":\"\"}]}");
        var replies = new java.util.concurrent.atomic.AtomicInteger();
        start(settings(100, 0, 1), llm(p -> "ну " + replies.incrementAndGet()), "образец");
        parody.setPeople(new PeopleBook(book));

        telegram.say(8, "•FroSt•", "всем ку");
        assertNotNull(telegram.awaitSent(1, 3000));
        Thread.sleep(300);
        telegram.say(7, "Рома Смотрун", "кто в доту");
        assertNotNull(telegram.awaitSent(2, 3000));

        var prompt = prompts.getLast();
        assertTrue(prompt.contains("Санёк: кто в доту"), prompt);
        assertTrue(!prompt.contains("Рома Смотрун"), "ник из Telegram заменён тем, как звать: " + prompt);
        assertTrue(prompt.contains("Самое новое сообщение написал «Санёк»"), prompt);

        // «•FroSt•, …» в ответ Саньку — путаница, переписываем
        assertEquals("•FroSt•", parody.addressesOther("•FroSt•, го", "•FroSt•: всем ку\nСанёк: кто в доту"));
        assertNull(parody.addressesOther("Санёк, го", "•FroSt•: всем ку\nСанёк: кто в доту"));
        assertNull(parody.addressesOther("ну, го", "•FroSt•: всем ку\nСанёк: кто в доту"));
    }

    @Test
    void addressToSomeoneElseIsNotTheirNickname() {
        var names = new ParodyNames(dir.resolve("names-test.json"));
        names.seen(7, "Саня");
        names.seen(8, "Рома Смотрун");

        // Отвечает Сане, но зовёт Рому — Саню «Ромой» не записываем
        for (var i = 0; i < 5; i++) {
            names.learn(7, "Рома, глянь");
        }

        assertTrue(names.nicknames(7).isEmpty(), names.nicknames(7).toString());
        assertNull(names.callName(7));

        for (var i = 0; i < ParodyNames.SURE; i++) {
            names.learn(7, "Санёк, глянь");
        }

        assertEquals("Санёк", names.callName(7));
    }
}
