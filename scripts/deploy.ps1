# Compila Aura, la instala en el Fire TV por ADB, concede sus permisos y la abre.
#   .\scripts\deploy.ps1                    -> compila (debug) + instala
#   .\scripts\deploy.ps1 -Release           -> compila la version firmada (como la de GitHub) + instala
#   .\scripts\deploy.ps1 -SkipBuild         -> solo instala el ultimo APK compilado
#   .\scripts\deploy.ps1 -Device 192.168.1.50:5555
# La IP por defecto se puede fijar con la variable de entorno AURA_DEVICE.
param(
    [switch]$SkipBuild,
    [switch]$Release,
    [string]$Device = $(if ($env:AURA_DEVICE) { $env:AURA_DEVICE } else { "10.0.0.4:5555" })
)

$ErrorActionPreference = "Stop"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$variant = if ($Release) { "release" } else { "debug" }
$task = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
$apk = "app\build\outputs\apk\$variant\app-$variant.apk"

# Si el APK esta abierto en otro programa (p. ej. LDPlayer) Gradle no puede reemplazarlo: se compila en ..\aura-build
$extra = @()
$locked = $false
if (Test-Path $apk) { try { [IO.File]::Open((Resolve-Path $apk), 'Open', 'ReadWrite', 'None').Close() } catch { $locked = $true } }
if ($locked) {
    "El APK esta abierto en otro programa: se compila en ..\aura-build"
    $extra = @("-I", "tools\alt-build-dir.gradle")
    $apk = "..\aura-build\app\outputs\apk\$variant\app-$variant.apk"
}

if (-not $SkipBuild) {
    Remove-Item hs_err_pid*.log, replay_pid*.log -ErrorAction SilentlyContinue
    # JVM pequena: el PC de desarrollo tiene poca memoria virtual
    $ErrorActionPreference = "Continue"
    & .\gradlew.bat @extra $task --no-daemon --console=plain -q `
        "-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=448m -Xss1m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m" 2>&1 | Out-Host
    $ErrorActionPreference = "Stop"
    if ($LASTEXITCODE -ne 0) { throw "La compilacion fallo (si dice 'Could not reserve enough space for object heap', libera memoria)" }
}
if (-not (Test-Path $apk)) { throw "No existe $apk. Compila primero." }

adb connect $Device | Out-Null
adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { throw "No se pudo instalar (ADB autorizado en la TV? Misma firma que la version instalada?)" }
# Permisos que solo se pueden dar por ADB (ver README, "Permisos")
adb -s $Device shell "pm grant com.david.aura android.permission.WRITE_SECURE_SETTINGS; appops set com.david.aura WRITE_SETTINGS allow; appops set com.david.aura SYSTEM_ALERT_WINDOW allow; appops set com.david.aura REQUEST_INSTALL_PACKAGES allow; cmd notification allow_dnd com.david.aura"
# Fire OS desactiva el servicio de accesibilidad al actualizar una app instalada a mano: se reactiva
adb -s $Device shell "settings put secure enabled_accessibility_services com.david.aura/com.david.aura.HomeWatcherService; settings put secure accessibility_enabled 1; am start -n com.david.aura/.MainActivity"
"Aura instalada y activa ($variant)."

# La landing local siempre ofrece el ultimo APK compilado
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot "update-landing.ps1") -Apk (Resolve-Path $apk) }
