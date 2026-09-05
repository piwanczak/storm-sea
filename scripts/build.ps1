param(
    [string[]]$Tasks = @(':app:assembleDebug'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$unityAndroid = 'C:/Program Files/Unity/Hub/Editor/6000.3.17f1/Editor/Data/PlaybackEngines/AndroidPlayer'

if (-not $env:JAVA_HOME) {
    $javaCandidates = @(
        "$unityAndroid/OpenJDK",
        'C:/Program Files/Android/Android Studio/jbr'
    )
    $env:JAVA_HOME = $javaCandidates | Where-Object {
        Test-Path -LiteralPath "$_/bin/java.exe"
    } | Select-Object -First 1
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
    throw 'Set JAVA_HOME to an installed JDK 17 or later.'
}

$localProperties = Join-Path $projectRoot 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties)) {
    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "$unityAndroid/SDK",
        "$env:LOCALAPPDATA/Android/Sdk"
    )
    $sdkPath = $sdkCandidates | Where-Object {
        $_ -and (Test-Path -LiteralPath "$_/platforms/android-35/android.jar")
    } | Select-Object -First 1
    if (-not $sdkPath) {
        throw 'Install Android SDK platform 35 and build tools 36.0.0. Set ANDROID_HOME to that SDK.'
    }
    'sdk.dir=' + $sdkPath.Replace('\', '/').Replace(':', '\:') | Set-Content -LiteralPath $localProperties -Encoding utf8
}

# Keep build caches and the debug signing key inside this project.
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.android-user-home'
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME -Force | Out-Null
$readOnlyCache = Join-Path $env:USERPROFILE '.gradle/caches'
if (Test-Path -LiteralPath "$readOnlyCache/modules-2") {
    $env:GRADLE_RO_DEP_CACHE = $readOnlyCache
}

$gradleArguments = @('-p', $projectRoot, '--no-daemon', '--console=plain') + $Tasks
if ($Offline) { $gradleArguments += '--offline' }

# Unity includes a complete Gradle runtime. Use it when present to avoid a download.
$localGradle = "$unityAndroid/Tools/gradle/lib/gradle-launcher-9.1.0.jar"
if (Test-Path -LiteralPath $localGradle) {
    & "$env:JAVA_HOME/bin/java.exe" -classpath $localGradle org.gradle.launcher.GradleMain @gradleArguments
} else {
    & "$projectRoot/gradlew.bat" @gradleArguments
}
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE."
}
