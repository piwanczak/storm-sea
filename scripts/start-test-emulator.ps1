param([string]$SdkRoot)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot '.emulator'
$serial = 'emulator-5586'
$sessionFile = Join-Path $testRoot 'session.json'
$avdConfig = Join-Path $testRoot 'avd/StormSea_Test.avd/config.ini'

if (-not (Test-Path -LiteralPath $avdConfig)) {
    throw 'The existing StormSea_Test AVD is missing. This script does not create a device.'
}

if (-not $SdkRoot) {
    $sdkCandidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME
    )
    $SdkRoot = $sdkCandidates | Where-Object {
        $_ -and (Test-Path -LiteralPath "$_/emulator/emulator.exe") -and
        (Test-Path -LiteralPath "$_/system-images/android-36/google_apis_playstore_ps16k/x86_64")
    } | Select-Object -First 1
}
if (-not $SdkRoot -or -not (Test-Path -LiteralPath "$SdkRoot/emulator/emulator.exe")) {
    throw 'Set -SdkRoot to the SDK that contains the emulator and the API 36 system image.'
}

$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$devices = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the ADB device list.' }
if ($devices -match '^emulator-5586\s') {
    $deviceName = (& $adb -s $serial shell getprop ro.boot.qemu.avd_name 2>$null) -join ''
    if ($deviceName.Trim() -ne 'StormSea_Test') {
        throw 'Port 5586 is already in use or its device is still offline. No device was changed.'
    }
    Write-Output "$serial is already running as StormSea_Test."
    if (Test-Path -LiteralPath $sessionFile) { Get-Content -LiteralPath $sessionFile }
    return
}

$launchEnvironment = @{
    ANDROID_AVD_HOME = Join-Path $testRoot 'avd'
    ANDROID_USER_HOME = Join-Path $testRoot 'android-user'
    ANDROID_EMULATOR_HOME = Join-Path $testRoot 'android-user'
    ANDROID_HOME = $SdkRoot
    ANDROID_SDK_ROOT = $SdkRoot
    TEMP = Join-Path $testRoot 'tmp'
    TMP = Join-Path $testRoot 'tmp'
}
$savedEnvironment = @{}
try {
    foreach ($name in $launchEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $launchEnvironment[$name], 'Process')
    }
    # Keep the flags from the existing isolated test device.
    $arguments = @(
        '-avd', 'StormSea_Test', '-port', '5586',
        '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot',
        '-skip-adb-auth', '-no-direct-adb', '-adb-path', ('"' + $adb + '"'),
        '-gpu', 'host', '-memory', '3072', '-cores', '4',
        '-accel', 'on', '-no-metrics', '-feature', '-Vulkan'
    )
    $launch = Start-Process -FilePath (Join-Path $SdkRoot 'emulator/emulator.exe') `
        -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot 'emulator.log') `
        -RedirectStandardError (Join-Path $testRoot 'emulator-error.log')
    $session = [ordered]@{
        Serial = $serial
        LauncherPid = $launch.Id
        StartedAt = (Get-Date).ToString('o')
        BootCompleted = $false
    }
    $session | ConvertTo-Json | Set-Content -LiteralPath $sessionFile -Encoding utf8
    Write-Output "Started $serial. Launcher PID: $($launch.Id)."
    Write-Output "Check boot: adb -s $serial shell getprop sys.boot_completed"
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
