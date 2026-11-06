<# : -------- parte de Windows (cmd). La parte de PowerShell empieza mas abajo. --------
@echo off
setlocal
title Instalador de Aura
"%SystemRoot%\System32\chcp.com" 65001 >nul 2>&1
rem Argumentos opcionales: -SoloPermisos  -Ip 192.168.1.50
set "AURA_ARGS=%*"
set "AURA_BAT=%~f0"

rem PowerShell por su ruta completa: en algunos PC no esta en el PATH
set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PSEXE%" set "PSEXE=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not exist "%PSEXE%" (
  echo.
  echo   No se encontro PowerShell en este PC. Hace falta Windows 10 u 11.
  echo.
  pause
  exit /b 1
)

rem Se ejecuta como administrador: si no lo es, se vuelve a abrir pidiendo permiso (ventana de Windows)
if not defined AURA_NOADMIN (
  "%SystemRoot%\System32\net.exe" session >nul 2>&1
  if errorlevel 1 (
    echo.
    echo   Pidiendo permisos de administrador...
    "%PSEXE%" -NoProfile -Command "$a = @{ FilePath = $env:AURA_BAT; Verb = 'RunAs' }; if ($env:AURA_ARGS) { $a.ArgumentList = $env:AURA_ARGS }; Start-Process @a" >nul 2>&1
    if errorlevel 1 (
      echo.
      echo   No se concedieron permisos de administrador.
      echo   Vuelve a abrir el instalador y pulsa Si en la ventana de Windows.
      echo.
      pause
    )
    exit /b
  )
)

"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -Command "$s = Get-Content -LiteralPath $env:AURA_BAT -Raw -Encoding UTF8; $i = $s.IndexOf([char]10 + '#>'); Invoke-Expression $s.Substring($i + 3)"
rem 0 = bien, 2 = error ya mostrado; cualquier otro codigo = fallo inesperado de PowerShell
if errorlevel 3 goto inesperado
if errorlevel 2 exit /b 2
if errorlevel 1 goto inesperado
exit /b 0
:inesperado
echo.
echo   El instalador se detuvo por un error inesperado. Revisa los mensajes de arriba.
echo.
pause
exit /b 1
#>
# ======================================================================================
# Instalador de Aura para Fire TV (PowerShell). Todo en un solo archivo para que funcione
# incluso abriendolo directamente desde dentro del .zip.
#   1. Descarga ADB (Google) si no lo tienes.
#   2. Busca tu Fire TV en la red (o usa -Ip).
#   3. Descarga la ultima Aura de GitHub y la instala (salvo -SoloPermisos).
#   4. Concede los permisos que una app no puede darse a si misma.
# Registro de la instalacion: %LOCALAPPDATA%\AuraInstaller\instalacion.log
# ======================================================================================
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = "dotcom350/aura-launcher"
$Pkg = "com.david.aura"
$Work = Join-Path $env:LOCALAPPDATA "AuraInstaller"
New-Item -ItemType Directory -Force $Work | Out-Null
$Log = Join-Path $Work "instalacion.log"

# --- argumentos ---
$argText = "$env:AURA_ARGS"
$SoloPermisos = $argText -match '(?i)-SoloPermisos'
$SinPausa = $argText -match '(?i)-SinPausa'
$Ip = if ($argText -match '(?i)-Ip\s+(\S+)') { $Matches[1] } else { "" }

# --- presentacion ---
try {
    $Host.UI.RawUI.WindowTitle = "Instalador de Aura"
    $Host.UI.RawUI.BackgroundColor = "Black"
    $Host.UI.RawUI.ForegroundColor = "Gray"
    $size = $Host.UI.RawUI.WindowSize
    if ($size.Width -lt 96) {
        $Host.UI.RawUI.BufferSize = New-Object Management.Automation.Host.Size(100, 3000)
        $Host.UI.RawUI.WindowSize = New-Object Management.Automation.Host.Size(100, 38)
    }
    Clear-Host
} catch {}

function W($text = "", $color = "Gray") { Write-Host $text -ForegroundColor $color; Add-Content -Path $Log -Value $text -Encoding UTF8 }
function Step($n, $text) { W ""; W "  [$n/4]  $text" "Yellow"; W ("  " + ("-" * 70)) "DarkGray" }
function Ok($text) { W "    OK   $text" "Green" }
function Info($text) { W "         $text" }
function Warn($text) { W "    !    $text" "DarkYellow" }
function Pause-End {
    if ($SinPausa) { return }
    W ""
    Write-Host "  Pulsa cualquier tecla para cerrar..." -ForegroundColor DarkGray
    try { [void][Console]::ReadKey($true) } catch { Start-Sleep 5 }
}

function Download($url, $dest, $label) {
    $req = [Net.HttpWebRequest]::Create($url)
    $req.UserAgent = "Aura-Installer"
    $req.AllowAutoRedirect = $true
    $resp = $req.GetResponse()
    $total = $resp.ContentLength
    $in = $resp.GetResponseStream()
    $out = [IO.File]::Create($dest)
    try {
        $buf = New-Object byte[] 262144
        $read = 0L; $last = -1
        while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
            $out.Write($buf, 0, $n); $read += $n
            if ($total -gt 0) {
                $pct = [int]($read * 100 / $total)
                if ($pct -ne $last) {
                    $last = $pct
                    $bar = ("#" * [int]($pct / 4)).PadRight(25, ".")
                    Write-Host ("`r         {0} [{1}] {2,3}%  {3:N1} / {4:N1} MB " -f $label, $bar, $pct, ($read / 1MB), ($total / 1MB)) -NoNewline -ForegroundColor Cyan
                }
            }
        }
    } finally { $out.Close(); $in.Close(); $resp.Close() }
    Write-Host ""
    Add-Content -Path $Log -Value ("Descargado {0}: {1:N1} MB" -f $label, ($read / 1MB)) -Encoding UTF8
}

Set-Content -Path $Log -Value ("Instalador de Aura - " + (Get-Date)) -Encoding UTF8
$ok = $false
try {
    W ""
    W "     _                    " "DarkYellow"
    W "    / \  _   _ _ __ __ _  " "DarkYellow"
    W "   / _ \| | | | '__/ _`` | " "Yellow"
    W "  / ___ \ |_| | | | (_| | " "Yellow"
    W " /_/   \_\__,_|_|  \__,_| " "White"
    W ""
    W "  Instalador para Fire TV            github.com/$Repo" "White"
    W ""
    W "  Antes de empezar, en el Fire TV:" "Cyan"
    W "   1. Ajustes > Mi Fire TV > Opciones de desarrollador > Depuración ADB: ACTIVADA"
    W "      (si no ves 'Opciones de desarrollador': Mi Fire TV > Información, pulsa 7 veces el nombre)"
    W "   2. El Fire TV y este PC tienen que estar en la misma red Wi-Fi."
    if ($SoloPermisos) { W "" ; W "  Modo: solo permisos (Aura ya está instalada)." "Cyan" }
    W ""
    if (-not $SinPausa) { Write-Host "  Pulsa Intro cuando esté listo..." -ForegroundColor White -NoNewline; [void](Read-Host) }

    # ---------- 1. ADB ----------
    Step 1 "Herramientas ADB"
    $adb = Join-Path $Work "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        $sys = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
        if ($sys) { $adb = $sys }
        else {
            Info "Descargando las herramientas oficiales de Google..."
            $zip = Join-Path $Work "platform-tools.zip"
            Download "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" $zip "ADB"
            Info "Descomprimiendo..."
            Expand-Archive $zip -DestinationPath $Work -Force
            Remove-Item $zip -Force
        }
    }
    if (-not (Test-Path $adb)) { throw "No se pudo preparar ADB." }
    Ok "ADB listo"
    function Adb { $ErrorActionPreference = "Continue"; & $adb @args 2>&1 | ForEach-Object { "$_" } }
    Adb start-server | Out-Null

    # ---------- 2. Fire TV ----------
    Step 2 "Buscando tu Fire TV en la red"
    if (-not $Ip) {
        $found = @()
        $nets = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)' -and $_.AddressState -eq 'Preferred' } |
            ForEach-Object { ($_.IPAddress -split '\.')[0..2] -join '.' } | Sort-Object -Unique
        foreach ($base in $nets) {
            Write-Host "         Revisando $base.0/24 ..." -NoNewline
            $tasks = @()
            foreach ($i in 1..254) {
                $c = New-Object Net.Sockets.TcpClient
                $tasks += ,@("$base.$i", $c, $c.ConnectAsync("$base.$i", 5555))
            }
            Start-Sleep -Milliseconds 1500
            $hits = @()
            foreach ($t in $tasks) {
                if ($t[2].Status -eq 'RanToCompletion' -and $t[1].Connected) { $hits += $t[0] }
                $t[1].Close()
            }
            if ($hits) { Write-Host (" encontrado: " + ($hits -join ", ")) -ForegroundColor Green } else { Write-Host " nada" -ForegroundColor DarkGray }
            $found += $hits
        }
        $found = @($found | Sort-Object -Unique)
        if ($found.Count -eq 1) { $Ip = $found[0] }
        elseif ($found.Count -gt 1) {
            Info "Hay varios dispositivos con ADB activo:"
            for ($i = 0; $i -lt $found.Count; $i++) { Info ("   [{0}] {1}" -f ($i + 1), $found[$i]) }
            $sel = Read-Host "         Escribe el número de tu Fire TV"
            $Ip = $found[[int]$sel - 1]
        } else {
            Warn "No se encontró automáticamente (¿Depuración ADB activada? ¿misma Wi-Fi?)."
            Info "Mira la IP en el Fire TV: Ajustes > Mi Fire TV > Información > Red."
            $Ip = Read-Host "         Escribe la IP del Fire TV (ejemplo 192.168.1.50)"
        }
    }
    $Ip = "$Ip".Trim()
    if ($Ip -notmatch '^\d{1,3}(\.\d{1,3}){3}(:\d+)?$') { throw "La IP '$Ip' no es válida." }
    $Dev = if ($Ip -match ':') { $Ip } else { "$($Ip):5555" }

    Info "Conectando con $Dev ..."
    $state = ""; $asked = $false
    for ($i = 0; $i -lt 40; $i++) {
        Adb connect $Dev | Out-Null
        $state = (Adb -s $Dev get-state) -join " "
        if ($state -match '^device') { break }
        if (-not $asked) {
            $asked = $true
            W ""
            W "    >>   MIRA LA TV: acepta 'Permitir depuración' (marca 'Permitir siempre')." "Cyan"
            W ""
        }
        Write-Host "." -NoNewline -ForegroundColor DarkGray
        Start-Sleep 3
    }
    if ($asked) { Write-Host "" }
    if ($state -notmatch '^device') { throw "No se pudo conectar con el Fire TV ($state). Revisa que la Depuración ADB esté activada y que hayas aceptado el aviso en la TV." }
    $model = (Adb -s $Dev shell getprop ro.product.model) -join ""
    Ok "Conectado a $model ($Dev)"

    # ---------- 3. Aura ----------
    if ($SoloPermisos) {
        Step 3 "Instalar Aura (se omite: solo permisos)"
        $has = (Adb -s $Dev shell pm path $Pkg) -join ""
        if ($has -notmatch 'package:') { throw "Aura no está instalada en la TV. Ejecuta el instalador sin -SoloPermisos." }
        Ok "Aura ya está instalada"
    } else {
        Step 3 "Instalando la última versión de Aura"
        $apk = Join-Path $Work "Aura.apk"
        Download "https://github.com/$Repo/releases/latest/download/Aura.apk" $apk "Aura"
        Info "Instalando en la TV (puede tardar un minuto)..."
        $r = (Adb -s $Dev install -r $apk) -join " "
        if ($r -notmatch 'Success') {
            if ($r -match 'UPDATE_INCOMPATIBLE') { throw "En la TV hay una Aura firmada con otra clave. Desinstálala en la TV y vuelve a ejecutar el instalador." }
            throw "La instalación falló: $r"
        }
        Ok "Aura instalada"
    }

    # ---------- 4. Permisos ----------
    Step 4 "Permisos (solo se pueden conceder desde el PC)"
    $cmds = @(
        @("pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS", "Ajustes del sistema (bienvenida y salvapantallas)"),
        @("appops set $Pkg WRITE_SETTINGS allow", "Sonidos y tiempo del salvapantallas"),
        @("appops set $Pkg SYSTEM_ALERT_WINDOW allow", "Abrir Aura al encender la TV"),
        @("appops set $Pkg REQUEST_INSTALL_PACKAGES allow", "Instalar apps y actualizaciones"),
        @("cmd notification allow_dnd $Pkg", "No interrumpir"),
        @("settings put secure enabled_accessibility_services $Pkg/$Pkg.HomeWatcherService", "Botón Inicio y mando web"),
        @("settings put secure accessibility_enabled 1", "Accesibilidad activada")
    )
    $fails = 0
    foreach ($c in $cmds) {
        $out = (Adb -s $Dev shell $c[0]) -join " "
        if ($out -match 'Exception|Error|not found|Unknown') { Warn "$($c[1]): $out"; $fails++ } else { Ok $c[1] }
    }
    Adb -s $Dev shell "am start -n $Pkg/.MainActivity" | Out-Null

    W ""
    if ($fails -eq 0) { W "  ======  LISTO  ======" "Green" } else { W "  Terminado con $fails aviso(s) (ver arriba)." "DarkYellow" }
    W "  Aura está abierta en la TV: sigue la bienvenida con el mando." "White"
    W "  Para controlar la TV desde el móvil, pulsa el botón Menú del mando y escanea el QR."
    W "  Ya puedes desactivar la Depuración ADB en el Fire TV si quieres."
    $ok = $true
}
catch {
    W ""
    W "  ======  NO SE PUDO COMPLETAR  ======" "Red"
    W ("  " + $_.Exception.Message) "Red"
    W ""
    W "  Registro completo: $Log" "DarkGray"
}
finally {
    Pause-End
}
if (-not $ok) { exit 2 }
