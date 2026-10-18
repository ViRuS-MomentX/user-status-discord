package ru.virus.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProcessTest {

    @TempDir
    Path home;

    final List<ManagedProcess> started = new ArrayList<>();

    @AfterEach
    void down() {
        started.forEach(process -> {
            process.stop();
            process.awaitStop(5000);
        });
    }

    ManagedProcess child(boolean restart, boolean supervised, String... args) {
        var command = new ArrayList<>(List.of("{java}", "-Dstdout.encoding=UTF-8", "-cp",
                System.getProperty("java.class.path"), EchoChild.class.getName()));
        command.addAll(List.of(args));
        var spec = new ProcessSpec("t", "Тест", command, "", false, restart, supervised);
        var java = ProcessHandle.current().info().command().orElse("java");
        var process = new ManagedProcess(spec, home, java, home.resolve("logs/t.log"));
        started.add(process);
        return process;
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 15_000;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("не дождался: " + what);
            }
            Thread.sleep(50);
        }
    }

    static boolean logged(ManagedProcess process, String text) {
        return process.log().since(0).stream().anyMatch(line -> line.text().contains(text));
    }

    @Test
    void runsShowsOutputAndStopsGracefully() throws Exception {
        var process = child(true, true);
        process.start();

        await(() -> logged(process, "привет из процесса ✓"), "вывода процесса");
        assertEquals(ManagedProcess.State.RUNNING, process.state());

        process.stop();
        await(() -> process.state() == ManagedProcess.State.STOPPED, "остановки");
        assertTrue(logged(process, "ухожу по-хорошему"), "должен был выйти по «stop», а не обрывом");
    }

    @Test
    void crashIsRestarted() throws Exception {
        var process = child(true, false, "crash");
        process.start();

        await(() -> process.state() == ManagedProcess.State.WAITING, "ожидания перезапуска");
        assertTrue(logged(process, "Перезапущу через 5 с"));
        await(() -> (int) process.toJson().get("restarts") == 1, "перезапуска");
    }

    @Test
    void configErrorIsNotRestarted() throws Exception {
        var process = child(true, false, "config");
        process.start();

        await(() -> process.state() == ManagedProcess.State.FAILED, "отказа");
        assertTrue(String.valueOf(process.toJson().get("note")).contains("настройки"));
        Thread.sleep(6000);
        assertEquals(0, process.toJson().get("restarts"), "ошибку настройки перезапуском не вылечить");
    }

    @Test
    void stopDuringRestartWaitCancelsIt() throws Exception {
        var process = child(true, false, "crash");
        process.start();
        await(() -> process.state() == ManagedProcess.State.WAITING, "ожидания перезапуска");

        process.stop();
        Thread.sleep(6000);
        assertEquals(ManagedProcess.State.STOPPED, process.state());
        assertEquals(0, process.toJson().get("restarts"));
    }

    @Test
    void restartStartsAgain() throws Exception {
        var process = child(true, true);
        process.start();
        await(() -> logged(process, "привет"), "первого запуска");
        var firstPid = process.toJson().get("pid");

        process.restart();
        await(() -> process.state() == ManagedProcess.State.RUNNING
                && process.toJson().get("pid") != null
                && !process.toJson().get("pid").equals(firstPid), "второго запуска");
    }

    @Test
    void missingProgramIsReported() throws Exception {
        var spec = new ProcessSpec("x", "Нет такого", List.of("/нет/такой/программы"), "", false, true, false);
        var process = new ManagedProcess(spec, home, "java", null);
        process.start();

        assertEquals(ManagedProcess.State.FAILED, process.state());
        assertTrue(String.valueOf(process.toJson().get("note")).startsWith("не запустился"));
    }

    @Test
    void placeholdersResolve() {
        var spec = new ProcessSpec("x", "X", List.of("{java}", "-jar", "{home}/bot.jar"), "", false, true, false);
        var process = new ManagedProcess(spec, home, "/opt/java/bin/java", null);

        assertEquals(List.of("/opt/java/bin/java", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dstdin.encoding=UTF-8", "-jar", home + "/bot.jar"), process.resolvedCommand());
        assertEquals(home, process.workdir());
    }
}
