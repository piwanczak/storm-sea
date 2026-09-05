param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Build) { & "$PSScriptRoot/build.ps1" -Tasks @(':app:assembleDebug', ':app:assembleDebugAndroidTest') }
$qa = Join-Path $projectRoot 'qa'
New-Item -ItemType Directory -Path $qa -Force | Out-Null
& adb -s $Serial install -r "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'App install failed.' }
& adb -s $Serial install -r "$projectRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ($LASTEXITCODE -ne 0) { throw 'Test install failed.' }
$result = & adb -s $Serial shell am instrument -w com.stormsea.app.test/com.stormsea.app.StormInstrumentation
$result | Set-Content -LiteralPath "$qa/device-tests.txt" -Encoding utf8
$result
New-Item -ItemType Directory -Path "$qa/device" -Force | Out-Null
foreach ($name in @('controls.png', 'animated.png', 'paused.png', 'paused-check.png', 'sea.png', 'high-view.png')) {
    & adb -s $Serial pull "/sdcard/Android/data/com.stormsea.app/files/qa/$name" "$qa/device/$name"
}
if (($result -join "`n") -notmatch 'PASS: all native checks') { throw 'Device checks failed. Read qa/device-tests.txt.' }
& adb -s $Serial shell am start -n com.stormsea.app/.MainActivity
