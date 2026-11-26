# Builds MapSkin.apk on Windows with the Android SDK (no Gradle): javac -> d8 -> aapt2 -> zipalign -> apksigner
# Needs a JDK (javac on PATH) and the Android SDK with build-tools + one platform installed.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } | Select-Object -Last 1
$platform = Get-ChildItem "$sdk\platforms" | Sort-Object Name | Select-Object -Last 1
$jar = Join-Path $platform.FullName 'android.jar'
$tools = $bt.FullName
Write-Host "build-tools $($bt.Name), $($platform.Name)"

function Invoke-Tool {
    param([string]$exe)
    $ErrorActionPreference = 'Continue'   # tools print notes on stderr; only the exit code means failure
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" }
}

if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory -Force build\stubs, build\classes, build\dex | Out-Null

$stubs = Get-ChildItem -Recurse stubs -Filter *.java | ForEach-Object FullName
$srcs = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName
Invoke-Tool javac -nowarn -encoding UTF-8 --release 8 -classpath $jar -d build\stubs @stubs 2>$null
Invoke-Tool javac -nowarn -encoding UTF-8 --release 8 -classpath "$jar;build\stubs" -d build\classes @srcs

$classes = Get-ChildItem -Recurse build\classes -Filter *.class | ForEach-Object FullName
Invoke-Tool "$tools\d8.bat" --min-api 26 --lib $jar --output build\dex @classes

Invoke-Tool "$tools\aapt2.exe" compile --dir res -o build\res.zip
Invoke-Tool "$tools\aapt2.exe" link -o build\unsigned.apk --manifest AndroidManifest.xml -I $jar -A assets build\res.zip
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$PWD\build\unsigned.apk", 'Update')
try { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$PWD\build\dex\classes.dex", 'classes.dex') | Out-Null }
finally { $zip.Dispose() }
Invoke-Tool "$tools\zipalign.exe" -f -p 4 build\unsigned.apk build\aligned.apk

if (-not (Test-Path mapskin.keystore)) {
    Invoke-Tool keytool -genkeypair -keystore mapskin.keystore -alias mapskin -keyalg RSA -keysize 2048 -validity 10000 -storepass mapskin -keypass mapskin -dname "CN=MapSkin"
}
Invoke-Tool "$tools\apksigner.bat" sign --ks mapskin.keystore --ks-pass pass:mapskin --key-pass pass:mapskin --out build\MapSkin.apk build\aligned.apk
Invoke-Tool "$tools\apksigner.bat" verify build\MapSkin.apk
Write-Host "Built build\MapSkin.apk"
