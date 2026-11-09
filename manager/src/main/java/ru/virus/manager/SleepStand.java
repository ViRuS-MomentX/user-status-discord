package ru.virus.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Подставка сна на Arduino: поставил фигурку — компьютер «засыпает»
 * (процессы, подсветка, экраны и мониторы гаснут), убрал — всё как было.
 * Плату ищем по COM-портам: её прошивка (virus_stand.ino) раз в 0,2 с
 * пишет «VIRUS-STAND граммы контакт». Порт держит помощник PowerShell
 * (System.IO.Ports) — без драйверов и библиотек.
 */
final class SleepStand {

    /**
     * Показание: граммы (-1 — весов нет), контакт (метка RFID, кнопка, датчик
     * на D4) и сила магнита на аналоговом датчике Холла (-1 — старая прошивка).
     */
    record Reading(int grams, boolean contact, int magnet) {

        Reading(int grams, boolean contact) {
            this(grams, contact, -1);
        }

        boolean placed(int threshold, int magnetThreshold) {
            return contact || grams >= threshold || magnet >= magnetThreshold;
        }
    }

    /** Состояние подставки, держится столько, прежде чем срабатывает. */
    static final long HOLD_MS = 2000;

    private final Consumer<String> status;
    private volatile Runnable onSleep = () -> { };
    private volatile Runnable onWake = () -> { };
    private volatile boolean wanted;
    private volatile Writer input;
    private volatile Reading last;
    private volatile String port = "";
    private volatile boolean asleep;
    private volatile int threshold = 250;
    private volatile int magnetThreshold = 20;
    private Thread thread;
    private Boolean candidate;
    private long candidateSince;

    SleepStand(Consumer<String> status) {
        this.status = status;
    }

    void actions(Runnable sleep, Runnable wake) {
        onSleep = sleep;
        onWake = wake;
    }

    void threshold(int grams) {
        threshold = Math.max(20, grams);
    }

    /** С какой силы магнита «фигурка стоит» (без неё обычно 0–3). */
    void magnetThreshold(int value) {
        magnetThreshold = Math.max(3, value);
    }

    Reading last() {
        return last;
    }

    boolean asleep() {
        return asleep;
    }

    /** «VIRUS-STAND 412 0» → показание; null — чужая строка. */
    static Reading parse(String line) {
        var parts = line.strip().split("\\s+");

        if (parts.length < 3 || parts.length > 4 || !parts[0].equals("VIRUS-STAND")) {
            return null;
        }

        try {
            return new Reading(Integer.parseInt(parts[1]), parts[2].equals("1"),
                    parts.length == 4 ? Integer.parseInt(parts[3]) : -1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Новое показание: состояние меняется, только если продержалось
     * {@link #HOLD_MS} — фигурку поправили или задели, ничего не моргает.
     *
     * @return true — «уснуть», false — «проснуться», null — ничего не делать
     */
    synchronized Boolean feed(Reading reading, long now) {
        last = reading;
        var placed = reading.placed(threshold, magnetThreshold);

        if (candidate == null || candidate != placed) {
            candidate = placed;
            candidateSince = now;
            return null;
        }

        if (now - candidateSince < HOLD_MS || placed == asleep) {
            return null;
        }

        asleep = placed;
        return placed;
    }

    private volatile Process helper;
    /** Плата была на связи в этом заходе помощника. */
    private volatile boolean connected;

    synchronized void start() {
        wanted = true;

        if (!SystemInfo.isWindows() || thread != null && thread.isAlive()) {
            return;
        }

        thread = new Thread(this::run, "virus-sleep-stand");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        wanted = false;
        send("");
    }

    /** Отпустить COM-порт насовсем (для прошивки платы): остановить и дождаться помощника. */
    void release() {
        Thread running;

        synchronized (this) {
            stop();
            running = thread;
        }

        // Помощник мог как раз перебирать порты и держать порт платы открытым — закрываем его сразу
        var process = helper;

        if (process != null) {
            process.destroyForcibly();

            try {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (running != null) {
            running.interrupt();

            try {
                running.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Команда плате: «t» — обнулить весы, «c400» — калибровка грузом 400 г. */
    boolean send(String command) {
        var writer = input;

        if (writer == null) {
            return false;
        }

        try {
            writer.write(command + "\n");
            writer.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void run() {
        while (wanted) {
            Process process = null;

            try {
                var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true).start();
                helper = process;
                input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII);

                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while (wanted && (line = in.readLine()) != null) {
                        line = line.strip();

                        if (line.equals("none")) {
                            status.accept("Подставка: плата не найдена — подключи Arduino с прошивкой V.I.R.U.S. по USB.");
                        } else if (line.equals("lost")) {
                            status.accept("Подставка: связь с платой пропала — переподключаюсь…");
                        } else if (line.startsWith("port ")) {
                            port = line.substring(5);
                            connected = true;
                            status.accept("Подставка: плата на " + port + ".");
                        } else if (line.startsWith("VIRUS-STAND-OK")) {
                            status.accept(line.contains("tare") ? "Подставка: «пусто» запомнено." : "Подставка: весы откалиброваны.");
                        } else {
                            var reading = parse(line);

                            if (reading != null) {
                                var change = feed(reading, System.currentTimeMillis());

                                if (change != null) {
                                    (change ? onSleep : onWake).run();
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                status.accept("Подставка: " + e.getMessage());
            } finally {
                input = null;
                port = "";

                if (process != null) {
                    process.destroy();
                }
            }

            try {
                // Плату только что видели — ищем снова почти сразу; не было — раз в 10 секунд
                Thread.sleep(connected ? 2_000 : 10_000);
                connected = false;
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    String port() {
        return port;
    }

    /**
     * Помощник: среди COM-портов (сначала похожие на Arduino: CH340, CP210x,
     * FTDI) находит тот, где плата пишет «VIRUS-STAND», и дальше передаёт
     * строки туда и обратно.
     */
    static String script() {
        return """
                $ErrorActionPreference = 'Stop'
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                $names = @([System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object -Unique)
                $likely = @()
                try {
                  Get-CimInstance Win32_PnPEntity -ErrorAction Stop | Where-Object { $_.Name -match '\\((COM\\d+)\\)' -and $_.Name -match 'CH34|CP210|FTDI|Arduino|USB.SERIAL|USB Serial' } |
                    ForEach-Object { if ($_.Name -match '\\((COM\\d+)\\)') { $likely += $Matches[1] } }
                } catch { }
                $order = @($likely) + @($names | Where-Object { $likely -notcontains $_ })
                $found = $null
                foreach ($name in $order) {
                  try {
                    $p = New-Object System.IO.Ports.SerialPort $name, 115200
                    $p.ReadTimeout = 300
                    $p.DtrEnable = $true
                    $p.NewLine = "`n"
                    $p.Open()
                    $end = (Get-Date).AddSeconds(6)
                    while ((Get-Date) -lt $end) {
                      try { if ($p.ReadLine() -match 'VIRUS-STAND') { $found = $p; break } } catch { }
                    }
                    if ($found) { break }
                    $p.Close()
                  } catch { }
                }
                if (-not $found) { [Console]::WriteLine('none'); exit }
                [Console]::WriteLine('port ' + $found.PortName)
                $reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
                $next = $reader.ReadLineAsync()
                while ($true) {
                  try { [Console]::WriteLine($found.ReadLine().Trim()) }
                  catch [System.TimeoutException] { }
                  catch { [Console]::WriteLine('lost'); break }
                  if ($next.IsCompleted) {
                    $command = $next.Result
                    if ($command -eq $null -or $command -eq '') { break }
                    $found.Write($command)
                    $next = $reader.ReadLineAsync()
                  }
                }
                $found.Close()
                """;
    }
}
