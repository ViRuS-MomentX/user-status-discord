# Осматривает цепочку, от которой зависит доступ бота к Discord, и называет
# виновника, когда она рвётся.
#
# Проверяется по порядку: кто владеет драйвером WinDivert, работает ли
# перехват, не поднялся ли ProxyBridge (он занимает драйвер собой), пускает
# ли «Целостность памяти» драйверы вообще — и доступен ли в итоге Discord.
#
#   powershell -ExecutionPolicy Bypass -File zapret-doctor.ps1

$ErrorActionPreference = "Continue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$problems = @()

function Note($text)  { Write-Host "  $text" }
function Good($text)  { Write-Host "  $text" -ForegroundColor Green }
function Warn($text)  { Write-Host "  $text" -ForegroundColor Yellow }
function Bad($text)   { Write-Host "  $text" -ForegroundColor Red }

Write-Host ""
Write-Host "== Драйвер WinDivert ==" -ForegroundColor Cyan

# Имя службы в системе одно на всех, и кто занял его первым, тот и владеет
# перехватом. Отсюда все прошлые беды: чужая программа держит своё, а zapret
# молча не запускается
$owner = ""
$divert = & sc.exe qc WinDivert 2>&1 | Out-String

if ($divert -match "1060") {
    Note "Служба не зарегистрирована — её создаст zapret при запуске."
} elseif ($divert -match "(?m)^\s*(Имя_двоичного_файла|BINARY_PATH_NAME)\s*:\s*(.+)$") {
    $owner = $Matches[2].Trim()
    Note "Файл драйвера: $owner"

    if ($owner -match "(?i)zapret") {
        Good "Драйвер принадлежит zapret — так и должно быть."
    } else {
        Bad "Драйвер занят чужой программой."
        $problems += "Драйвер WinDivert занят: $owner. Закрой эту программу, убери её из автозагрузки, затем: sc.exe stop WinDivert; sc.exe delete WinDivert — и перезагрузись."
    }
} else {
    Note "Служба есть, но прочитать её путь не вышло — нужны права администратора."
}

Write-Host ""
Write-Host "== Перехват ==" -ForegroundColor Cyan

$winws = Get-Process winws -ErrorAction SilentlyContinue

if ($winws) {
    Good "winws работает (номер $($winws[0].Id), с $($winws[0].StartTime))."
} else {
    Bad "winws не запущен — обход блокировок сейчас не работает."
    $problems += "Запусти профиль zapret или его службу."
}

$service = Get-Service -Name "zapret*" -ErrorAction SilentlyContinue

if ($service) {
    foreach ($one in $service) {
        if ($one.Status -eq "Running") {
            Good "Служба «$($one.Name)»: работает."
        } else {
            Warn "Служба «$($one.Name)»: $($one.Status)."
            $problems += "Служба $($one.Name) не работает: Start-Service $($one.Name)"
        }
    }
} else {
    Warn "Службы zapret нет — после перезагрузки профиль придётся запускать руками."
    Note "Поставить службой: service.bat из папки zapret, от администратора."
}

Write-Host ""
Write-Host "== Соперники за драйвер ==" -ForegroundColor Cyan

# Любая программа, перехватывающая трафик тем же способом, отнимает драйвер
$rivals = Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -match "(?i)proxybridge|goodbyedpi|byedpi|spoofdpi" }

if ($rivals) {
    foreach ($one in $rivals) {
        Bad "Работает $($one.ProcessName) — он занимает WinDivert собой."
    }
    $problems += "Закрой $($rivals[0].ProcessName) полностью, из трея, и убери из автозагрузки."
} else {
    Good "Соперников не видно."
}

Write-Host ""
Write-Host "== Целостность памяти ==" -ForegroundColor Cyan

$guard = Get-ItemProperty `
    "HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity" `
    -Name Enabled -ErrorAction SilentlyContinue

if ($guard -and $guard.Enabled -eq 1) {
    Warn "Включена. Старые версии WinDivert она не пускает."
    Note "Если драйвер свежий, это не мешает. Мешает — «Безопасность Windows» →"
    Note "«Безопасность устройства» → «Изоляция ядра»."
} else {
    Note "Выключена — драйверам не препятствует."
}

Write-Host ""
Write-Host "== Discord ==" -ForegroundColor Cyan

try {
    $answer = Invoke-WebRequest -Uri "https://discord.com/api/v10/gateway" `
        -TimeoutSec 10 -UseBasicParsing
    Good "Отвечает $($answer.StatusCode) — бота можно запускать."
} catch {
    Bad "Не отвечает: $($_.Exception.Message)"
    $problems += "Discord недоступен. Разберись с пунктами выше, потом проверь снова."
}

Write-Host ""

if ($problems.Count -eq 0) {
    Write-Host "Цепочка целая, мешать нечему." -ForegroundColor Green
    exit 0
}

Write-Host "Что делать:" -ForegroundColor Yellow

foreach ($one in $problems) {
    Write-Host "  - $one"
}

exit 1
