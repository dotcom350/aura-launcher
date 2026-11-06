# Fire TV (AFTKM, Fire OS 8) - limpieza de publicidad y bloatware
# Uso:   .\debloat-firetv.ps1            -> aplica
#        .\debloat-firetv.ps1 -Revert    -> deshace todo
param([switch]$Revert, [string]$Device = "10.0.0.4:5555")

$adb = "C:\platform-tools\adb.exe"
adb connect $Device | Out-Null

# Paquetes de Amazon desactivados (pm disable-user, reversible)
$packages = @(
  # Publicidad / recomendaciones / compras
  "com.amazon.ftvads.deeplinking",      # anuncios del inicio
  "com.amazon.hybridadidservice",       # ID publicitario
  "com.amazon.kso.blackbird",           # "special offers" (anuncios)
  "com.amazon.media.recommendations",   # filas de recomendaciones
  "com.amazon.sneakpeek",               # vista previa automatica del banner
  "com.amazon.shoptv.client",           # compras en TV
  "com.amazon.shoptv.firetv.client",
  "com.amazon.tv.acr",                  # reconocimiento de lo que ves (rastreo)
  "com.amazon.tv.ftvambient",           # Ambient Experience
  "com.amazon.minitv.android.app",      # Amazon miniTV
  "com.amazon.gamehub",                 # Luna / juegos
  "com.amazon.tv.livetv",               # pestana Live TV
  "com.amazon.bueller.photos",          # Amazon Photos
  "com.amazon.bueller.music",           # Amazon Music
  "com.amazon.tv.earlyaccess",
  "com.amazon.tv.releasenotes",
  "com.amazon.storm.lightning.tutorial",
  "com.amazon.tmm.tutorial",
  # Telemetria / registros
  "com.amazon.device.crashmanager",
  "com.amazon.device.logmanager",
  "com.amazon.device.telemetry.emitter",
  "com.amazon.wirelessmetrics.service",
  "com.amazon.perfcollection",
  "com.amazon.dp.logger",
  "com.amazon.neo.minerva.logcat",
  # Apps de Amazon que no se usan (salian en el launcher)
  "com.amazon.spiderpork",              # Amazon Luna
  "com.amazon.hedwig",                  # News
  "com.amazon.smarthomemapviewapp",     # Alexa Smart Home
  "com.amazon.tahoe",                   # Amazon Kids
  "com.amazon.whasettings",             # AmazonSettings (audio multiroom)
  "com.amazon.firetv.troubleshooting",  # FireTV Troubleshooting
  "com.amazon.firebat"                  # Prime Video
  # Las apps de terceros que no se usan NO se desactivan: Aura las oculta (config "hidden")
  # para que sigan apareciendo en "Todas las apps" del panel y se puedan volver a anadir.
)

# Ajustes de privacidad / anuncios: clave -> (valor aplicado, valor original)
$secure = [ordered]@{
  "amz_limit_ad_tracking"                    = @("1","0")
  "limit_ad_tracking"                        = @("1","0")
  "non_amz_limit_ad_tracking"                = @("1","0")
  "advertisingIdApp:interestBasedAds:value"  = @("0","1")
  "usage_metrics_marketing_enabled"          = @("0","1")
  "pact_usage_collection_enabled"            = @("0","1")
  "settingsApp:appUsageData:value"           = @("0","1")
  "settingsApp:deviceUsageData:value"        = @("0","1")
}

if (-not $Revert) {
  foreach ($p in $packages) {
    adb -s $Device shell pm unsuspend --user 0 $p 2>$null | Out-Null
    $r = adb -s $Device shell pm disable-user --user 0 $p 2>&1
    "{0,-40} {1}" -f $p, ($r | Select-Object -Last 1)
  }
  foreach ($k in $secure.Keys) { adb -s $Device shell settings put secure $k $secure[$k][0] }
  adb -s $Device shell settings put secure "advertisingIdApp:interestBasedAds:syncPending" true
  adb -s $Device shell settings put secure "settingsApp:appUsageData:syncPending" true
  adb -s $Device shell settings put secure "settingsApp:deviceUsageData:syncPending" true
  # DNS privado de AdGuard (bloquea anuncios y rastreadores a nivel de red)
  adb -s $Device shell settings put global private_dns_mode hostname
  adb -s $Device shell settings put global private_dns_specifier dns.adguard-dns.com
  "Ajustes de privacidad y DNS aplicados."
} else {
  foreach ($p in $packages) {
    $r = adb -s $Device shell pm enable --user 0 $p 2>&1
    "{0,-40} {1}" -f $p, ($r | Select-Object -Last 1)
  }
  foreach ($k in $secure.Keys) { adb -s $Device shell settings put secure $k $secure[$k][1] }
  adb -s $Device shell settings put global private_dns_mode off
  adb -s $Device shell settings delete global private_dns_specifier
  "Todo revertido."
}
