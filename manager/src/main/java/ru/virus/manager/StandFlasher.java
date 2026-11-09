package ru.virus.manager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Прошить подставку сна прямо из V.I.R.U.S. — без Arduino IDE. Готовая
 * прошивка (virus_stand.hex, собрана из virus_stand.ino скриптом
 * tools/arduino/build-hex.sh) заливается в Arduino Nano через её
 * загрузчик по USB тем же протоколом, что у Arduino IDE (STK500):
 * перезапуск платы, «привет», проверка, что это ATmega328P, запись по
 * страницам и сверка записанного.
 */
final class StandFlasher {

    /** Страница флеш-памяти ATmega328P — 128 байт. */
    static final int PAGE = 128;
    /** Место под программу: 32 КБ минус загрузчик (2 КБ у старого). */
    static final int MAX = 30 * 1024;

    private StandFlasher() {
    }

    /** Intel HEX → байты прошивки с нуля, дополненные 0xFF до целой страницы. */
    static byte[] image(String hex) {
        var memory = new byte[MAX];
        Arrays.fill(memory, (byte) 0xff);
        var end = 0;

        for (var raw : hex.split("\\R")) {
            var line = raw.strip();

            if (line.isEmpty()) {
                continue;
            }

            if (!line.startsWith(":") || line.length() < 11) {
                throw new IllegalArgumentException("испорченная строка прошивки: " + line);
            }

            var bytes = new byte[(line.length() - 1) / 2];

            for (var i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(line.substring(1 + i * 2, 3 + i * 2), 16);
            }

            var sum = 0;

            for (var b : bytes) {
                sum += b & 0xff;
            }

            if ((sum & 0xff) != 0) {
                throw new IllegalArgumentException("не сходится контрольная сумма прошивки: " + line);
            }

            var count = bytes[0] & 0xff;
            var address = (bytes[1] & 0xff) << 8 | bytes[2] & 0xff;
            var type = bytes[3] & 0xff;

            if (type == 1) {
                break;
            }

            if (type != 0) {
                continue;
            }

            if (address + count > MAX) {
                throw new IllegalArgumentException("прошивка не влезает в плату");
            }

            System.arraycopy(bytes, 4, memory, address, count);
            end = Math.max(end, address + count);
        }

        var size = (end + PAGE - 1) / PAGE * PAGE;
        return Arrays.copyOf(memory, size);
    }

    /** Прошивка подставки, что лежит внутри V.I.R.U.S. */
    static byte[] bundled() throws IOException {
        try (var in = StandFlasher.class.getResourceAsStream("virus_stand.hex")) {
            if (in == null) {
                throw new IOException("в V.I.R.U.S. нет файла прошивки");
            }

            return image(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    /**
     * Залить прошивку в плату. status получает понятные строки по ходу дела.
     * true — записано и сверено.
     */
    static boolean flash(Consumer<String> status) {
        if (!SystemInfo.isWindows()) {
            status.accept("Прошить плату можно только в Windows.");
            return false;
        }

        Process process = null;

        try {
            var image = bundled();
            var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true).start();

            try (var out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII)) {
                out.write(java.util.Base64.getEncoder().encodeToString(image) + "\n");
            }

            var done = false;
            var log = new ByteArrayOutputStream();

            try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;

                while ((line = in.readLine()) != null) {
                    line = line.strip();
                    log.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
                    var text = say(line);

                    if (text != null) {
                        status.accept(text);
                    }

                    if (line.equals("done")) {
                        done = true;
                    }
                }
            }

            process.waitFor();
            return done;
        } catch (Exception e) {
            status.accept("Подставка: не удалось прошить — " + e.getMessage());
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Строка помощника → что сказать человеку; null — молчать. */
    static String say(String line) {
        if (line.equals("noport")) {
            return "Подставка: плата не видна в системе. Воткни Arduino по USB. Если не помогает — нужен драйвер CH340 "
                    + "(в поиске: «CH340 driver»), установи его и переткни плату.";
        }

        if (line.startsWith("try ")) {
            return "Подставка: пробую плату на " + line.substring(4) + "…";
        }

        if (line.startsWith("found ")) {
            return "Подставка: плата на " + line.substring(6) + " ответила, записываю прошивку…";
        }

        if (line.startsWith("progress ")) {
            return "Подставка: записываю прошивку… " + line.substring(9) + "%";
        }

        if (line.startsWith("verify ")) {
            return "Подставка: сверяю записанное… " + line.substring(7) + "%";
        }

        if (line.equals("done")) {
            return "Подставка: прошивка записана и сверена. Плата перезапустилась — подключаюсь…";
        }

        if (line.startsWith("sig ")) {
            return "Подставка: это не Arduino Nano/Uno на ATmega328P (подпись чипа " + line.substring(4)
                    + ") — такую плату прошить не могу.";
        }

        if (line.equals("nosync")) {
            return "Подставка: плата не ответила загрузчиком. Закрой Arduino IDE и другие программы, которые могут "
                    + "держать порт, переткни плату и нажми ещё раз.";
        }

        if (line.startsWith("fail ")) {
            return "Подставка: прошивка прервалась — " + line.substring(5) + ". Переткни плату и нажми ещё раз.";
        }

        return null;
    }

    /**
     * Помощник на PowerShell: первой строкой stdin — прошивка в base64.
     * Порты — сначала похожие на Arduino (CH340, CP210x, FTDI); скорость
     * 115200 (новый загрузчик), потом 57600 (старый, у китайских Nano).
     */
    static String script() {
        return """
                $ErrorActionPreference = 'Stop'
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                $image = [Convert]::FromBase64String([Console]::In.ReadLine())
                $likely = @()
                try {
                  Get-CimInstance Win32_PnPEntity -ErrorAction Stop | Where-Object { $_.Name -match '\\((COM\\d+)\\)' -and $_.Name -match 'CH34|CP210|FTDI|Arduino|USB.SERIAL|USB Serial' } |
                    ForEach-Object { if ($_.Name -match '\\((COM\\d+)\\)') { $likely += $Matches[1] } }
                } catch { }
                if ($likely.Count -eq 0) { [Console]::WriteLine('noport'); exit }
                function Rd($p) { try { return $p.ReadByte() } catch { return -1 } }
                function Cmd($p, [byte[]]$b, [int]$n) {
                  $p.Write($b, 0, $b.Length)
                  if ((Rd $p) -ne 0x14) { return $null }
                  $d = New-Object byte[] $n
                  for ($i = 0; $i -lt $n; $i++) { $v = Rd $p; if ($v -lt 0) { return $null }; $d[$i] = [byte]$v }
                  if ((Rd $p) -ne 0x10) { return $null }
                  return ,$d
                }
                function Sync($name, $baud) {
                  $p = New-Object System.IO.Ports.SerialPort $name, $baud
                  $p.ReadTimeout = 150
                  $p.DtrEnable = $false
                  $p.RtsEnable = $false
                  $p.Open()
                  Start-Sleep -Milliseconds 250
                  $p.DtrEnable = $true
                  $p.RtsEnable = $true
                  Start-Sleep -Milliseconds 50
                  $p.DiscardInBuffer()
                  for ($i = 0; $i -lt 12; $i++) {
                    $r = Cmd $p ([byte[]](0x30, 0x20)) 0
                    if ($null -ne $r) { $p.DiscardInBuffer(); $r = Cmd $p ([byte[]](0x30, 0x20)) 0; if ($null -ne $r) { return $p } }
                    $p.DiscardInBuffer()
                  }
                  $p.Close()
                  return $null
                }
                $p = $null
                foreach ($name in ($likely | Sort-Object -Unique)) {
                  foreach ($baud in 115200, 57600) {
                    [Console]::WriteLine("try $name")
                    try { $p = Sync $name $baud } catch { $p = $null }
                    if ($p) { break }
                  }
                  if ($p) { break }
                }
                if (-not $p) { [Console]::WriteLine('nosync'); exit }
                try {
                  $p.ReadTimeout = 1000
                  [Console]::WriteLine('found ' + $p.PortName)
                  $sig = Cmd $p ([byte[]](0x75, 0x20)) 3
                  if ($null -eq $sig) { throw 'плата не сказала, какой у неё чип' }
                  $hex = ($sig | ForEach-Object { $_.ToString('x2') }) -join ''
                  if ($hex -ne '1e950f' -and $hex -ne '1e9514') { [Console]::WriteLine("sig $hex"); exit }
                  if ($null -eq (Cmd $p ([byte[]](0x50, 0x20)) 0)) { throw 'плата не вошла в режим записи' }
                  $pages = $image.Length / 128
                  for ($page = 0; $page -lt $pages; $page++) {
                    $word = $page * 64
                    if ($null -eq (Cmd $p ([byte[]](0x55, ($word -band 0xff), ($word -shr 8), 0x20)) 0)) { throw "не задался адрес страницы $page" }
                    $packet = New-Object byte[] 133
                    $packet[0] = 0x64; $packet[1] = 0; $packet[2] = 128; $packet[3] = 0x46
                    [Array]::Copy($image, $page * 128, $packet, 4, 128)
                    $packet[132] = 0x20
                    if ($null -eq (Cmd $p $packet 0)) { throw "не записалась страница $page" }
                    [Console]::WriteLine('progress ' + [int](($page + 1) * 100 / $pages))
                  }
                  for ($page = 0; $page -lt $pages; $page++) {
                    $word = $page * 64
                    if ($null -eq (Cmd $p ([byte[]](0x55, ($word -band 0xff), ($word -shr 8), 0x20)) 0)) { throw "не задался адрес страницы $page" }
                    $back = Cmd $p ([byte[]](0x74, 0, 128, 0x46, 0x20)) 128
                    if ($null -eq $back) { throw "не прочиталась страница $page" }
                    for ($i = 0; $i -lt 128; $i++) {
                      if ($back[$i] -ne $image[$page * 128 + $i]) { throw "записанное не совпало на странице $page" }
                    }
                    [Console]::WriteLine('verify ' + [int](($page + 1) * 100 / $pages))
                  }
                  $null = Cmd $p ([byte[]](0x51, 0x20)) 0
                  [Console]::WriteLine('done')
                } catch {
                  [Console]::WriteLine('fail ' + $_)
                } finally {
                  $p.Close()
                }
                """;
    }
}
