package ru.virus.manager;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Кольцевая подсветка микрофона Maono PD200X / PD200XS (USB 352f:0104).
 * Протокол разобран в открытом проекте pd200x-linux (PROTOCOL.md): HID-отчёт
 * 0x4B на вендорской странице 0xFF01, пакет
 * {@code 4B C4 len 00 00 type слова(u16 LE) csum(u16 LE)} до 64 байт.
 * Подсветка — регистры 0x2036 (вкл), 0x2037 (яркость 0–100), 0x2038 (цвет:
 * 8 готовых и два радужных — своего RGB у микрофона нет).
 *
 * <p>Пишет в микрофон маленькая программа на PowerShell через hid.dll —
 * стандартный HID Windows, без драйверов. Пишем только эти регистры и флаг
 * «хост подключён» (0x2035), как Maono Link; DFU и сброс не трогаем никогда.
 */
final class MaonoMic {

    static final int REPORT = 0x4B;
    static final int WRITE = 3;
    static final int HOST = 0x2035;
    static final int LIGHT = 0x2036;
    static final int BRIGHTNESS = 0x2037;
    static final int COLOUR = 0x2038;

    /** Цвета микрофона по номерам, как в Maono Link. */
    static final int WHITE = 0;
    static final int RED = 1;
    static final int ORANGE = 2;
    static final int YELLOW = 3;
    static final int GREEN = 4;
    static final int CYAN = 5;
    static final int BLUE = 6;
    static final int PINK = 7;
    static final int RAINBOW = 8;

    private final Consumer<String> status;
    private volatile boolean wanted;
    private volatile Writer input;
    private volatile String last = "";
    private volatile long sentAt;
    private Thread thread;

    MaonoMic(Consumer<String> status) {
        this.status = status;
    }

    /** Пакет записи регистров: пары адрес–значение. */
    static byte[] packet(int... pairs) {
        var body = new byte[5 + pairs.length * 2];
        body[0] = (byte) 0xC4;
        body[1] = (byte) (7 + pairs.length * 2);
        body[4] = (byte) WRITE;

        for (var i = 0; i < pairs.length; i++) {
            body[5 + i * 2] = (byte) (pairs[i] & 0xFF);
            body[6 + i * 2] = (byte) ((pairs[i] >> 8) & 0xFF);
        }

        var sum = 0;

        for (var b : body) {
            sum += b & 0xFF;
        }

        var check = (-sum) & 0xFFFF;
        var packet = new byte[64];
        packet[0] = (byte) REPORT;
        System.arraycopy(body, 0, packet, 1, body.length);
        packet[1 + body.length] = (byte) (check & 0xFF);
        packet[2 + body.length] = (byte) ((check >> 8) & 0xFF);
        return packet;
    }

    /**
     * Ближайший цвет микрофона: почти серый — белый, иначе по оттенку.
     * Радужные режимы V.I.R.U.S. — радужная волна микрофона.
     */
    static int nearest(Color color, boolean rainbow) {
        if (rainbow) {
            return RAINBOW;
        }

        var hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);

        if (hsb[1] < 0.25f) {
            return WHITE;
        }

        var hue = hsb[0] * 360;

        if (hue < 15 || hue >= 330) {
            return RED;
        }
        if (hue < 45) {
            return ORANGE;
        }
        if (hue < 75) {
            return YELLOW;
        }
        if (hue < 160) {
            return GREEN;
        }
        if (hue < 200) {
            return CYAN;
        }
        if (hue < 270) {
            return BLUE;
        }
        return PINK;
    }

    /** Режимы, где цвет бежит по кругу, — для микрофона это его радужная волна. */
    static boolean rainbow(int mode) {
        return mode == 2 || mode == 3 || mode == 24;
    }

    synchronized void start() {
        wanted = true;

        if (thread != null && thread.isAlive()) {
            return;
        }

        if (!SystemInfo.isWindows()) {
            status.accept("Микрофон: только в Windows.");
            return;
        }

        thread = new Thread(this::run, "virus-maono-mic");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        wanted = false;
        var writer = input;

        if (writer != null) {
            try {
                writer.write("quit\n");
                writer.flush();
            } catch (Exception ignored) {
                // помощник уже закрыт
            }
        }
    }

    /**
     * Подсветка микрофона под режим: вкл/выкл, яркость, цвет. Шлём только
     * изменения и не чаще трёх раз в секунду — у микрофона 8 цветов, а не кадры.
     */
    void apply(boolean on, int brightness, int colour) {
        var writer = input;

        if (writer == null) {
            return;
        }

        var line = HexFormat.of().formatHex(packet(HOST, 1, LIGHT, on ? 1 : 0, BRIGHTNESS,
                Math.max(0, Math.min(100, brightness)), COLOUR, colour));
        var now = System.currentTimeMillis();

        if (line.equals(last) && now - sentAt < 10_000 || now - sentAt < 300) {
            return;
        }

        try {
            writer.write(line + "\n");
            writer.flush();
            last = line;
            sentAt = now;
        } catch (Exception e) {
            input = null;
        }
    }

    private void run() {
        while (wanted) {
            Process process = null;

            try {
                var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true).start();
                input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII);
                last = "";

                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while ((line = in.readLine()) != null) {
                        var text = describe(line);

                        if (text != null) {
                            status.accept(text);
                        }
                    }
                }
            } catch (Exception e) {
                status.accept("Микрофон: не запустился помощник — " + e.getMessage());
            } finally {
                input = null;

                if (process != null) {
                    process.destroy();
                }
            }

            if (!wanted) {
                return;
            }

            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static String describe(String line) {
        var text = line.strip();

        if (text.equals("found")) {
            return "Микрофон Maono: подключён — кольцо повторяет цвет подсветки.";
        }

        if (text.equals("none")) {
            return "Микрофон Maono: не найден. Он подключён по USB? (Maono Link можно не закрывать.)";
        }

        if (text.startsWith("error ")) {
            return "Микрофон: " + text.substring(6);
        }

        return null;
    }

    /**
     * Помощник: среди HID-устройств находит PD200X (352f:0104) с вендорской
     * страницей 0xFF01 и пишет в него 64-байтные отчёты, которые присылает
     * V.I.R.U.S. (по строке, в шестнадцатеричном виде).
     */
    static String script() {
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                using Microsoft.Win32.SafeHandles;
                public static class VirusMic {
                  [DllImport("hid.dll")] static extern void HidD_GetHidGuid(out Guid g);
                  [DllImport("setupapi.dll", CharSet = CharSet.Unicode)] static extern IntPtr SetupDiGetClassDevsW(ref Guid g, IntPtr e, IntPtr h, int flags);
                  [StructLayout(LayoutKind.Sequential)] struct DID { public int cbSize; public Guid g; public int flags; public IntPtr r; }
                  [DllImport("setupapi.dll", SetLastError = true)] static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr info, ref Guid g, int i, ref DID d);
                  [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool SetupDiGetDeviceInterfaceDetailW(IntPtr set, ref DID d, IntPtr detail, int size, out int req, IntPtr info);
                  [DllImport("setupapi.dll")] static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
                  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern SafeFileHandle CreateFileW(string n, uint access, uint share, IntPtr sec, uint disp, uint flags, IntPtr t);
                  [DllImport("kernel32.dll", SetLastError = true)] static extern bool WriteFile(SafeFileHandle h, byte[] b, int n, out int done, IntPtr o);
                  [StructLayout(LayoutKind.Sequential)] struct ATTR { public int Size; public ushort Vid; public ushort Pid; public ushort Version; }
                  [DllImport("hid.dll")] static extern bool HidD_GetAttributes(SafeFileHandle h, ref ATTR a);
                  [DllImport("hid.dll")] static extern bool HidD_GetPreparsedData(SafeFileHandle h, out IntPtr p);
                  [DllImport("hid.dll")] static extern bool HidD_FreePreparsedData(IntPtr p);
                  [StructLayout(LayoutKind.Sequential)] struct CAPS {
                    public ushort Usage; public ushort UsagePage; public ushort In; public ushort Out; public ushort Feature;
                    [MarshalAs(UnmanagedType.ByValArray, SizeConst = 17)] public ushort[] Reserved;
                    [MarshalAs(UnmanagedType.ByValArray, SizeConst = 10)] public ushort[] Counts;
                  }
                  [DllImport("hid.dll")] static extern int HidP_GetCaps(IntPtr p, ref CAPS c);
                  public static SafeFileHandle Open() {
                    Guid g; HidD_GetHidGuid(out g);
                    IntPtr set = SetupDiGetClassDevsW(ref g, IntPtr.Zero, IntPtr.Zero, 0x12);
                    try {
                      for (int i = 0; ; i++) {
                        DID d = new DID(); d.cbSize = Marshal.SizeOf(typeof(DID));
                        if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref g, i, ref d)) return null;
                        int need; SetupDiGetDeviceInterfaceDetailW(set, ref d, IntPtr.Zero, 0, out need, IntPtr.Zero);
                        IntPtr buf = Marshal.AllocHGlobal(need);
                        try {
                          Marshal.WriteInt32(buf, IntPtr.Size == 8 ? 8 : 6);
                          if (!SetupDiGetDeviceInterfaceDetailW(set, ref d, buf, need, out need, IntPtr.Zero)) continue;
                          string path = Marshal.PtrToStringUni(new IntPtr(buf.ToInt64() + 4));
                          SafeFileHandle h = CreateFileW(path, 0xC0000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);
                          if (h.IsInvalid) continue;
                          ATTR a = new ATTR(); a.Size = Marshal.SizeOf(typeof(ATTR));
                          IntPtr pre;
                          if (HidD_GetAttributes(h, ref a) && a.Vid == 0x352F && a.Pid == 0x0104 && HidD_GetPreparsedData(h, out pre)) {
                            CAPS c = new CAPS(); HidP_GetCaps(pre, ref c); HidD_FreePreparsedData(pre);
                            if (c.UsagePage == 0xFF01) return h;
                          }
                          h.Dispose();
                        } finally { Marshal.FreeHGlobal(buf); }
                      }
                    } finally { SetupDiDestroyDeviceInfoList(set); }
                  }
                  public static bool Send(SafeFileHandle h, byte[] report) {
                    int done; return WriteFile(h, report, report.Length, out done, IntPtr.Zero);
                  }
                }
                '@
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                $mic = [VirusMic]::Open()
                if ($mic -eq $null) { [Console]::WriteLine('none'); exit }
                [Console]::WriteLine('found')
                $reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
                while ($true) {
                  $line = $reader.ReadLine()
                  if ($line -eq $null -or $line -eq 'quit') { break }
                  $bytes = New-Object byte[] ($line.Length / 2)
                  for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($line.Substring($i * 2, 2), 16) }
                  if (-not [VirusMic]::Send($mic, $bytes)) { [Console]::WriteLine('error микрофон отключили или он занят — переподключаюсь'); exit }
                }
                """;
    }
}
