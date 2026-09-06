param(
    [string]$Alias = "woah-upload"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$androidRoot = Join-Path $repoRoot "mobile/app/android"
$keystorePath = Join-Path $androidRoot "woah-upload-key.jks"
$propertiesPath = Join-Path $androidRoot "key.properties"

if ((Test-Path $keystorePath) -or (Test-Path $propertiesPath)) {
    throw "Release signing files already exist. Back them up and reuse them instead of generating a new upload key."
}

$keytool = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME "bin/keytool.exe"
} else {
    "keytool"
}

if (($keytool -ne "keytool") -and -not (Test-Path $keytool)) {
    throw "keytool.exe was not found under JAVA_HOME: $env:JAVA_HOME"
}

$passwordBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

& $keytool `
    -genkeypair `
    -v `
    -keystore $keystorePath `
    -storetype JKS `
    -storepass $password `
    -keypass $password `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname "CN=Woah Upload, OU=Release, O=Gaoge"

if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE"
}

$properties = @(
    "storeFile=woah-upload-key.jks"
    "storePassword=$password"
    "keyAlias=$Alias"
    "keyPassword=$password"
) -join "`n"

[IO.File]::WriteAllText(
    $propertiesPath,
    $properties + "`n",
    [Text.UTF8Encoding]::new($false)
)

Write-Host "Android upload key generated."
Write-Host "Keystore: $keystorePath"
Write-Host "Properties: $propertiesPath"
Write-Host "Back up both files securely before publishing. Losing this upload key complicates future updates."
