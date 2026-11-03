package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * «Диагностика» одной кнопкой: что с zapret, микрофоном и лампами — чтобы
 * прислать текст, а не десяток скриншотов. Ключей, токенов, Access ID и
 * Secret здесь нет: только состояния, ошибки и имена устройств.
 */
final class Diagnostics {

    private Diagnostics() {
    }

    static String script() {
        return """
                $ErrorActionPreference = 'Continue'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                $me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
                '== Права администратора: ' + $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
                '== zapret'
                foreach ($n in 'virus-zapret', 'zapret', 'WinDivert') {
                  $s = Get-Service $n -ErrorAction SilentlyContinue
                  if ($s) { 'служба ' + $n + ': ' + $s.Status + ', запуск ' + $s.StartType } else { 'службы ' + $n + ' нет' }
                }
                'sc qc: ' + ((sc.exe qc virus-zapret) -join ' ' -replace '\\s+', ' ')
                foreach ($f in 'winws.exe', 'WinDivert.dll', 'WinDivert64.sys', 'cygwin1.dll') {
                  $p = Join-Path 'C:\\Program Files\\VIRUS-zapret\\bin' $f
                  $f + ': ' + (Test-Path $p)
                }
                try {
                  Get-MpThreatDetection -ErrorAction Stop | Where-Object { ($_.Resources -join ' ') -match 'winws|WinDivert|zapret' } |
                    Select-Object -First 5 | ForEach-Object { 'Защитник: ' + (Get-MpThreat -ThreatID $_.ThreatID -ErrorAction SilentlyContinue).ThreatName + ', действие ' + $_.ThreatStatusID + ', ' + $_.InitialDetectionTime }
                } catch { 'Защитник: не узнать (' + $_.Exception.Message + ')' }
                try {
                  Get-WinEvent -FilterHashtable @{ LogName = 'System'; StartTime = (Get-Date).AddDays(-2) } -MaxEvents 3000 -ErrorAction Stop |
                    Where-Object { $_.Message -match 'zapret|WinDivert' } | Select-Object -First 6 |
                    ForEach-Object { 'журнал ' + $_.TimeCreated.ToString('dd.MM HH:mm') + ': ' + ($_.Message -replace '\\s+', ' ') }
                } catch { }
                '== Микрофон (USB 352F)'
                Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.InstanceId -match 'VID_352F' } |
                  ForEach-Object { $_.Class + ' | ' + $_.Status + ' | ' + $_.FriendlyName + ' | ' + $_.InstanceId }
                Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match 'maono' } | ForEach-Object { 'запущено: ' + $_.ProcessName }
                '== Bluetooth'
                Get-PnpDevice -Class Bluetooth -PresentOnly -ErrorAction SilentlyContinue | Select-Object -First 12 |
                  ForEach-Object { $_.Status + ' | ' + $_.FriendlyName }
                try {
                  Add-Type -AssemblyName System.Runtime.WindowsRuntime
                  $null = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
                  $w = New-Object Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher
                  $w.ScanningMode = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEScanningMode]::Active
                  Register-ObjectEvent -InputObject $w -EventName Received -SourceIdentifier adv | Out-Null
                  $w.Start()
                  $seen = @{}
                  $end = (Get-Date).AddSeconds(12)
                  while ((Get-Date) -lt $end) {
                    foreach ($e in @(Get-Event -SourceIdentifier adv -ErrorAction SilentlyContinue)) {
                      $a = $e.SourceEventArgs
                      $tuya = $false
                      foreach ($m in $a.Advertisement.ManufacturerData) { if ($m.CompanyId -eq 0x07D0) { $tuya = $true } }
                      foreach ($u in $a.Advertisement.ServiceUuids) { if ($u.ToString().StartsWith('0000a201')) { $tuya = $true } }
                      $key = $a.BluetoothAddress
                      $old = $seen[$key]
                      $name = $a.Advertisement.LocalName
                      if (-not $old -or ($name -and -not $old.n)) { $seen[$key] = @{ n = $name; r = $a.RawSignalStrengthInDBm; t = ($tuya -or ($old -and $old.t)) } }
                      Remove-Event -EventIdentifier $e.EventIdentifier
                    }
                    Start-Sleep -Milliseconds 50
                  }
                  $w.Stop()
                  'в эфире Bluetooth LE за 12 секунд: ' + $seen.Count
                  foreach ($k in $seen.Keys) {
                    $v = $seen[$k]
                    if ($v.t -or $v.n) { '  ' + $k.ToString('x12') + ' ' + $v.r + ' дБм ' + $(if ($v.t) { '[Tuya] ' } else { '' }) + $v.n }
                  }
                } catch { 'эфир Bluetooth: ' + $_.Exception.Message }
                """;
    }

    /** Последние строки файла (или пусто). */
    static String tail(Path file, int lines) {
        try {
            var all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Собрать всё в текст и положить в reports/diagnostics-….txt.
     *
     * @param statuses строки состояния из окна: название → текст
     */
    static Path collect(Path home, Map<String, String> statuses) throws Exception {
        var out = new StringBuilder("V.I.R.U.S. — диагностика " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "\n");
        statuses.forEach((name, text) -> out.append(name).append(": ").append(text == null || text.isBlank() ? "—" : text).append('\n'));

        if (SystemInfo.isWindows()) {
            var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(60, TimeUnit.SECONDS);
            out.append(text.lines().filter(line -> !line.startsWith("#< CLIXML") && !line.startsWith("<Objs"))
                    .reduce("", (a, b) -> a + b + "\n"));
        }

        for (var file : List.of("logs/zapret.log", "zapret/apply.log", "zapret/winws-out.txt", "zapret/winws-err.txt",
                "zapret/installed.txt")) {
            var text = tail(home.resolve(file), 40);

            if (!text.isBlank()) {
                out.append("== ").append(file).append('\n').append(text).append('\n');
            }
        }

        var folder = home.resolve("reports");
        Files.createDirectories(folder);
        var file = folder.resolve("diagnostics-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
