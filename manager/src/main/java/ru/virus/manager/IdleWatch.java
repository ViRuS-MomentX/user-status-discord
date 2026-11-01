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
    /** Экран заблокирован (Win+L, заставка с паролем) — по последнему ответу помощника. */
    private static volatile boolean locked;
    private static volatile long wanted;
    private static Thread thread;

    private IdleWatch() {
    }

    static long seconds() {
        demand();
        return seconds;
    }

    /** Заблокирован ли экран; неизвестно — false, чтобы зря не гасить. */
    static boolean locked() {
        demand();
        return locked;
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
                            locked = parseLocked(line);
                        }
                    }
                }
            } catch (Exception e) {
                seconds = -1;
                locked = false;
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
        locked = false;
    }

    static long parse(String line) {
        try {
            return Long.parseLong(line.strip().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Строка помощника «12 locked» — экран заблокирован. */
    static boolean parseLocked(String line) {
        return line.strip().endsWith(" locked");
    }

    /**
     * Печатает секунды без ввода и, если экран заблокирован, слово «locked».
     * Блокировку узнаём, как это делают программы подсветки: на экране
     * блокировки входной рабочий стол — чужой, переключиться на него нельзя.
     * Раз в 5 секунд; через 10 минут выходит — его перезапустят.
     */
    static String script() {
        return "$ErrorActionPreference='Stop';"
                + "Add-Type -TypeDefinition @'\n"
                + "using System; using System.Runtime.InteropServices; using System.Threading;\n"
                + "public static class VirusIdle {\n"
                + "  [StructLayout(LayoutKind.Sequential)] struct LastInput { public uint Size; public uint Time; }\n"
                + "  [DllImport(\"user32.dll\")] static extern bool GetLastInputInfo(ref LastInput info);\n"
                + "  [DllImport(\"user32.dll\")] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);\n"
                + "  [DllImport(\"user32.dll\")] static extern bool SwitchDesktop(IntPtr desktop);\n"
                + "  [DllImport(\"user32.dll\")] static extern bool CloseDesktop(IntPtr desktop);\n"
                + "  static bool Locked() {\n"
                + "    var desktop = OpenInputDesktop(0, false, 0x0100);\n"
                + "    if (desktop == IntPtr.Zero) return true;\n"
                + "    var mine = SwitchDesktop(desktop); CloseDesktop(desktop); return !mine;\n"
                + "  }\n"
                + "  public static void Run() {\n"
                + "    var until = DateTime.Now.AddMinutes(10);\n"
                + "    while (DateTime.Now < until) {\n"
                + "      var info = new LastInput(); info.Size = (uint) Marshal.SizeOf(info);\n"
                + "      if (GetLastInputInfo(ref info)) {\n"
                + "        Console.WriteLine(((uint) Environment.TickCount - info.Time) / 1000 + (Locked() ? \" locked\" : \"\"));\n"
                + "      }\n"
                + "      Thread.Sleep(5000);\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "'@\n"
                + "[VirusIdle]::Run()";
    }
}
