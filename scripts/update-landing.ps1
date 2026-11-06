# Copia el ultimo APK a la landing local (landing/downloads) y regenera landing/release.js.
#   .\scripts\update-landing.ps1                       -> usa el APK compilado mas reciente
#   .\scripts\update-landing.ps1 -Apk ruta\al\app.apk  -> usa ese APK
# deploy.ps1 lo llama solo despues de compilar. En GitHub, la landing la publica release.yml.
param([string]$Apk = "")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

if (-not $Apk) {
    $candidates = @(
        "app\build\outputs\apk\release\app-release.apk",
        "app\build\outputs\apk\debug\app-debug.apk",
        "..\aura-build\app\outputs\apk\release\app-release.apk",
        "..\aura-build\app\outputs\apk\debug\app-debug.apk"
    ) | ForEach-Object { Join-Path $root $_ } | Where-Object { Test-Path $_ }
    if (-not $candidates) { throw "No hay ningun APK compilado. Compila primero (scripts\deploy.ps1)." }
    $Apk = ($candidates | Get-Item | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

$name = ((Get-Content (Join-Path $root "version.properties")) -match "^versionName=") -replace "versionName=", ""
$dl = Join-Path $root "landing\downloads"
New-Item -ItemType Directory -Force $dl | Out-Null
Copy-Item $Apk (Join-Path $dl "Aura-$name.apk") -Force
Copy-Item $Apk (Join-Path $dl "Aura-latest.apk") -Force

python -I (Join-Path $PSScriptRoot "release_info.py") --apk (Join-Path $dl "Aura-$name.apk")
if ($LASTEXITCODE -ne 0) { throw "release_info.py fallo" }
