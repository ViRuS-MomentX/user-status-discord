# Ставит бота в автозапуск при входе в Windows.
#
# Делается задачей планировщика, а не ярлыком в автозагрузке: задача умеет
# ждать минуту после входа (чтобы служба обхода блокировок успела подняться),
# перезапускать бота, если он всё же упал, и работать без окна на экране.
#
# Запускать от администратора:
#   powershell -ExecutionPolicy Bypass -File install-autostart.ps1 -Jar "C:\путь\voice-bridge-bot.jar"
#
# Убрать из автозапуска:
#   powershell -ExecutionPolicy Bypass -File install-autostart.ps1 -Remove

param(
    # Путь к voice-bridge-bot.jar. Рядом с ним должен лежать config.properties
    [string] $Jar = "",

    # Через сколько минут после входа запускать. Меньше минуты не стоит:
    # службе обхода блокировок нужно время, иначе бот стартует в пустоту
    [int] $DelayMinutes = 1,

    # Своя java, если её нет в PATH
    [string] $Java = "",

    [string] $Name = "VoiceBridgeBot",

    [switch] $Remove
)

$ErrorActionPreference = "Stop"

function Test-Admin {
    $me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    return $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Host "Нужны права администратора: запусти PowerShell от имени администратора." -ForegroundColor Red
    exit 2
}

if ($Remove) {
    if (Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $Name -Confirm:$false
        Write-Host "Задача «$Name» убрана из автозапуска." -ForegroundColor Green
    } else {
        Write-Host "Задачи «$Name» и не было."
    }
    exit 0
}

if ($Jar -eq "") {
    Write-Host "Укажи путь к боту: -Jar ""C:\путь\voice-bridge-bot.jar""" -ForegroundColor Red
    exit 2
}

if (-not (Test-Path $Jar)) {
    Write-Host "Не нашёл файл: $Jar" -ForegroundColor Red
    exit 2
}

$Jar = (Resolve-Path $Jar).Path
$home_dir = Split-Path $Jar -Parent

# Настройки бот читает из папки, где лежит сам, поэтому рабочий каталог важен
if (-not (Test-Path (Join-Path $home_dir "config.properties"))) {
    Write-Host "Рядом с ботом нет config.properties — он не найдёт настройки." -ForegroundColor Yellow
    Write-Host "Папка: $home_dir"
}

if ($Java -eq "") {
    $found = Get-Command java.exe -ErrorAction SilentlyContinue

    if ($found) {
        $Java = $found.Source
    } elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $Java = "$env:JAVA_HOME\bin\java.exe"
    } else {
        Write-Host "Не нашёл java. Укажи её: -Java ""C:\путь\bin\java.exe""" -ForegroundColor Red
        exit 2
    }
}

Write-Host "Бот:  $Jar"
Write-Host "Java: $Java"

$log = Join-Path $home_dir "bot.log"

# Через cmd, чтобы вывод бота уходил в файл: у задачи планировщика нет окна,
# и без записи в лог о её работе нельзя было бы узнать ничего
$line = '"' + $Java + '" -jar "' + $Jar + '" >> "' + $log + '" 2>&1'

$action = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument ('/c "' + $line + '"') -WorkingDirectory $home_dir

$trigger = New-ScheduledTaskTrigger -AtLogOn
$trigger.Delay = "PT" + $DelayMinutes + "M"

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -StartWhenAvailable

# S4U — запуск без пароля и без окна на экране. Если политика машины его
# запрещает, откатываемся на обычный вход: бот будет работать так же,
# только с окном консоли
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
    -LogonType S4U -RunLevel Limited

if (Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $Name -Confirm:$false
}

try {
    Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger `
        -Settings $settings -Principal $principal `
        -Description "Бот Филя: голосовые каналы, ранги, музыка, мост с Telegram." | Out-Null
} catch {
    Write-Host "Без окна не вышло ($($_.Exception.Message)), ставлю с обычным входом." -ForegroundColor Yellow

    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -LogonType Interactive -RunLevel Limited

    Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger `
        -Settings $settings -Principal $principal `
        -Description "Бот Филя: голосовые каналы, ранги, музыка, мост с Telegram." | Out-Null
}

Write-Host ""
Write-Host "Готово. Бот будет запускаться через $DelayMinutes мин. после входа в Windows." -ForegroundColor Green
Write-Host "Лог: $log"
Write-Host ""
Write-Host "Проверить прямо сейчас, не перезагружаясь:"
Write-Host "  Start-ScheduledTask -TaskName $Name"
Write-Host "  Get-Content ""$log"" -Tail 20 -Wait"
Write-Host ""
Write-Host "Остановить:  Stop-ScheduledTask -TaskName $Name"
Write-Host "Убрать:      этот же скрипт с ключом -Remove"
