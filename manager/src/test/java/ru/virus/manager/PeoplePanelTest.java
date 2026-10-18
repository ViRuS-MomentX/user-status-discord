package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeoplePanelTest {

    @TempDir
    Path dir;

    @Test
    void namesBotHasSeenAreOffered() throws Exception {
        var file = dir.resolve("names.json");
        Files.writeString(file, "{\"5902781043\":{\"name\":\"Мма\",\"heard\":{}},\"bad\":{\"name\":\"x\"}}");

        var seen = PeoplePanel.readSeen(file);

        assertEquals("Мма", seen.get(5902781043L));
        assertEquals(1, seen.size());
    }
}
