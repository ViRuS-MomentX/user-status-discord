package ru.virus.manager;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Bluetooth-лампы и ленты семейства ELK-BLEDOM — те, что настраиваются
 * приложениями Lotus Lamp X, duoCo Strip и подобными. Протокол открытый
 * (его разобрали в проектах elkbledom для Home Assistant и других): цвет —
 * команда {@code 7e 00 05 03 R G B 00 ef} в характеристику fff3.
 *
 * <p>С лампами говорит маленькая программа на PowerShell через Bluetooth
 * самой Windows: ищет лампы, подключается и ждёт цвета построчно
 * («R G B»). Пока лампы держит телефон, компьютер к ним не подключится —
 * приложение Lotus на телефоне надо закрыть.
 */
final class BleLamps {

    /** Не чаще: Bluetooth не успевает за 30 кадрами в секунду, лампы начинают отставать. */
    static final long PERIOD_MS = 120;

    /**
     * Команды разных моделей этого семейства — из таблицы проекта elkbledom
     * (models.json). У ELK-BLEDOB и похожих свой формат: цвет
     * {@code 7e 07 05 03 R G B 0a ef}, а не {@code 7e 00 05 03 R G B 00 ef}, —
     * чужую команду лампа не понимает и мигает красным. В шаблоне цвета −1, −2,
     * −3 — места красного, зелёного и синего (у LED-/JACKYLED порядок R B G).
     * Порядок важен: длинные имена раньше коротких.
     */
    record Model(String prefix, int[] on, int[] color) {
    }

    static final java.util.List<Model> MODELS = java.util.List.of(
            new Model("ELK-BLEDOB", new int[] {126, 7, 4, 255, 0, 1, 2, 1, 239}, new int[] {126, 7, 5, 3, -1, -2, -3, 10, 239}),
            new Model("ELK-BLEDOM0E", new int[] {126, 7, 4, 255, 0, 1, 2, 1, 239}, new int[] {126, 7, 5, 3, -1, -2, -3, 10, 239}),
            new Model("ELK-BLEDDM", new int[] {126, 4, 4, 240, 0, 1, 255, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("ELK-BTC", new int[] {126, 0, 4, 240, 0, 1, 255, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 10, 239}),
            new Model("MELK-OG10", new int[] {126, 7, 4, 255, 0, 1, 2, 1, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("MELK-O", new int[] {126, 4, 4, 240, 0, 1, 255, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("MELK", new int[] {126, 0, 4, 1, 0, 0, 0, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("ELK-BULB2", new int[] {126, 0, 4, 240, 0, 1, 255, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("ELK-BULB", new int[] {126, 0, 4, 1, 0, 0, 0, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("ELK-LAMPL", new int[] {126, 0, 4, 1, 0, 0, 0, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("LEDBLE", new int[] {126, 0, 4, 1, 0, 0, 0, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}),
            new Model("LED-", new int[] {126, 4, 4, 1, 255, 255, 255, 0, 239}, new int[] {126, 7, 5, 3, -1, -3, -2, 0, 239}),
            new Model("JACKYLED", new int[] {126, 4, 4, 1, 255, 255, 255, 0, 239}, new int[] {126, 7, 5, 3, -1, -3, -2, 0, 239}),
            // Всё остальное (ELK-BLEDOM, ELK-BLE…) — самый распространённый вариант
            new Model("", new int[] {126, 0, 4, 240, 0, 1, 255, 0, 239}, new int[] {126, 0, 5, 3, -1, -2, -3, 0, 239}));

    /** Модель по имени лампы в эфире. */
    static Model model(String name) {
        var upper = name.toUpperCase(java.util.Locale.ROOT);
        return MODELS.stream().filter(model -> upper.startsWith(model.prefix())).findFirst().orElse(MODELS.getLast());
    }

    /** Команда цвета для модели. */
    static byte[] colorFrame(Model model, int red, int green, int blue) {
        var frame = new byte[model.color().length];

        for (var i = 0; i < frame.length; i++) {
            var value = model.color()[i];
            frame[i] = (byte) (value == -1 ? red : value == -2 ? green : value == -3 ? blue : value);
        }

        return frame;
    }

    /** Таблица моделей для помощника на PowerShell. */
    static String modelsTable() {
        var rows = new java.util.ArrayList<String>();

        for (var model : MODELS) {
            rows.add("  @{ p = '" + model.prefix() + "'; on = @(" + join(model.on()) + "); col = @(" + join(model.color()) + ") }");
        }

        return "$models = @(\n" + String.join(",\n", rows) + "\n)";
    }

    private static String join(int[] values) {
        return java.util.Arrays.stream(values).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /** Имена ламп этого семейства в эфире. */
    static final Pattern NAMES = Pattern.compile("(?i)^(ELK|MELK|LEDBLE|BLEDOM|ELK-|LAMPL|LED-|JACKYLED|Triones|QHM-)");

    private final Consumer<String> status;
    private volatile boolean wanted;
    private volatile Writer input;
    private volatile String last = "";
    private volatile long sentAt;
    private Thread thread;

    BleLamps(Consumer<String> status) {
        this.status = status;
    }

    synchronized void start() {
        wanted = true;

        if (thread != null && thread.isAlive()) {
            return;
        }

        if (!SystemInfo.isWindows()) {
            status.accept("Лампы по Bluetooth работают только в Windows.");
            return;
        }

        thread = new Thread(this::run, "virus-ble-lamps");
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

    /** Цвет для ламп: шлём, только если он новый и прошло достаточно времени. */
    void color(Color color) {
        var writer = input;

        if (writer == null) {
            return;
        }

        var line = command(LedColor.correct(color));
        var now = System.currentTimeMillis();

        if (line.equals(last) && now - sentAt < 5000 || now - sentAt < PERIOD_MS) {
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

    /** Строка для помощника: «R G B». */
    static String command(Color color) {
        return color.getRed() + " " + color.getGreen() + " " + color.getBlue();
    }

    /** Команда цвета ELK-BLEDOM. */
    static byte[] frame(int red, int green, int blue) {
        return new byte[] {0x7e, 0x00, 0x05, 0x03, (byte) red, (byte) green, (byte) blue, 0x00, (byte) 0xef};
    }

    private void run() {
        while (wanted) {
            Process process = null;

            try {
                var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true).start();
                input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
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
                status.accept("Лампы: не запустился помощник Bluetooth — " + e.getMessage());
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

    /** Строка помощника → текст для окна; null — служебная, не показываем. */
    static String describe(String line) {
        var text = line.strip();

        if (text.startsWith("found ")) {
            return "Лампы: подключены — " + text.substring(6) + ".";
        }

        if (text.equals("none")) {
            return "Лампы: не нашёл. Включи их, закрой Lotus на телефоне (лампу держит только кто-то один) "
                    + "и проверь, что Bluetooth на компьютере включён.";
        }

        if (text.equals("nobt")) {
            return "Лампы: на компьютере нет Bluetooth или он выключен.";
        }

        if (text.startsWith("lost ")) {
            return "Лампы: связь с " + text.substring(5) + " пропала — переподключаюсь.";
        }

        if (text.startsWith("error ")) {
            return "Лампы: " + text.substring(6);
        }

        return null;
    }

    /**
     * Помощник: ищет лампы по имени среди Bluetooth LE-устройств (и уже
     * сопряжённых, и нет), находит характеристику записи (fff3, у части
     * ламп — ffe1), включает лампы и дальше пишет цвет из каждой строки.
     */
    static String script() {
        return """
                $ErrorActionPreference = 'Stop'
                try {
                  Add-Type -AssemblyName System.Runtime.WindowsRuntime
                  $null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
                  $null = [Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime]
                  $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceService, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
                } catch { [Console]::WriteLine('nobt'); exit }
                $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                  $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                function Await($op, [Type]$type) {
                  $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
                  $null = $task.Wait(15000)
                  $task.Result
                }
                $infoType = [Windows.Devices.Enumeration.DeviceInformationCollection]
                $kind = [Windows.Devices.Enumeration.DeviceInformationKind]::AssociationEndpoint
                $found = @()
                foreach ($paired in @($false, $true)) {
                  $aqs = [Windows.Devices.Bluetooth.BluetoothLEDevice]::GetDeviceSelectorFromPairingState($paired)
                  try {
                    $list = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($aqs, $null, $kind)) $infoType
                    foreach ($d in $list) { if ($d.Name -match '%NAMES%') { $found += $d } }
                  } catch { }
                }
                %MODELS%
                function ModelOf([string]$name) { foreach ($m in $models) { if ($name.ToUpper().StartsWith($m.p)) { return $m } } }
                $lamps = @()
                foreach ($d in ($found | Sort-Object Id -Unique)) {
                  try {
                    $dev = Await ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromIdAsync($d.Id)) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
                    $services = Await ($dev.GetGattServicesAsync([Windows.Devices.Bluetooth.BluetoothCacheMode]::Uncached)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
                    $target = $null
                    foreach ($s in $services.Services) {
                      $chars = Await ($s.GetCharacteristicsAsync([Windows.Devices.Bluetooth.BluetoothCacheMode]::Uncached)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
                      foreach ($c in $chars.Characteristics) {
                        $u = $c.Uuid.ToString()
                        if ($u.StartsWith('0000fff3')) { $target = $c }
                        elseif ($u.StartsWith('0000ffe1') -and $target -eq $null) { $target = $c }
                      }
                    }
                    if ($target -ne $null) { $lamps += [pscustomobject]@{ Name = $d.Name; Char = $target; Model = (ModelOf $d.Name) } }
                  } catch { [Console]::WriteLine('error ' + $d.Name + ': ' + $_.Exception.Message) }
                }
                if ($lamps.Count -eq 0) { [Console]::WriteLine('none'); exit }
                $statusType = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus]
                $noReply = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattWriteOption]::WriteWithoutResponse
                function SendTo($l, [byte[]]$bytes) {
                  $buffer = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]::AsBuffer($bytes)
                  try { $null = Await ($l.Char.WriteValueAsync($buffer, $noReply)) $statusType }
                  catch { [Console]::WriteLine('lost ' + $l.Name); exit }
                }
                # Включить каждую лампу её собственной командой
                foreach ($l in $lamps) { SendTo $l ([byte[]]$l.Model.on) }
                [Console]::WriteLine('found ' + (($lamps | ForEach-Object { $_.Name }) -join ', '))
                while ($true) {
                  $line = [Console]::In.ReadLine()
                  if ($line -eq $null -or $line -eq 'quit') { break }
                  $p = $line.Split(' ')
                  if ($p.Count -ne 3) { continue }
                  foreach ($l in $lamps) {
                    $frame = foreach ($v in $l.Model.col) { if ($v -eq -1) { [byte]$p[0] } elseif ($v -eq -2) { [byte]$p[1] } elseif ($v -eq -3) { [byte]$p[2] } else { [byte]$v } }
                    SendTo $l ([byte[]]$frame)
                  }
                }
                """.replace("%NAMES%", NAMES.pattern().replace("(?i)", "")).replace("%MODELS%", modelsTable());
    }
}
