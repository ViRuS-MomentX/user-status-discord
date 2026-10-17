package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParodyConfigTest {

    @TempDir
    Path dir;

    ParodySettings load(String parody) throws Exception {
        var file = dir.resolve("config.properties");
        Files.writeString(file, "bot.token=x\nguild.id=1\nuser.id=2\n" + parody);
        return BotConfig.load(file).getParody();
    }

    @Test
    void gigaChatDefaults() throws Exception {
        var settings = load("""
                parody.enabled=true
                parody.telegram.token=T
                parody.telegram.chat=@grp
                parody.telegram.user=42
                parody.llm.provider=gigachat
                parody.llm.key=K
                """);

        assertTrue(settings.isGigaChat());
        assertEquals("GigaChat", settings.llmModel());
        assertEquals("GIGACHAT_API_PERS", settings.llmScope());
        assertEquals("russian_trusted_root_ca.cer", settings.llmCert());
        assertEquals(5, settings.chance());
        assertEquals("участник", settings.name());
        assertTrue(settings.isUsable());
    }

    @Test
    void anthropicNeedsModel() throws Exception {
        var settings = load("""
                parody.enabled=true
                parody.telegram.token=T
                parody.telegram.chat=@grp
                parody.telegram.user=42
                parody.llm.provider=anthropic
                parody.llm.key=K
                """);

        assertFalse(settings.isGigaChat());
        assertFalse(settings.isUsable(), "без модели Anthropic не заработает");
    }

    @Test
    void unknownProviderFallsBack() throws Exception {
        assertEquals("anthropic", load("parody.llm.provider=chatgpt\n").llmProvider());
    }

    @Test
    void chanceIsClamped() throws Exception {
        assertEquals(100, load("parody.chance=250\n").chance());
    }

    @Test
    void ownerQuietAndDaily() throws Exception {
        var settings = load("""
                parody.telegram.owner=1
                parody.quiet=23-8
                parody.daily=12
                """);

        assertEquals(1, settings.owner());
        assertEquals("23-8", settings.quiet());
        assertEquals(12, settings.daily());
        assertTrue(settings.mayCommand(1));
        assertFalse(settings.mayCommand(0));
    }

    @Test
    void badQuietIsDropped() throws Exception {
        assertEquals("", load("parody.quiet=ночью\n").quiet());
        assertEquals("", load("parody.quiet=25-8\n").quiet());
        assertEquals(30, load("").daily());
    }

    @Test
    void markCanChangeWordsButNotMeaning() throws Exception {
        assertEquals("пародия: ", load("").mark());
        assertEquals("🤖 Вася-бот: ", load("parody.mark=🤖 Вася-бот:\n").mark());
        assertEquals("[ИИ] ", load("parody.mark=[ИИ]\n").mark());
        assertEquals("пародия: ", load("parody.mark=Вася:\n").mark(), "без признака ИИ — нельзя");
        assertEquals("пародия: ", load("parody.mark=роботы атакуют\n").mark(),
                "«бот» внутри другого слова — не признак");
        assertEquals("пародия: ", load("parody.mark=" + "пародия".repeat(10) + "\n").mark());
    }

    @Test
    void offByDefault() throws Exception {
        assertFalse(load("").enabled());
    }
}
