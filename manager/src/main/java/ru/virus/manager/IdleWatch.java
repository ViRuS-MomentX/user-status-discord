package ru.virus.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Сколько времени нет ввода — клавиатура, мышь, геймпад, — как его считает
 * сама Windows для заставки (GetLastInputInfo). Так делают iCUE и SignalRGB:
 * по одной мыши игра на клавиатуре выглядела бы простоем.
 *
 * <p>Маленькая программа на PowerShell раз в 5 секунд печатает число секунд
 * без ввода. Работает, только пока гашение в простое кто-то просит.
 */
final class IdleWatch {

    private static final long IDLE_MS = 30_000;

    /** Секунд без ввода; −1 — неизвестно (тогда решает запасной способ — по мыши). */
    private static volatile long seconds = -1;
    private static volatile long wanted;
    private static Thread thread;

    private IdleWatch() {
    }

    static long seconds() {
        demand();
        return seconds;
    }

    private static synchronized void demand() {
        wanted = System.currentTimeMillis();

        if (thread != null && thread.isAlive() || !SystemInfo.isWindows()) {
            return;
        }

        thread = new Thread(IdleWatch::run, "virus-idle-watch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run() {
        while (System.currentTimeMillis() - wanted < IDLE_MS) {
            Process process = null;

            try {
                var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-EncodedCommand", encoded).redirectErrorStream(true).start();
                process.getOutputStream().close();

                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while ((line = in.readLine()) != null && System.currentTimeMillis() - wanted < IDLE_MS) {
                        var parsed = parse(line);

                        if (parsed >= 0) {
                            seconds = parsed;
                        }
                    }
                }
            } catch (Exception e) {
                seconds = -1;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }

        seconds = -1;
    }

    static long parse(String line) {
        try {
            return Long.parseLong(line.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Печатает секунды без ввода раз в 5 секунд; через 10 минут выходит — его перезапустят. */
    static String script() {
        return "$ErrorActionPreference='Stop';"
                + "Add-Type -TypeDefinition @'\n"
                + "using System; using System.Runtime.InteropServices; using System.Threading;\n"
                + "public static class VirusIdle {\n"
                + "  [StructLayout(LayoutKind.Sequential)] struct LastInput { public uint Size; public uint Time; }\n"
                + "  [DllImport(\"user32.dll\")] static extern bool GetLastInputInfo(ref LastInput info);\n"
                + "  public static void Run() {\n"
                + "    var until = DateTime.Now.AddMinutes(10);\n"
                + "    while (DateTime.Now < until) {\n"
                + "      var info = new LastInput(); info.Size = (uint) Marshal.SizeOf(info);\n"
                + "      if (GetLastInputInfo(ref info)) {\n"
                + "        Console.WriteLine(((uint) Environment.TickCount - info.Time) / 1000);\n"
                + "      }\n"
                + "      Thread.Sleep(5000);\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "'@\n"
                + "[VirusIdle]::Run()";
    }
}
