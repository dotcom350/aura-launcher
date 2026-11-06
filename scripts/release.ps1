# Prepara una version nueva de Aura: sube la version, cierra el CHANGELOG, hace commit y etiqueta vX.Y.
# Al subir la etiqueta a GitHub, .github/workflows/release.yml compila el APK firmado,
# crea la Release con el APK y publica la landing.
#
#   .\scripts\release.ps1 -Version 1.8            -> commit + etiqueta en local (revisa y luego sube)
#   .\scripts\release.ps1 -Version 1.8 -Push      -> ademas sube rama y etiqueta (GitHub publica la version)
#   .\scripts\release.ps1 -Version 1.8 -Test      -> antes compila la release y la instala en la TV para probarla
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$Push,
    [switch]$Test
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

if ($Version -notmatch '^\d+\.\d+(\.\d+)?$') { Fail "La version debe ser tipo 1.8 o 1.8.1" }
if (git status --porcelain) { Fail "Hay cambios sin commit. Haz commit (o descartalos) antes de publicar." }
if (git tag --list "v$Version") { Fail "La etiqueta v$Version ya existe." }

# --- version.properties ---
$propsPath = Join-Path $root "version.properties"
$props = Get-Content $propsPath
function Prop($key) { (($props | Where-Object { $_ -match "^$key=" } | Select-Object -First 1) -replace "^$key=", '').Trim() }
$oldName = Prop "versionName"
$oldCode = [int](Prop "versionCode")
if ([version]$Version -le [version]$oldName) { Fail "La version nueva ($Version) debe ser mayor que la actual ($oldName)." }
$newCode = $oldCode + 1

# --- CHANGELOG.md: "## [Sin publicar]" pasa a ser "## [X] - fecha" ---
$clPath = Join-Path $root "CHANGELOG.md"
$cl = [IO.File]::ReadAllText($clPath)
$m = [regex]::Match($cl, '(?ms)^## \[Sin publicar\][^\n]*\n(.*?)(?=^## \[|\z)')
if (-not $m.Success -or -not $m.Groups[1].Value.Trim()) {
    Fail "Escribe las novedades en CHANGELOG.md, en la seccion '## [Sin publicar]', antes de publicar."
}
$date = Get-Date -Format "yyyy-MM-dd"
$cl = $cl.Substring(0, $m.Index) + "## [Sin publicar]`n`n## [$Version] - $date`n" + $m.Groups[1].Value + $cl.Substring($m.Index + $m.Length)

$utf8 = New-Object Text.UTF8Encoding $false
$props = $props -replace '^versionName=.*', "versionName=$Version" -replace '^versionCode=.*', "versionCode=$newCode"
[IO.File]::WriteAllLines($propsPath, $props, $utf8)
[IO.File]::WriteAllText($clPath, $cl, $utf8)

if ($Test) {
    # Compila la release ya con la version nueva y la instala en la TV; si falla, se deshace todo
    try { & (Join-Path $PSScriptRoot "deploy.ps1") -Release }
    catch { git checkout -- version.properties CHANGELOG.md; Fail "La prueba fallo: $($_.Exception.Message)" }
    Set-Location $root
}

git add version.properties CHANGELOG.md
git commit -m "Aura $Version"
git tag -a "v$Version" -m "Aura $Version"
Write-Host "Version $Version (code $newCode) lista: commit y etiqueta v$Version creados." -ForegroundColor Green

if ($Push) {
    git push origin HEAD
    git push origin "v$Version"
    Write-Host "Subido. GitHub Actions compila y publica la Release en unos minutos (pestana Actions)." -ForegroundColor Green
} else {
    Write-Host "Para publicarla:  git push origin HEAD; git push origin v$Version"
    Write-Host "Para deshacerla:  git tag -d v$Version; git reset --hard HEAD~1"
}
