param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidatePattern('^[a-z0-9-]+$')][string]$Prefix = 'v2'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot 'qa/revision'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
# The app and its test APK must already be installed on this test device.
$result = & adb -s $Serial shell am instrument -w -e capturePrefix $Prefix com.stormsea.app.test/com.stormsea.app.StormInstrumentation
$result | Set-Content -LiteralPath "$projectRoot/qa/fixed-view-tests-$Prefix.txt" -Encoding utf8
$result
if (($result -join "`n") -notmatch 'PASS: fixed camera captures') { throw 'Fixed camera capture failed.' }
foreach ($view in @('low-dry', 'low-rain', 'wide-dry', 'wide-rain', 'wide-cross', 'rain-motion')) {
    & adb -s $Serial pull "/sdcard/Android/data/com.stormsea.app/files/qa/$Prefix-$view.png" "$destination/$Prefix-$view.png"
    if ($LASTEXITCODE -ne 0) { throw "Could not copy $view." }
}
