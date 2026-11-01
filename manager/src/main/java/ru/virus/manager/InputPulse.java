package ru.virus.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Когда последний раз нажимали клавишу или кнопку мыши — для режима «Отклик
 * на ввод», как Type Lighting в iCUE и реактивные эффекты SignalRGB. Без
 * перехвата клавиатуры: Windows сама помнит время последнего ввода
 * (GetLastInputInfo), маленькая программа на PowerShell опрашивает его 30 раз
 * в секунду и печатает строку, когда оно сдвинулось. Работает, только пока
 * режим кто-то рисует.
 */
final class InputPulse {

    private static final long IDLE_MS = 3000;

    /** Моменты последних нажатий, мс (кольцо) — у каждого своя волна. */
    private static final long[] PRESSES = new long[8];
    private static int next;
    private static volatile long wanted;
    private static Thread thread;

    private InputPulse() {
    }

    /** Последние нажатия; просит слушать дальше. */
    static synchronized long[] presses() {
        demand();
        return PRESSES.clone();
    }

    /** Отметить нажатие (из помощника или в тестах). */
    static synchronized void press(long when) {
        // Удержание клавиши шлёт ввод без конца — волна не чаще 12 раз в секунду
        var latest = PRESSES[(next + PRESSES.length - 1) % PRESSES.length];

        if (when - latest < 80) {
            return;
        }

        PRESSES[next] = when;
        next = (next + 1) % PRESSES.length;
    }

    private static synchronized void demand() {
        wanted = System.currentTimeMillis();

        if (thread != null && thread.isAlive() || !SystemInfo.isWindows()) {
            return;
        }

        thread = new Thread(InputPulse::run, "virus-input-pulse");
        thread.setDaemon(true);
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
                        if (line.strip().equals("+")) {
                            press(System.currentTimeMillis());
                        }
                    }
                }
            } catch (Exception e) {
                // нет PowerShell — режим просто тлеет
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Сила волн в точке x (0–1) в момент now: каждое нажатие — кольцо,
     * которое бежит от центра к краям и гаснет за ~0,7 секунды.
     */
    static double wave(long[] presses, double x, long now) {
        var distance = Math.abs(x - 0.5) * 2;
        var total = 0.0;

        for (var press : presses) {
            var age = now - press;

            if (press <= 0 || age < 0 || age > 700) {
                continue;
            }

            var radius = age / 450.0;
            var ring = Math.exp(-Math.pow((distance - radius) / 0.18, 2));
            total += ring * (1 - age / 700.0);
        }

        return Math.min(1, total);
    }

    /** Печатает «+» каждый раз, когда Windows видит новый ввод; через 10 минут выходит — перезапустят. */
    static String script() {
        return "$ErrorActionPreference='Stop';"
                + "Add-Type -TypeDefinition @'\n"
                + "using System; using System.Runtime.InteropServices; using System.Threading;\n"
                + "public static class VirusInput {\n"
                + "  [StructLayout(LayoutKind.Sequential)] struct LastInput { public uint Size; public uint Time; }\n"
                + "  [DllImport(\"user32.dll\")] static extern bool GetLastInputInfo(ref LastInput info);\n"
                + "  public static void Run() {\n"
                + "    var until = DateTime.Now.AddMinutes(10); uint seen = 0;\n"
                + "    while (DateTime.Now < until) {\n"
                + "      var info = new LastInput(); info.Size = (uint) Marshal.SizeOf(info);\n"
                + "      if (GetLastInputInfo(ref info) && info.Time != seen) {\n"
                + "        if (seen != 0) { Console.WriteLine(\"+\"); }\n"
                + "        seen = info.Time;\n"
                + "      }\n"
                + "      Thread.Sleep(30);\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "'@\n"
                + "[VirusInput]::Run()";
    }
}
