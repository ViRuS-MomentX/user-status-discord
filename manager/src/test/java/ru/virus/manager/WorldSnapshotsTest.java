package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSnapshotsTest {

    @Test
    void snapshotAndRollBack(@TempDir Path server) throws Exception {
        assertNull(WorldSnapshots.take(server, "empty"), "миров нет — снимать нечего");

        Files.createDirectories(server.resolve("world/region"));
        Files.writeString(server.resolve("world/level.dat"), "старый мир");
        Files.writeString(server.resolve("world/session.lock"), "lock");
        Files.createDirectories(server.resolve("world_nether"));
        Files.writeString(server.resolve("world_nether/level.dat"), "старый ад");

        var zip = WorldSnapshots.take(server, "plugin");
        assertTrue(Files.isRegularFile(zip));
        assertEquals(1, WorldSnapshots.list(server).size());
        assertTrue(WorldSnapshots.recent(server, 60_000));
        assertTrue(WorldSnapshots.title(zip).endsWith("· перед заменой плагина"), WorldSnapshots.title(zip));

        // Мир испортился — откатываем
        Files.writeString(server.resolve("world/level.dat"), "сломанный мир");
        Files.writeString(server.resolve("world/junk.dat"), "мусор");
        WorldSnapshots.restore(server, zip);

        assertEquals("старый мир", Files.readString(server.resolve("world/level.dat")));
        assertEquals("старый ад", Files.readString(server.resolve("world_nether/level.dat")));
        assertFalse(Files.exists(server.resolve("world/junk.dat")), "мир заменён целиком, а не дописан");
        assertFalse(Files.exists(server.resolve("world/session.lock")), "замок сессии в снимок не попадает");
    }

    @Test
    void keepsOnlyLastFiveAndStaysInsideServer(@TempDir Path server) throws Exception {
        Files.createDirectories(server.resolve("world"));
        Files.writeString(server.resolve("world/level.dat"), "мир");
        var dir = Files.createDirectories(WorldSnapshots.folder(server));

        for (var i = 1; i <= 6; i++) {
            Files.writeString(dir.resolve("2026-01-0" + i + "_10-00-00-old.zip"), "x");
        }

        WorldSnapshots.take(server, "fresh");
        assertEquals(WorldSnapshots.KEEP, WorldSnapshots.list(server).size());
        assertTrue(WorldSnapshots.list(server).getFirst().getFileName().toString().endsWith("-fresh.zip"));

        // Архив с «../» не пишет за пределы папки сервера
        var evil = server.resolve("evil.zip");

        try (var out = new ZipOutputStream(Files.newOutputStream(evil))) {
            out.putNextEntry(new ZipEntry("../outside.txt"));
            out.write("нет".getBytes());
            out.closeEntry();
        }

        WorldSnapshots.restore(server, evil);
        assertFalse(Files.exists(server.getParent().resolve("outside.txt")));
    }
}
