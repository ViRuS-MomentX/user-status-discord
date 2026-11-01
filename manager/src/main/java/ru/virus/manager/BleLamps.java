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
    static final long PERIOD_MS = 80;

    /** Имена ламп этого семейства в эфире. */
    static final Pattern NAMES = Pattern.compile("(?i)^(ELK|MELK|LEDBLE|BLEDOM|ELK-|LAMPL|LED-|Triones|QHM-)");

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
                    if ($target -ne $null) { $lamps += [pscustomobject]@{ Name = $d.Name; Char = $target } }
                  } catch { [Console]::WriteLine('error ' + $d.Name + ': ' + $_.Exception.Message) }
                }
                if ($lamps.Count -eq 0) { [Console]::WriteLine('none'); exit }
                $statusType = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus]
                $noReply = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattWriteOption]::WriteWithoutResponse
                function Send([byte[]]$bytes) {
                  $buffer = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]::AsBuffer($bytes)
                  foreach ($l in $lamps) {
                    try { $null = Await ($l.Char.WriteValueAsync($buffer, $noReply)) $statusType }
                    catch { [Console]::WriteLine('lost ' + $l.Name); exit }
                  }
                }
                Send ([byte[]](0x7e,0x00,0x04,0xf0,0x00,0x01,0xff,0x00,0xef))
                [Console]::WriteLine('found ' + (($lamps | ForEach-Object { $_.Name }) -join ', '))
                while ($true) {
                  $line = [Console]::In.ReadLine()
                  if ($line -eq $null -or $line -eq 'quit') { break }
                  $p = $line.Split(' ')
                  if ($p.Count -ne 3) { continue }
                  Send ([byte[]](0x7e,0x00,0x05,0x03,[byte]$p[0],[byte]$p[1],[byte]$p[2],0x00,0xef))
                }
                """.replace("%NAMES%", NAMES.pattern().replace("(?i)", ""));
    }
}
