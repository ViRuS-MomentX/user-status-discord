package ru.virus.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Громкость того, что играет на компьютере, — для режима «Звук», как
 * аудио-визуализаторы у SignalRGB и режим «Музыка» у Aura.
 *
 * <p>Звук не записывается: Windows сама считает пиковый уровень каждого
 * канала (индикатор в микшере громкости, IAudioMeterInformation). Небольшая
 * программа на PowerShell читает его 30 раз в секунду и печатает «левый
 * правый». Работает, только пока режим кто-то просит.
 */
final class AudioMeter {

    private static final long IDLE_MS = 2000;

    /** Уровни 0..1 после сглаживания: вверх — сразу, вниз — плавно, как у стрелочного индикатора. */
    private static volatile double left;
    private static volatile double right;
    private static volatile long wanted;
    private static Thread thread;

    private AudioMeter() {
    }

    static double left() {
        demand();
        return left;
    }

    static double right() {
        demand();
        return right;
    }

    private static synchronized void demand() {
        wanted = System.currentTimeMillis();

        if (thread != null && thread.isAlive() || !SystemInfo.isWindows()) {
            return;
        }

        thread = new Thread(AudioMeter::run, "virus-audio-meter");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        while (System.currentTimeMillis() - wanted < IDLE_MS) {
            Process process = null;
            var heard = false;

            try {
                var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-EncodedCommand", encoded).redirectErrorStream(true).start();
                process.getOutputStream().close();

                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while ((line = in.readLine()) != null && System.currentTimeMillis() - wanted < IDLE_MS) {
                        heard |= feed(line);
                    }
                }
            } catch (Exception e) {
                // нет звуковой карты или PowerShell — режим просто стоит тихо
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            // Программа отработала свою минуту — сразу следующую; не завелась — пауза, чтобы не долбить
            if (heard) {
                continue;
            }

            left = 0;
            right = 0;

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Строка «0.512 0.430» — уровни каналов. @return была ли это строка уровней */
    static boolean feed(String line) {
        var parts = line.strip().split("\\s+");

        if (parts.length < 2) {
            return false;
        }

        try {
            var l = Double.parseDouble(parts[0]);
            var r = Double.parseDouble(parts[1]);
            // Общий пик на оба канала: иначе тихий канал «дотягивался» бы до громкого
            peak = track(peak, Math.max(l, r));
            left = smooth(left, normalize(l, peak));
            right = smooth(right, normalize(r, peak));
            return true;
        } catch (NumberFormatException ignored) {
            // строка не про уровни — например, сообщение об ошибке
            return false;
        }
    }

    /** Тише этого — тишина (шумовой порог, как squelch у WLED). */
    static final double SQUELCH = 0.008;
    /** Самая высокая чувствительность: тише этого пика громче не делаем, иначе шипение станет «музыкой». */
    static final double MIN_PEAK = 0.06;

    /** Недавний пик громкости — опора для подстройки чувствительности. */
    private static volatile double peak = MIN_PEAK;

    /**
     * Пик за последние секунды: подскакивает сразу, забывается медленно
     * (за ~10 секунд) — как AGC в звукореактивной WLED. Так подсветка
     * одинаково живая и на тихой громкости Windows, и на громкой.
     */
    static double track(double peak, double level) {
        return Math.max(MIN_PEAK, Math.max(level, peak * 0.995));
    }

    /** Уровень относительно недавнего пика, 0–1. */
    static double normalize(double level, double peak) {
        if (level < SQUELCH) {
            return 0;
        }

        return Math.min(1, level / Math.max(MIN_PEAK, peak));
    }

    /** Атака мгновенная, спад плавный: удар барабана видно, а свет не дёргается. */
    static double smooth(double was, double now) {
        var level = Math.max(0, Math.min(1, now));
        return level >= was ? level : was * 0.82 + level * 0.18;
    }

    /** Программа, которая печатает уровни. Пересоздаётся каждые 60 с — чтобы подхватить смену наушников. */
    static String script() {
        return "$ErrorActionPreference='Stop';"
                + "Add-Type -TypeDefinition @'\n"
                + "using System; using System.Runtime.InteropServices; using System.Threading; using System.Globalization;\n"
                + "[ComImport, Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
                + "interface IMMDeviceEnumerator { int EnumAudioEndpoints(); [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice device); }\n"
                + "[ComImport, Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
                + "interface IMMDevice { [PreserveSig] int Activate(ref Guid iid, int context, IntPtr parameters, "
                + "[MarshalAs(UnmanagedType.IUnknown)] out object result); }\n"
                + "[ComImport, Guid(\"C02216F6-8C67-4B5B-9D00-D008E73E0064\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
                + "interface IAudioMeterInformation { [PreserveSig] int GetPeakValue(out float peak); "
                + "[PreserveSig] int GetMeteringChannelCount(out int count); "
                + "[PreserveSig] int GetChannelsPeakValues(int count, [In, Out] float[] peaks); }\n"
                + "[ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")] class MMDeviceEnumeratorCom {}\n"
                + "public static class VirusMeter {\n"
                + "  public static void Run() {\n"
                + "    var enumerator = (IMMDeviceEnumerator) new MMDeviceEnumeratorCom();\n"
                + "    IMMDevice device; Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));\n"
                + "    var iid = new Guid(\"C02216F6-8C67-4B5B-9D00-D008E73E0064\"); object meterObject;\n"
                + "    Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out meterObject));\n"
                + "    var meter = (IAudioMeterInformation) meterObject; int channels; meter.GetMeteringChannelCount(out channels);\n"
                + "    var peaks = new float[Math.Max(1, channels)]; var until = DateTime.Now.AddSeconds(60);\n"
                + "    while (DateTime.Now < until) {\n"
                + "      meter.GetChannelsPeakValues(peaks.Length, peaks);\n"
                + "      var l = peaks[0]; var r = peaks.Length > 1 ? peaks[1] : peaks[0];\n"
                + "      Console.WriteLine(l.ToString(\"F3\", CultureInfo.InvariantCulture) + \" \" + r.ToString(\"F3\", CultureInfo.InvariantCulture));\n"
                + "      Thread.Sleep(33);\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "'@\n"
                + "[VirusMeter]::Run()";
    }
}
