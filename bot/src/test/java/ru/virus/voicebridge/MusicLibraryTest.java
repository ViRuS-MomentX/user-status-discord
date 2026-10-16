package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicLibraryTest {

    @TempDir
    Path dir;

    LibraryTrack track(String file, String title, String artist) throws Exception {
        Files.writeString(dir.resolve(file), "звук");
        return new LibraryTrack(file, title, artist, 180, "https://x/" + file, "кто-то");
    }

    @Test
    void findsByLooseWordsAndBuildsPlaylist() throws Exception {
        var library = new MusicLibrary(dir);
        library.add(track("1.opus", "Группа крови (1988)", "Кино"));
        library.add(track("2.opus", "Звезда по имени Солнце", "Кино"));
        library.add(track("3.opus", "Smells Like Teen Spirit", "Nirvana"));

        var found = library.search("группа крови", 5);
        assertEquals("Группа крови (1988)", found.getFirst().title());

        var playlist = library.playlistFor("группа крови", 2);
        assertEquals(2, playlist.size());
        assertEquals("Кино", playlist.get(1).artist(), "сначала доливается тот же исполнитель");

        assertTrue(library.playlistFor("ничего похожего", 5).isEmpty());
    }

    @Test
    void indexSurvivesRestartAndDropsMissingFiles() throws Exception {
        var library = new MusicLibrary(dir);
        library.add(track("1.opus", "Раз", "А"));
        library.add(track("2.opus", "Два", "Б"));
        Files.delete(dir.resolve("2.opus"));

        var again = new MusicLibrary(dir);
        again.load();

        assertEquals(1, again.size());
        assertTrue(again.has("https://x/1.opus"));
        assertFalse(Files.exists(dir.resolve("library.json.tmp")), "временный файл остался");
    }

    @Test
    void brokenIndexIsNotOverwritten() throws Exception {
        Files.writeString(dir.resolve("library.json"), "[{\"file\": \"1.opus\",");

        var library = new MusicLibrary(dir);
        library.load();
        library.add(track("new.opus", "Новая", "В"));

        assertEquals("[{\"file\": \"1.opus\",", Files.readString(dir.resolve("library.json.broken")));
        assertEquals(1, library.size());
    }
}
