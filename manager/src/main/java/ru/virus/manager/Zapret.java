package ru.virus.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * zapret от Flowseal (github.com/Flowseal/zapret-discord-youtube) — обход
 * блокировок Discord и YouTube. V.I.R.U.S. сам скачивает последнюю версию,
 * собирает команду winws.exe из выбранной стратегии (general*.bat) и ставит
 * её службой Windows, как это делает их service.bat.
 *
 * <p>winws.exe работает только с правами администратора, поэтому:
 * <ul>
 *   <li>готовим всё в своей папке (stage) без прав;</li>
 *   <li>один скрипт с запросом прав (UAC) копирует это в Program Files,
 *       закрывает папку от записи без прав (иначе любая программа могла бы
 *       подменить winws.exe, который служба запускает от SYSTEM), ставит
 *       службу и разрешает обычному пользователю только запускать и
 *       останавливать её;</li>
 *   <li>дальше V.I.R.U.S. включает и выключает службу без запросов.</li>
 * </ul>
 */
final class Zapret {

    static final String ID = "zapret";
    static final String SERVICE = "virus-zapret";
    static final String REPO = "Flowseal/zapret-discord-youtube";

    /** Режимы игрового фильтра, как в service.bat: выкл / TCP и UDP / только TCP / только UDP. */
    static final List<String> GAME_MODES = List.of("disabled", "all", "tcp", "udp");
    /** Режимы IPSet, как в service.bat: обычный список / ничего / любые адреса. */
    static final List<String> IPSET_MODES = List.of("loaded", "none", "any");

    private static final Pattern VAR = Pattern.compile("%[^%\\s]+%");
    private static final String PORTS = "1024-65535";

    record Release(String tag, String zip) {
    }

    private final Path home;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    Zapret(Path home) {
        this.home = home;
    }

    /** Своя папка: настройки, скрипты и подготовленная копия zapret (stage). */
    Path folder() {
        return home.resolve("zapret");
    }

    Path stage() {
        return folder().resolve("stage");
    }

    /** Куда ставится: Program Files — туда без прав администратора не пишут. */
    static Path app() {
        var programFiles = System.getenv("ProgramFiles");
        return Path.of(programFiles == null ? "C:\\Program Files" : programFiles, "VIRUS-zapret");
    }

    // ---- Настройки ----

    Properties settings() {
        var props = new Properties();
        props.setProperty("strategy", "general.bat");
        props.setProperty("game", "disabled");
        props.setProperty("ipset", "loaded");
        props.setProperty("withWindows", "false");
        props.setProperty("autoUpdate", "true");

        try (var in = Files.newBufferedReader(folder().resolve("zapret.properties"), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException ignored) {
            // настроек ещё нет — по умолчанию
        }

        return props;
    }

    void save(Properties props) throws IOException {
        Files.createDirectories(folder());

        try (var out = Files.newBufferedWriter(folder().resolve("zapret.properties"), StandardCharsets.UTF_8)) {
            props.store(out, "zapret в V.I.R.U.S.");
        }
    }

    /** Версия в подготовленной копии; пусто — ещё не скачан. */
    String version() {
        try {
            return Files.readString(stage().resolve(".virus-version"), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** Версия, которую уже поставили службой (то, что сейчас работает). */
    String installed() {
        try {
            return Files.readString(folder().resolve("installed.txt"), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- Обновление с GitHub ----

    Release latest() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VIRUS-manager").build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("GitHub ответил " + response.statusCode());
        }

        return parseRelease(response.body());
    }

    /** Из ответа GitHub: тег и ссылка на zip (rar V.I.R.U.S. не распакует). */
    static Release parseRelease(String json) throws IOException {
        var data = Json.parse(json);

        if (!(data instanceof Map<?, ?> release)) {
            throw new IOException("непонятный ответ GitHub");
        }

        var tag = String.valueOf(release.get("tag_name"));

        if (release.get("assets") instanceof List<?> assets) {
            for (var asset : assets) {
                if (asset instanceof Map<?, ?> file && String.valueOf(file.get("name")).toLowerCase().endsWith(".zip")) {
                    return new Release(tag, String.valueOf(file.get("browser_download_url")));
                }
            }
        }

        throw new IOException("в релизе " + tag + " нет zip-архива");
    }

    /**
     * Скачать и разложить в stage. Свои списки (*-user.txt) и игровой фильтр
     * переносятся из прошлой версии.
     */
    void download(Release release) throws IOException, InterruptedException {
        Files.createDirectories(folder());
        var zip = folder().resolve("download.zip");
        var request = HttpRequest.newBuilder(URI.create(release.zip())).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "VIRUS-manager").build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofFile(zip));

        if (response.statusCode() != 200) {
            throw new IOException("архив не скачался: " + response.statusCode());
        }

        var fresh = folder().resolve("new");
        deleteTree(fresh);

        try (var in = Files.newInputStream(zip)) {
            unzip(in, fresh);
        }

        var root = singleRoot(fresh);

        // Свои списки — из прошлой версии
        var old = stage();

        for (var keep : List.of("lists/list-general-user.txt", "lists/list-exclude-user.txt",
                "lists/ipset-exclude-user.txt")) {
            var from = old.resolve(keep);

            if (Files.isRegularFile(from)) {
                Files.createDirectories(root.resolve(keep).getParent());
                Files.copy(from, root.resolve(keep), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Files.writeString(root.resolve(".virus-version"), release.tag(), StandardCharsets.UTF_8);
        deleteTree(old);
        Files.move(root, old);
        deleteTree(fresh);
        Files.deleteIfExists(zip);
    }

    /** Распаковать zip, не выпуская файлы за пределы папки. */
    static void unzip(InputStream in, Path target) throws IOException {
        Files.createDirectories(target);

        try (var zip = new ZipInputStream(in)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                var path = target.resolve(entry.getName()).normalize();

                if (!path.startsWith(target)) {
                    throw new IOException("подозрительный путь в архиве: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.copy(zip, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Если в архиве всё лежит в одной папке — она и есть корень. */
    static Path singleRoot(Path folder) throws IOException {
        try (var list = Files.list(folder)) {
            var items = list.toList();
            return items.size() == 1 && Files.isDirectory(items.getFirst()) ? items.getFirst() : folder;
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {
            for (var item : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    // ---- Стратегии и команда ----

    /** Стратегии — general*.bat в корне версии. */
    List<String> strategies() {
        try (var list = Files.list(stage())) {
            return list.map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase().startsWith("general") && name.toLowerCase().endsWith(".bat"))
                    .sorted(Comparator.comparing(Zapret::strategyOrder).thenComparing(String::compareTo)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** general.bat первым, потом ALT по номеру, потом остальные. */
    static int strategyOrder(String name) {
        if (name.equalsIgnoreCase("general.bat")) {
            return 0;
        }

        var matcher = Pattern.compile("\\(ALT(\\d*)\\)").matcher(name);
        return matcher.find() ? 1 + (matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1))) : 1000;
    }

    /**
     * Аргументы winws.exe из .bat: строка {@code start ... winws.exe" ...}
     * со всеми продолжениями через ^, с подставленными папками и игровым фильтром.
     */
    static String arguments(String bat, Path app, String gameMode) throws IOException {
        var lines = bat.lines().toList();
        var joined = new StringBuilder();
        var collecting = false;

        for (var line : lines) {
            var text = line.strip();

            if (!collecting && text.toLowerCase().contains("winws.exe\"") && text.toLowerCase().startsWith("start")) {
                collecting = true;
                text = text.substring(text.toLowerCase().indexOf("winws.exe\"") + "winws.exe\"".length());
            } else if (!collecting) {
                continue;
            }

            var more = text.endsWith("^");
            joined.append(more ? text.substring(0, text.length() - 1) : text).append(' ');

            if (!more) {
                break;
            }
        }

        if (joined.isEmpty()) {
            throw new IOException("в стратегии нет запуска winws.exe — формат .bat поменялся");
        }

        var tcp = gameMode.equals("all") || gameMode.equals("tcp") ? PORTS : "12";
        var udp = gameMode.equals("all") || gameMode.equals("udp") ? PORTS : "12";
        var root = app.toString() + "\\";
        var args = joined.toString().strip().replaceAll("\\s+", " ")
                .replace("%BIN%", root + "bin\\").replace("%LISTS%", root + "lists\\").replace("%~dp0", root)
                .replace("%GameFilterTCP%", tcp).replace("%GameFilterUDP%", udp)
                .replace("%GameFilter%", tcp);
        var left = VAR.matcher(args);

        if (left.find()) {
            throw new IOException("в стратегии осталась неизвестная переменная " + left.group());
        }

        return args;
    }

    /**
     * Перед установкой: свои списки не пустые (winws не стартует без
     * файлов из --hostlist), режим IPSet — как в service.bat.
     */
    void prepare(Properties props) throws IOException {
        var lists = stage().resolve("lists");
        Files.createDirectories(lists);
        defaults(lists.resolve("list-general-user.txt"), "# Свои домены для обхода, по одному в строке\ndomain.example.abc\n");
        defaults(lists.resolve("list-exclude-user.txt"), "# Домены, которые не трогать\ndomain.example.abc\n");
        defaults(lists.resolve("ipset-exclude-user.txt"), "203.0.113.113/32\n");

        var ipset = lists.resolve("ipset-all.txt");
        var backup = lists.resolve("ipset-all.txt.backup");

        // Оригинальный список бережём, чтобы вернуться к «loaded»
        if (Files.isRegularFile(ipset) && !Files.isRegularFile(backup)
                && !Files.readString(ipset, StandardCharsets.UTF_8).strip().equals("203.0.113.113/32")
                && Files.size(ipset) > 0) {
            Files.copy(ipset, backup);
        }

        switch (props.getProperty("ipset", "loaded")) {
            case "none" -> Files.writeString(ipset, "203.0.113.113/32\n", StandardCharsets.UTF_8);
            case "any" -> Files.writeString(ipset, "", StandardCharsets.UTF_8);
            default -> {
                if (Files.isRegularFile(backup)) {
                    Files.copy(backup, ipset, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        Files.writeString(folder().resolve("keeper.ps1"), "\uFEFF" + keeperScript(), StandardCharsets.UTF_8);
    }

    private static void defaults(Path file, String text) throws IOException {
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
    }

    /** Список доменов пользователя — для окна правки. */
    Path userList(String name) {
        return stage().resolve("lists").resolve(name);
    }

    // ---- Установка службы (с правами администратора) ----

    /**
     * Скрипт, который запускается с запросом прав: копирует stage в Program
     * Files, закрывает папку от записи, ставит или перенастраивает службу и
     * даёт пользователям право только запускать и останавливать её.
     */
    String applyScript(String args, boolean withWindows, boolean start) {
        var bin = "\"" + app() + "\\bin\\winws.exe\" " + args;
        return String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$log = Join-Path $PSScriptRoot 'apply.log'",
                "try {",
                "  $name = '" + SERVICE + "'",
                "  $app = '" + ps(app().toString()) + "'",
                "  $stage = '" + ps(stage().toString()) + "'",
                "  if (Get-Service $name -ErrorAction SilentlyContinue) { Stop-Service $name -Force -ErrorAction SilentlyContinue }",
                // Свой zapret от service.bat Flowseal (служба «zapret») мешал бы: два winws на одном WinDivert
                "  if (Get-Service 'zapret' -ErrorAction SilentlyContinue) { Stop-Service 'zapret' -Force -ErrorAction SilentlyContinue; Set-Service 'zapret' -StartupType Disabled }",
                "  Get-Process winws -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue",
                // Как :tcp_enable в service.bat: часть стратегий обманывает DPI через TCP timestamps
                "  netsh interface tcp set global timestamps=enabled | Out-Null",
                "  Start-Sleep -Milliseconds 800",
                "  if (-not (Test-Path $app)) { New-Item -ItemType Directory -Path $app | Out-Null }",
                "  robocopy $stage $app /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null",
                "  if ($LASTEXITCODE -ge 8) { throw \"robocopy: код $LASTEXITCODE\" }",
                // Только администраторы и SYSTEM пишут; пользователи — читают и запускают
                "  icacls $app /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)RX' /T /C /Q | Out-Null",
                "  $bin = '" + ps(bin) + "'",
                "  $type = '" + (withWindows ? "Automatic" : "Manual") + "'",
                "  if (Get-Service $name -ErrorAction SilentlyContinue) {",
                "    Set-ItemProperty \"HKLM:\\SYSTEM\\CurrentControlSet\\Services\\$name\" -Name ImagePath -Value $bin",
                "    Set-Service $name -StartupType $type",
                "  } else {",
                "    New-Service -Name $name -BinaryPathName $bin -DisplayName 'zapret (V.I.R.U.S.)' -StartupType $type -Description 'Обход блокировок Discord и YouTube (Flowseal zapret). Управляется V.I.R.U.S.' | Out-Null",
                "  }",
                // Пользователям — запуск, остановка и опрос; менять настройки службы — только администраторам
                "  sc.exe sdset $name 'D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWRPWPDTLOCRRC;;;AU)' | Out-Null",
                start ? "  Start-Service $name" : "",
                "  Set-Content -Path $log -Value 'ok' -Encoding UTF8",
                "} catch {",
                "  Set-Content -Path $log -Value ('error: ' + $_.Exception.Message) -Encoding UTF8",
                "}");
    }

    /**
     * Проверка с правами: запустить winws.exe с той же командой на 5 секунд
     * (служба на это время остановлена) и записать, что он сказал. Так видно
     * его собственную ошибку — Windows о службе пишет только «не удалось».
     */
    String diagnoseScript(String args) {
        var exe = app() + "\\bin\\winws.exe";
        return String.join("\n",
                "$log = Join-Path $PSScriptRoot 'apply.log'",
                "$out = Join-Path $PSScriptRoot 'winws-out.txt'",
                "$err = Join-Path $PSScriptRoot 'winws-err.txt'",
                "try {",
                "  Stop-Service '" + SERVICE + "' -Force -ErrorAction SilentlyContinue",
                "  Get-Process winws -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue",
                "  $exe = '" + ps(exe) + "'",
                "  if (-not (Test-Path $exe)) { throw 'нет winws.exe — его удалил антивирус или zapret не поставлен' }",
                "  $p = Start-Process -FilePath $exe -ArgumentList '" + ps(args) + "' -WorkingDirectory (Split-Path $exe) -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err",
                "  Start-Sleep -Seconds 5",
                "  $text = ((Get-Content $out -Raw -ErrorAction SilentlyContinue) + ' ' + (Get-Content $err -Raw -ErrorAction SilentlyContinue)).Trim()",
                "  if ($p.HasExited) { $result = 'winws.exe сразу завершился (код ' + $p.ExitCode + '): ' + $text }",
                "  else { Stop-Process -Id $p.Id -Force; $result = 'winws.exe запускается и работает — значит, дело в самой службе. ' + $text }",
                "  Set-Content -Path $log -Value ('error: ' + $result) -Encoding UTF8",
                "} catch { Set-Content -Path $log -Value ('error: ' + $_.Exception.Message) -Encoding UTF8 }");
    }

    /** Проверить запуск winws.exe напрямую; вернёт, что он сказал. */
    String diagnose() {
        try {
            var props = settings();
            var bat = stage().resolve(props.getProperty("strategy", "general.bat"));
            var args = arguments(Files.readString(bat, StandardCharsets.ISO_8859_1), app(), props.getProperty("game", "disabled"));
            var result = elevate(diagnoseScript(args));
            return result == null ? "проверка ничего не сказала" : result;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Удаление службы и папки — тоже с правами. */
    String removeScript() {
        return String.join("\n",
                "$log = Join-Path $PSScriptRoot 'apply.log'",
                "try {",
                "  Stop-Service '" + SERVICE + "' -Force -ErrorAction SilentlyContinue",
                "  sc.exe delete '" + SERVICE + "' | Out-Null",
                "  Start-Sleep -Milliseconds 800",
                "  Remove-Item -Recurse -Force '" + ps(app().toString()) + "' -ErrorAction SilentlyContinue",
                "  Set-Content -Path $log -Value 'ok' -Encoding UTF8",
                "} catch { Set-Content -Path $log -Value ('error: ' + $_.Exception.Message) -Encoding UTF8 }");
    }

    /** Строка для PowerShell в одинарных кавычках. */
    static String ps(String text) {
        return text.replace("'", "''");
    }

    /**
     * Поставить службу с текущими настройками: подготовить stage, собрать
     * команду, запустить скрипт с запросом прав и дождаться его.
     *
     * @return null — получилось, иначе что пошло не так
     */
    String apply(boolean start) {
        try {
            var props = settings();

            if (version().isEmpty()) {
                return "zapret ещё не скачан — нажми «Обновить»";
            }

            prepare(props);
            var bat = stage().resolve(props.getProperty("strategy", "general.bat"));

            if (!Files.isRegularFile(bat)) {
                return "нет стратегии " + bat.getFileName() + " — выбери другую";
            }

            var args = arguments(Files.readString(bat, StandardCharsets.ISO_8859_1), app(), props.getProperty("game", "disabled"));
            var ok = elevate(applyScript(args, Boolean.parseBoolean(props.getProperty("withWindows")), start));

            if (ok == null) {
                Files.writeString(folder().resolve("installed.txt"), version() + " · " + props.getProperty("strategy"),
                        StandardCharsets.UTF_8);
            }

            return ok;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    String remove() {
        var result = elevate(removeScript());

        if (result == null) {
            try {
                Files.deleteIfExists(folder().resolve("installed.txt"));
            } catch (IOException ignored) {
                // не страшно
            }
        }

        return result;
    }

    /** Запустить скрипт с запросом прав (UAC) и дождаться; null — всё хорошо. */
    private String elevate(String script) {
        try {
            Files.createDirectories(folder());
            var file = folder().resolve("apply.ps1");
            var log = folder().resolve("apply.log");
            Files.deleteIfExists(log);
            Files.writeString(file, "\uFEFF" + script, StandardCharsets.UTF_8);
            var command = "Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "
                    + "'-NoProfile','-ExecutionPolicy','Bypass','-File','\"" + ps(file.toString()) + "\"'";
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command)
                    .redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();

            if (!Files.isRegularFile(log)) {
                return out.toLowerCase().contains("cancel") || out.contains("отмен")
                        ? "права администратора не дали — отменено" : "скрипт установки не отработал";
            }

            var result = Files.readString(log, StandardCharsets.UTF_8).replace("\uFEFF", "").strip();
            return result.equals("ok") ? null : result.replaceFirst("^error: ", "");
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ---- Процесс-смотритель ----

    /** Процесс в списке V.I.R.U.S.: смотритель службы zapret. */
    static ProcessSpec defaultSpec() {
        return new ProcessSpec(ID, "zapret", List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", "{home}\\zapret\\keeper.ps1"), "zapret", false, true, true);
    }

    /**
     * Смотритель: запускает службу, раз в 3 секунды проверяет, что она
     * работает, по «stop» из V.I.R.U.S. — останавливает. Служба упала сама —
     * пишет ERROR и выходит (V.I.R.U.S. перезапустит).
     */
    static String keeperScript() {
        return String.join("\n",
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
                "$name = '" + SERVICE + "'",
                "function State { try { (Get-Service $name -ErrorAction Stop).Status.ToString() } catch { 'Missing' } }",
                "if ((State) -eq 'Missing') {",
                "  Write-Output 'ERROR: служба zapret не установлена — нажми «Установить» в настройках zapret'",
                "  exit 2",
                "}",
                "if ((State) -ne 'Running') {",
                "  Write-Output 'Запускаю службу zapret…'",
                "  try { Start-Service $name -ErrorAction Stop } catch {",
                "    Write-Output ('ERROR: служба не запустилась: ' + $_.Exception.Message)",
                // Почему — файлы на месте ли (их часто удаляет антивирус), код ошибки и запись журнала Windows
                "    $bin = '" + ps(app().toString()) + "\\bin'",
                "    foreach ($file in 'winws.exe', 'WinDivert.dll', 'WinDivert64.sys', 'cygwin1.dll') {",
                "      if (-not (Test-Path (Join-Path $bin $file))) { Write-Output ('ERROR: нет файла ' + $file + ' — скорее всего его удалил антивирус. Добавь папку в исключения и нажми «Применить».') }",
                "    }",
                "    $sc = (sc.exe start $name) -join ' '",
                "    if ($sc -match '(\\d{3,5})') { Write-Output ('Код Windows: ' + $Matches[1] + ' (' + ($sc -replace '\\s+', ' ').Trim() + ')') }",
                "    try {",
                "      Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Service Control Manager'; StartTime = (Get-Date).AddMinutes(-2) } -MaxEvents 20 -ErrorAction Stop |",
                "        Where-Object { $_.Message -match 'zapret' } | Select-Object -First 2 | ForEach-Object { Write-Output ('Журнал Windows: ' + ($_.Message -replace '\\s+', ' ')) }",
                "    } catch { }",
                "    Write-Output 'Подробности даст кнопка «Проверить запуск» в настройках zapret.'",
                "    exit 1",
                "  }",
                "}",
                "$info = Join-Path $PSScriptRoot 'installed.txt'",
                "Write-Output ('zapret работает: ' + (Get-Content $info -ErrorAction SilentlyContinue))",
                // Не [Console]::In: его ReadLineAsync в PowerShell 5.1 на деле ждёт строку и не даёт следить за службой
                "$reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput())",
                "$next = $reader.ReadLineAsync()",
                "while ($true) {",
                "  if ($next.Wait(3000)) {",
                "    $line = $next.Result",
                "    if ($line -eq $null -or $line.Trim() -eq 'stop') {",
                "      Write-Output 'Останавливаю службу zapret…'",
                "      Stop-Service $name -ErrorAction SilentlyContinue",
                "      Write-Output 'zapret остановлен.'",
                "      exit 0",
                "    }",
                "    $next = $reader.ReadLineAsync()",
                "  }",
                "  if ((State) -ne 'Running') {",
                "    Write-Output 'ERROR: служба zapret остановилась сама — попробуй другую стратегию или проверь антивирус (WinDivert)'",
                "    exit 1",
                "  }",
                "}");
    }

    /** Свежее ли — сравнение тегов вида 1.10.4. */
    static boolean newer(String latest, String current) {
        if (current.isBlank()) {
            return true;
        }

        var a = numbers(latest);
        var b = numbers(current);

        for (var i = 0; i < Math.max(a.size(), b.size()); i++) {
            var x = i < a.size() ? a.get(i) : 0;
            var y = i < b.size() ? b.get(i) : 0;

            if (x != y) {
                return x > y;
            }
        }

        return false;
    }

    private static List<Integer> numbers(String version) {
        var result = new ArrayList<Integer>();
        var matcher = Pattern.compile("\\d+").matcher(version);

        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group()));
        }

        return result;
    }
}
