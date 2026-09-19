# Подбирает рабочий профиль zapret для Discord.
#
# В сборке Flowseal два десятка профилей: они отличаются способом обмана
# фильтрации, и у разных провайдеров срабатывают разные. Перебирать их руками,
# проверяя после каждого доступность Discord, — полчаса однообразной работы,
# поэтому скрипт делает это сам и оставляет включённым тот, что сработал.
#
# Запускать от имени администратора: zapret перехватывает пакеты драйвером,
# без прав это не работает.
#
#   powershell -ExecutionPolicy Bypass -File pick-zapret.ps1

param(
    # Папка сборки zapret-discord-youtube. Пусто — найдём сами
    [string] $Root = "",

    # Сколько ждать, пока профиль поднимется, прежде чем проверять.
    # Профиль перед запуском winws ходит на GitHub за проверкой версии,
    # поэтому пауза нужна с запасом
    [int] $WarmupSeconds = 12,

    # Сколько ждать ответа Discord
    [int] $TimeoutSeconds = 8
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$probe = "https://discord.com/api/v10/gateway"

function Test-Admin {
    $me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    return $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Find-Root {
    # Опознавательный знак сборки — winws.exe в подпапке bin
    $found = Get-ChildItem -Path $HOME -Filter winws.exe -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending

    foreach ($file in $found) {
        $folder = Split-Path (Split-Path $file.FullName -Parent) -Parent

        if (Test-Path (Join-Path $folder "general.bat")) {
            return $folder
        }
    }

    return ""
}

function Stop-Zapret {
    Get-Process winws -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 800
}

function Test-Discord {
    try {
        $answer = Invoke-WebRequest -Uri $probe -TimeoutSec $TimeoutSeconds -UseBasicParsing
        return $answer.StatusCode -eq 200
    } catch {
        return $false
    }
}

# Ставит вперёд профили, которые срабатывают чаще прочих, остальные — следом
# по алфавиту. Перебор всё равно дойдёт до каждого, но обычно кончается на первых
function Get-ProfileOrder {
    param([string[]] $Available)

    $preferred = @(
        "general (ALT).bat",
        "general.bat",
        "general (ALT2).bat",
        "general (ALT3).bat",
        "general (ALT4).bat",
        "general (ALT5).bat",
        "general (FAKE TLS AUTO).bat",
        "general (FAKE TLS AUTO ALT).bat",
        "general (SIMPLE FAKE).bat"
    )

    $order = @()

    foreach ($name in $preferred) {
        if ($Available -contains $name) { $order += $name }
    }

    foreach ($name in ($Available | Sort-Object)) {
        if ($order -notcontains $name) { $order += $name }
    }

    return $order
}

if (-not (Test-Admin)) {
    Write-Host "Нужны права администратора: закрой это окно и запусти PowerShell от имени администратора." -ForegroundColor Red
    exit 2
}

if ($Root -eq "") {
    Write-Host "Ищу папку zapret..."
    $Root = Find-Root
}

if ($Root -eq "" -or -not (Test-Path $Root)) {
    Write-Host "Не нашёл сборку zapret. Укажи папку вручную: -Root ""C:\путь\к\zapret""" -ForegroundColor Red
    exit 2
}

Write-Host "Сборка: $Root"

# Служба, если она уже стоит, держит свой winws.exe и мешает проверке:
# профили будут запускаться поверх работающего и результат окажется не тот
$service = Get-Service -Name "zapret" -ErrorAction SilentlyContinue

if ($service -and $service.Status -eq "Running") {
    Write-Host "Служба zapret запущена — останавливаю на время подбора." -ForegroundColor Yellow
    Stop-Service -Name "zapret" -Force
    Start-Sleep -Seconds 2
}

Stop-Zapret

if (Test-Discord) {
    Write-Host "Discord доступен и без zapret — подбирать нечего." -ForegroundColor Green
    exit 0
}

$all = Get-ChildItem -Path $Root -Filter "general*.bat" | Select-Object -ExpandProperty Name
$order = Get-ProfileOrder $all

Write-Host "Профилей к перебору: $($order.Count). Каждый проверяю примерно $($WarmupSeconds + 2) секунд."
Write-Host ""

$stillborn = 0

foreach ($name in $order) {
    Write-Host ("  {0,-38}" -f $name) -NoNewline

    # Запускаем файл напрямую, а не через cmd /c: путь с пробелами и скобками
    # cmd разбирает по своим правилам, и одна лишняя кавычка ломает запуск
    $started = Start-Process -FilePath (Join-Path $Root $name) `
        -WorkingDirectory $Root -WindowStyle Minimized -PassThru

    Start-Sleep -Seconds $WarmupSeconds

    # Живой ли перехват. Без этой проверки «не помогло» и «не запустилось»
    # выглядят одинаково, а это совершенно разные поломки: первое про
    # провайдера, второе про драйвер WinDivert
    if (-not (Get-Process winws -ErrorAction SilentlyContinue)) {
        Write-Host " НЕ ЗАПУСТИЛСЯ" -ForegroundColor Yellow
        $stillborn++

        if (-not $started.HasExited) {
            Stop-Process -Id $started.Id -Force -ErrorAction SilentlyContinue
        }

        continue
    }

    if (Test-Discord) {
        Write-Host " работает!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Discord доступен. Окно профиля свёрнуто — не закрывай его, пока работает бот." -ForegroundColor Green
        Write-Host ""
        Write-Host "Чтобы не запускать каждый раз руками, поставь этот профиль службой:"
        Write-Host "  1. запусти от администратора $Root\service.bat"
        Write-Host "  2. выбери установку службы и профиль «$name»"
        Write-Host ""
        Write-Host "Теперь можно запускать бота."
        exit 0
    }

    Write-Host " нет"

    Stop-Zapret

    if (-not $started.HasExited) {
        Stop-Process -Id $started.Id -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ""

if ($stillborn -eq $order.Count) {
    Write-Host "Ни один профиль даже не запустился." -ForegroundColor Red
    Write-Host "Дело не в провайдере, а в драйвере WinDivert: имя службы занимает"
    Write-Host "другая программа (ProxyBridge, GoodbyeDPI и подобные) либо его не"
    Write-Host "пускает «Целостность памяти» Windows. Проверь: sc.exe qc WinDivert"
    exit 3
}

if ($stillborn -gt 0) {
    Write-Host "Не запустились: $stillborn из $($order.Count). Их проверка ничего не значит." -ForegroundColor Yellow
}

Write-Host "Ни один из запустившихся профилей не помог." -ForegroundColor Red
Write-Host "Остаётся VPN — либо ждать обновления списков обхода в сборке."
exit 1
