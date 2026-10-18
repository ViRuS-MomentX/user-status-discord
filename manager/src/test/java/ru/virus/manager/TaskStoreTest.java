package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStoreTest {

    @TempDir
    Path dir;

    Path file() {
        return dir.resolve("tasks.json");
    }

    @Test
    void savesByItselfAndReadsBack() throws Exception {
        var store = new TaskStore(file());
        assertNull(store.load());

        var task = store.add();
        store.edit(task, t -> {
            t.title = "Плагин: «команда /ранг»";
            t.notes = "1. сделать\n[x] придумать\n[ ] проверить";
            t.status = TaskStore.Status.DOING;
            t.priority = TaskStore.Priority.HIGH;
            t.project = "voice-bridge";
        });

        // Кнопки «Сохранить» нет — файл должен появиться сам
        var deadline = System.currentTimeMillis() + 5000;

        while (!Files.isRegularFile(file()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertTrue(Files.isRegularFile(file()), "автосохранение не сработало");

        var again = new TaskStore(file());
        again.load();
        var read = again.all().getFirst();
        assertEquals(1, read.number);
        assertEquals("Плагин: «команда /ранг»", read.title);
        assertEquals(TaskStore.Status.DOING, read.status);
        assertEquals(TaskStore.Priority.HIGH, read.priority);
        assertEquals("voice-bridge", read.project);
        assertEquals(task.created, read.created);
        assertEquals(1, read.checklist()[0]);
        assertEquals(2, read.checklist()[1]);
    }

    @Test
    void numbersAreNeverReused() throws Exception {
        var store = new TaskStore(file());
        store.add();
        var second = store.add();
        store.remove(second);
        store.saveNow();

        var again = new TaskStore(file());
        again.load();
        assertEquals(3, again.add().number, "номер удалённой задачи не должен достаться новой");
    }

    @Test
    void newTaskGoesOnTopAndCanBeMoved() throws Exception {
        var store = new TaskStore(file());
        var first = store.add();
        var second = store.add();
        assertEquals(List.of(second, first), store.all());

        assertTrue(store.move(second, 1));
        assertEquals(List.of(first, second), store.all());
        assertTrue(!store.move(second, 1), "ниже последней некуда");
    }

    @Test
    void brokenFileFallsBackToBackupAndIsKept() throws Exception {
        var store = new TaskStore(file());
        store.edit(store.add(), t -> t.title = "первая");
        store.saveNow();
        store.edit(store.add(), t -> t.title = "вторая");
        store.saveNow();

        // Файл испорчен — читаем прошлую версию
        Files.writeString(file(), "{обрывок");
        var again = new TaskStore(file());
        assertEquals(file().resolveSibling("tasks.json.bak"), again.load());
        assertEquals("первая", again.all().getFirst().title);

        // Испорчены оба — ошибка, а при записи старое откладывается, не затирается
        Files.writeString(file().resolveSibling("tasks.json.bak"), "мусор");
        var broken = new TaskStore(file());
        assertThrows(java.io.IOException.class, broken::load);
        broken.add();
        broken.saveNow();
        assertEquals("{обрывок", Files.readString(file().resolveSibling("tasks.json.broken")));
    }

    @Test
    void searchFindsByNumberAndText() {
        var task = new TaskStore.Task(12, 0);
        task.title = "Музыка в войсе";
        assertTrue(task.matches("#12"));
        assertTrue(task.matches("12"));
        assertTrue(!task.matches("#1"));
        assertTrue(task.matches("музыка"));
    }

    @Test
    void listContinues() {
        assertEquals("2. ", TasksPage.continueList("1. купить"));
        assertEquals("  10) ", TasksPage.continueList("  9) пункт"));
        assertEquals("- ", TasksPage.continueList("- пункт"));
        assertEquals("[ ] ", TasksPage.continueList("[x] готово"));
        assertEquals("", TasksPage.continueList("3. "), "пустой пункт заканчивает список");
        assertNull(TasksPage.continueList("просто текст"));
    }

    @Test
    void checkToggles() {
        assertEquals("[x] сделать", TasksPage.toggleCheck("[ ] сделать"));
        assertEquals("[ ] сделать", TasksPage.toggleCheck("[x] сделать"));
        assertEquals("[ ] сделать", TasksPage.toggleCheck("- сделать"));
        assertEquals("  [ ] сделать", TasksPage.toggleCheck("  сделать"));
    }

    @Test
    void textExport() {
        var task = new TaskStore.Task(5, 0);
        task.title = "Идея";
        task.notes = "первое\n\nвторое";
        task.priority = TaskStore.Priority.HIGH;
        assertEquals("#5 [Идея] [!] Идея\n    первое\n    второе\n", TaskStore.asText(List.of(task)));
    }

    @Test
    void photosAreKeptWithTaskAndGoneWithIt() throws Exception {
        var store = new TaskStore(file());
        var task = store.add();
        var picture = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var source = dir.resolve("shot.jpg");
        javax.imageio.ImageIO.write(picture, "jpg", source.toFile());

        var fromFile = store.attach(task, source);
        var fromClipboard = store.attach(task, picture);
        store.saveNow();

        assertTrue(fromFile.endsWith(".jpg"));
        assertTrue(fromClipboard.endsWith(".png"));
        assertTrue(Files.isRegularFile(store.image(task, fromFile)));

        var again = new TaskStore(file());
        again.load();
        assertEquals(List.of(fromFile, fromClipboard), again.all().getFirst().images);

        store.detach(task, fromFile);
        assertFalse(Files.exists(store.image(task, fromFile)));

        store.remove(task);
        assertFalse(Files.exists(store.imagesOf(task)), "папка фото удалённой задачи стирается");
    }
}
