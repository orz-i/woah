param(
    [string]$ExpectedApplicationId = "art.gaoge.dance",
    [string]$ExpectedVersionName = "0.1.0",
    [int]$ExpectedVersionCode = 1
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$appRoot = Join-Path $repoRoot "mobile/app"
$androidRoot = Join-Path $appRoot "android"
$aabPath = Join-Path $appRoot "build/app/outputs/bundle/release/app-release.aab"
$apkPath = Join-Path $appRoot "build/app/outputs/apk/release/app-release.apk"
$metadataPath = Join-Path $appRoot "build/app/outputs/apk/release/output-metadata.json"
$localPropertiesPath = Join-Path $androidRoot "local.properties"

foreach ($path in @($aabPath, $apkPath, $metadataPath, $localPropertiesPath)) {
    if (-not (Test-Path $path)) {
        throw "Required release artifact/configuration does not exist: $path"
    }
}

$metadata = Get-Content -Raw $metadataPath | ConvertFrom-Json
$element = $metadata.elements | Select-Object -First 1
if ($metadata.applicationId -ne $ExpectedApplicationId) {
    throw "Unexpected applicationId: $($metadata.applicationId)"
}
if ($element.versionName -ne $ExpectedVersionName) {
    throw "Unexpected versionName: $($element.versionName)"
}
if ([int]$element.versionCode -ne $ExpectedVersionCode) {
    throw "Unexpected versionCode: $($element.versionCode)"
}

$jarsigner = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME "bin/jarsigner.exe"
} else {
    "jarsigner"
}

$jarVerifyOutput = & $jarsigner -verify $aabPath 2>&1
if ($LASTEXITCODE -ne 0) {
    $tail = ($jarVerifyOutput | Select-Object -Last 20) -join "`n"
    throw "AAB JAR signature verification failed with exit code $LASTEXITCODE`n$tail"
}
Write-Host "AAB JAR signature is present and readable."

$sdkDirLine = Get-Content $localPropertiesPath | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
if (-not $sdkDirLine) {
    throw "sdk.dir is missing from local.properties"
}
$sdkDir = $sdkDirLine.Substring("sdk.dir=".Length).Replace("\\\\", "\")
$buildToolsRoot = Join-Path $sdkDir "build-tools"
$buildTools = Get-ChildItem $buildToolsRoot -Directory | Sort-Object {
    try { [version]$_.Name } catch { [version]"0.0" }
} -Descending | Select-Object -First 1
if (-not $buildTools) {
    throw "No Android build-tools installation found under $buildToolsRoot"
}
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
if (-not (Test-Path $apksigner)) {
    throw "apksigner.bat not found: $apksigner"
}

& $apksigner verify --verbose --print-certs $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed with exit code $LASTEXITCODE"
}

Write-Host "Release metadata verified: $ExpectedApplicationId $ExpectedVersionName ($ExpectedVersionCode)"
Write-Host "AAB: $aabPath"
Write-Host "APK: $apkPath"
