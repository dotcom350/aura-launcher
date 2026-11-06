# Instalador de Aura para Windows
# ------------------------------------------------------------------------------------------
# Hace en un paso lo que la app no puede hacer sola (ver README, "¿Por qué hace falta el PC?"):
#   1. Descarga las herramientas ADB de Google si no las tienes.
#   2. Busca tu Fire TV en la red (o usa la IP que indiques).
#   3. Descarga la última versión de Aura desde GitHub y la instala.
#   4. Concede los permisos que solo se pueden dar por ADB y activa el botón Inicio.
#
# Uso: doble clic en Instalar-Aura.bat   (o: powershell -ExecutionPolicy Bypass -File Instalar-Aura.ps1 [-Ip 192.168.1.50])
param([string]$Ip = "", [switch]$SoloPermisos, [switch]$SinPausa)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Host.UI.RawUI.WindowTitle = "Instalador de Aura"

$Repo = "dotcom350/aura-launcher"
$Pkg = "com.david.aura"
$Work = Join-Path $env:LOCALAPPDATA "AuraInstaller"
New-Item -ItemType Directory -Force $Work | Out-Null

function Title($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Yellow }
function Ok($t) { Write-Host "  OK  $t" -ForegroundColor Green }
function Info($t) { Write-Host "      $t" }
function Fail($t) {
    Write-Host ""; Write-Host "  ERROR: $t" -ForegroundColor Red
    Write-Host ""; if (-not $SinPausa) { Read-Host "Pulsa Intro para salir" | Out-Null }; exit 1
}

Write-Host ""
Write-Host "  Instalador de Aura para Fire TV" -ForegroundColor Cyan
Write-Host "  github.com/$Repo"
Write-Host ""
Write-Host "  Antes de empezar, en el Fire TV:"
Write-Host "   1. Ajustes > Mi Fire TV > Opciones de desarrollador > Depuracion ADB: ACTIVADA"
Write-Host "      (si no ves 'Opciones de desarrollador': Mi Fire TV > Informacion, pulsa 7 veces el nombre)"
Write-Host "   2. El Fire TV y este PC deben estar en la misma red Wi-Fi."
Write-Host ""
if (-not $SinPausa) { Read-Host "Pulsa Intro cuando este listo" | Out-Null }

# ---------- 1. ADB ----------
Title "1/4  Herramientas ADB"
$adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = Join-Path $Work "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Info "Descargando platform-tools de Google..."
        $zip = Join-Path $Work "platform-tools.zip"
        try { Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing }
        catch { Fail "No se pudo descargar ADB. Revisa la conexion a Internet." }
        Expand-Archive $zip -DestinationPath $Work -Force
        Remove-Item $zip
    }
}
Ok "ADB: $adb"

function Adb { $ErrorActionPreference = "Continue"; & $adb @args 2>&1 | ForEach-Object { "$_" } }

# ---------- 2. Buscar el Fire TV ----------
Title "2/4  Buscando tu Fire TV"
function Find-FireTVs {
    $found = @()
    $nets = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)' -and $_.PrefixLength -ge 16 }
    foreach ($n in $nets) {
        $base = ($n.IPAddress -split '\.')[0..2] -join '.'
        Info "Revisando la red $base.0/24 ..."
        $tasks = @{}
        foreach ($i in 1..254) {
            $c = New-Object Net.Sockets.TcpClient
            $tasks["$base.$i"] = @($c, $c.ConnectAsync("$base.$i", 5555))
        }
        Start-Sleep -Milliseconds 1500
        foreach ($k in $tasks.Keys) {
            $c, $t = $tasks[$k]
            if ($t.Status -eq 'RanToCompletion' -and $c.Connected) { $found += $k }
            $c.Close()
        }
    }
    return $found | Sort-Object -Unique
}

if (-not $Ip) {
    $cands = @(Find-FireTVs)
    if ($cands.Count -eq 1) { $Ip = $cands[0]; Ok "Encontrado: $Ip" }
    elseif ($cands.Count -gt 1) {
        Info "Hay varios dispositivos con ADB:"
        for ($i = 0; $i -lt $cands.Count; $i++) { Info "  [$($i+1)] $($cands[$i])" }
        $sel = Read-Host "Elige el numero de tu Fire TV"
        $Ip = $cands[[int]$sel - 1]
    } else {
        Info "No se encontro automaticamente."
        Info "Mira la IP en el Fire TV: Ajustes > Mi Fire TV > Informacion > Red."
        $Ip = Read-Host "Escribe la IP del Fire TV (por ejemplo 192.168.1.50)"
    }
}
$Ip = $Ip.Trim()
if ($Ip -notmatch '^\d{1,3}(\.\d{1,3}){3}(:\d+)?$') { Fail "IP no valida: $Ip" }
$Dev = if ($Ip -match ':') { $Ip } else { "$($Ip):5555" }

Info "Conectando con $Dev ..."
Adb connect $Dev | Out-Null
$state = ""
for ($i = 0; $i -lt 40; $i++) {
    $state = (Adb -s $Dev get-state) -join " "
    if ($state -match '^device') { break }
    if ($i -eq 0 -or $state -match 'unauthorized') {
        Write-Host ""
        Write-Host "  >> Mira la TV: acepta 'Permitir depuracion' (marca 'Permitir siempre desde este ordenador')." -ForegroundColor Cyan
    }
    Start-Sleep 3
    Adb connect $Dev | Out-Null
}
if ($state -notmatch '^device') { Fail "No se pudo conectar con el Fire TV ($state). Revisa que la Depuracion ADB este activada y la IP." }
$model = (Adb -s $Dev shell getprop ro.product.model) -join ""
Ok "Conectado a $model ($Dev)"

# ---------- 3. Descargar e instalar Aura ----------
if (-not $SoloPermisos) {
    Title "3/4  Instalando Aura"
    $apk = Join-Path $Work "Aura.apk"
    Info "Descargando la ultima version desde GitHub..."
    try { Invoke-WebRequest "https://github.com/$Repo/releases/latest/download/Aura.apk" -OutFile $apk -UseBasicParsing }
    catch { Fail "No se pudo descargar Aura.apk de GitHub." }
    Info ("Descargado ({0:N1} MB). Instalando en la TV..." -f ((Get-Item $apk).Length / 1MB))
    $r = (Adb -s $Dev install -r $apk) -join " "
    if ($r -notmatch 'Success') {
        if ($r -match 'UPDATE_INCOMPATIBLE') { Fail "Ya hay una Aura firmada con otra clave. Desinstalala en la TV y vuelve a ejecutar el instalador." }
        Fail "La instalacion fallo: $r"
    }
    Ok "Aura instalada"
} else { Title "3/4  (se omite la instalacion: solo permisos)" }

# ---------- 4. Permisos ----------
Title "4/4  Permisos (solo se pueden dar por ADB)"
$cmds = @(
    @("pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS", "Ajustes del sistema (bienvenida, salvapantallas)"),
    @("appops set $Pkg WRITE_SETTINGS allow", "Sonidos y tiempo del salvapantallas"),
    @("appops set $Pkg SYSTEM_ALERT_WINDOW allow", "Abrir Aura al encender"),
    @("appops set $Pkg REQUEST_INSTALL_PACKAGES allow", "Instalar apps y actualizaciones"),
    @("cmd notification allow_dnd $Pkg", "No interrumpir"),
    @("settings put secure enabled_accessibility_services $Pkg/$Pkg.HomeWatcherService", "Boton Inicio y mando web"),
    @("settings put secure accessibility_enabled 1", "Accesibilidad activada")
)
foreach ($c in $cmds) {
    $out = (Adb -s $Dev shell $c[0]) -join " "
    if ($out -match 'Exception|Error|not found|Unknown') { Write-Host "  !!  $($c[1]): $out" -ForegroundColor DarkYellow }
    else { Ok $c[1] }
}
Adb -s $Dev shell "am start -n $Pkg/.MainActivity" | Out-Null

Write-Host ""
Write-Host "  Listo. Aura esta abierta en la TV: sigue la bienvenida con el mando." -ForegroundColor Green
Write-Host "  Para controlar la TV desde el movil, pulsa el boton Menu del mando y escanea el QR."
Write-Host "  Si quieres, ya puedes desactivar la Depuracion ADB en el Fire TV."
Write-Host ""
if (-not $SinPausa) { Read-Host "Pulsa Intro para salir" | Out-Null }
