$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build the debug APK first.' }
$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$buildText = Get-Content -LiteralPath "$projectRoot/app/build.gradle" -Raw
$version = [regex]::Match($buildText, "versionName '([0-9.]+)'").Groups[1].Value
if (-not $version) { throw 'App version was not found.' }
Copy-Item -LiteralPath $apk -Destination "$dist/Storm-Sea.apk" -Force
Copy-Item -LiteralPath $apk -Destination "$dist/Storm-Sea-v$version.apk" -Force
if (Test-Path -LiteralPath "$projectRoot/qa/device/sea.png") {
    Copy-Item -LiteralPath "$projectRoot/qa/device/sea.png" -Destination "$dist/Storm-Sea-preview.png" -Force
    Copy-Item -LiteralPath "$projectRoot/qa/device/sea.png" -Destination "$dist/Storm-Sea-v$version-preview.png" -Force
}
if (Test-Path -LiteralPath "$projectRoot/qa/revision/v2-wide-dry.png") {
    Copy-Item -LiteralPath "$projectRoot/qa/revision/v2-wide-dry.png" -Destination "$dist/Storm-Sea-v$version-wide.png" -Force
}
$hash = Get-FileHash -LiteralPath "$dist/Storm-Sea.apk" -Algorithm SHA256
"$($hash.Hash.ToLowerInvariant())  Storm-Sea.apk" | Set-Content -LiteralPath "$dist/SHA256.txt" -Encoding ascii

# Export source files only. Leave out machine paths, caches, keys, and the emulator.
$files = @('README.md', '.gitignore', '.gitattributes', 'build.gradle', 'settings.gradle', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'app/build.gradle', 'qa/VERIFICATION.md', 'qa/device-tests.txt',
    'qa/wave-checks-v2.cjs', 'qa/wave-checks-v2.json', 'qa/wave-checks-v2.md', 'qa/fixed-view-tests-v2.txt')
foreach ($folder in @('app/src', 'gradle', 'scripts')) {
    $files += Get-ChildItem -LiteralPath "$projectRoot/$folder" -Recurse -File | ForEach-Object {
        [IO.Path]::GetRelativePath($projectRoot, $_.FullName).Replace('\', '/')
    }
}
$zipPath = Join-Path $dist 'Storm-Sea-source.zip'
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::Create)
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($files | Sort-Object -Unique)) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, "$projectRoot/$file", "StormSea/$file", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $archive.Dispose()
    $stream.Dispose()
}
Copy-Item -LiteralPath $zipPath -Destination "$dist/Storm-Sea-v$version-source.zip" -Force
Get-ChildItem -LiteralPath $dist -File | Select-Object Name, Length
