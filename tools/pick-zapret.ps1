# Подбирает профиль zapret, при котором открывается весь Discord.
#
# Проверять один адрес недостаточно: профили обходят фильтрацию по-разному, и
# бывает, что API уже доступен, а склад картинок и сервер обновлений — ещё нет.
# Снаружи это выглядит как работающий бот при сером экране в приложении.
# Поэтому каждый профиль проверяется по всем адресам, которыми пользуется
# Discord, и побеждает тот, что открывает их все.
#
# Запускать от имени администратора: zapret перехватывает пакеты драйвером.
#
#   powershell -ExecutionPolicy Bypass -File pick-zapret.ps1

param(
    # Папка сборки zapret-discord-youtube. Пусто — найдём сами
    [string] $Root = "",

    # Сколько ждать, пока профиль поднимется, прежде чем проверять.
    # Профиль перед запуском winws ходит на GitHub за проверкой версии,
    # поэтому пауза нужна с запасом
    [int] $WarmupSeconds = 12,

    # Сколько ждать ответа от каждого адреса
    [int] $TimeoutSeconds = 8,

    # Проверять только то, что нужно боту, — быстрее, но приложение может
    # остаться с серым экраном
    [switch] $BotOnly
)

$ErrorActionPreference = "Stop"

# Что именно должно открыться. Без склада картинок приложение показывает серый
# экран, без сервера обновлений — виснет на «Starting…», так что «работает
# Discord» — это все четыре, а не только первый
$targets = @(
    @{ Name = "API";        Url = "https://discord.com/api/v10/gateway"; Bot = $true },
    @{ Name = "картинки";   Url = "https://cdn.discordapp.com/";         Bot = $false },
    @{ Name = "обновления"; Url = "https://updates.discord.com/distributions/app/manifests/latest?channel=stable&platform=win&arch=x64"; Bot = $false },
    @{ Name = "загрузки";   Url = "https://dl.discordapp.net/";          Bot = $false }
)

if ($BotOnly) {
    $targets = $targets | Where-Object { $_.Bot }
}

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

# Отвечает ли адрес хоть чем-нибудь. Именно «хоть чем-нибудь»: склад картинок на
# голый корень отвечает ошибкой, и это всё равно означает, что связь есть.
# Молчание — вот что говорит о блокировке
function Test-Url {
    param([string] $Url)

    $code = & curl.exe -s -o NUL --max-time $TimeoutSeconds -w "%{http_code}" $Url 2>$null

    return $code -and $code.Trim() -ne "000"
}

# Возвращает имена адресов, которые не отозвались
function Find-Silent {
    $silent = @()

    foreach ($one in $targets) {
        if (-not (Test-Url $one.Url)) {
            $silent += $one.Name
        }
    }

    return ,$silent
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

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    Write-Host "Не нашёл curl.exe — он есть в Windows 10 и новее." -ForegroundColor Red
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
Write-Host "Проверяю адресов: $($targets.Count) — $(($targets | ForEach-Object { $_.Name }) -join ', ')"

# Служба, если она стоит, держит свой winws.exe и мешает проверке:
# профили будут запускаться поверх работающего, и результат окажется не тот
$service = Get-Service -Name "zapret" -ErrorAction SilentlyContinue
$wasRunning = $service -and $service.Status -eq "Running"

if ($wasRunning) {
    Write-Host "Служба zapret запущена — останавливаю на время подбора." -ForegroundColor Yellow
    Stop-Service -Name "zapret" -Force
    Start-Sleep -Seconds 2
}

Stop-Zapret

$silent = Find-Silent

if ($silent.Count -eq 0) {
    Write-Host "Discord открыт целиком и без zapret — подбирать нечего." -ForegroundColor Green
    if ($wasRunning) { Start-Service -Name "zapret" }
    exit 0
}

$all = Get-ChildItem -Path $Root -Filter "general*.bat" | Select-Object -ExpandProperty Name
$order = Get-ProfileOrder $all

Write-Host "Профилей к перебору: $($order.Count)."
Write-Host ""

$stillborn = 0
$bestName = ""
$bestSilent = $silent

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

    $silent = Find-Silent

    if ($silent.Count -eq 0) {
        Write-Host " всё открыто!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Discord доступен целиком. Окно профиля свёрнуто — не закрывай его." -ForegroundColor Green
        Write-Host ""
        Write-Host "Поставить этот профиль службой, чтобы поднимался с Windows:"
        Write-Host "  1. запусти от администратора $Root\service.bat"
        Write-Host "  2. выбери установку службы и профиль «$name»"
        exit 0
    }

    Write-Host (" нет: " + ($silent -join ", "))

    if ($silent.Count -lt $bestSilent.Count) {
        $bestName = $name
        $bestSilent = $silent
    }

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

if ($bestName -ne "") {
    Write-Host "Полностью не справился никто. Ближе всех «$bestName»:" -ForegroundColor Yellow
    Write-Host "  остаются закрытыми: $($bestSilent -join ', ')"
    Write-Host ""
    Write-Host "Запусти его и живи с этим — бот будет работать, если открыт API."
    Write-Host "Приложению Discord нужны остальные адреса, для него остаётся VPN."
} else {
    Write-Host "Ни один профиль не помог." -ForegroundColor Red
    Write-Host "Остаётся VPN — либо ждать обновления списков обхода в сборке."
}

exit 1
