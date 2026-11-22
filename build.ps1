# Build and sign CallGuard without Gradle or Android Studio.
#
#   .\build.ps1                    offline build (no INTERNET)
#   .\build.ps1 -Online            online build (INTERNET permission; lookup stays off by default)
#   .\build.ps1 -NoDb              do not package assets/phone.dat
#   .\build.ps1 -SelfTest          export the adb self-test receiver (debug builds only)
#   .\build.ps1 -VersionName 0.1.0 -VersionCode 1
#
# Requirements:
#   - JDK 17+ (JAVA_HOME, Android Studio's JBR, or javac on PATH)
#   - Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT) with build-tools and a platform
#
# Signing:
#   Set CG_KS_PASS, or store the password in Windows Credential Manager with
#   scripts\keystore-credential.ps1 (target CallGuard/Keystore). The keystore
#   defaults to .\callguard.keystore and is generated on first use.
param(
    [switch]$Online,
    [switch]$NoDb,
    [switch]$SelfTest,
    [string]$VersionName = '0.1.0',
    [int]$VersionCode = 1,
    [string]$Keystore = '',
    [string]$BuildTools = '',
    [string]$Platform = 'android-36'
)
if ($env:CG_ONLINE -eq '1') { $Online = $true }

$ErrorActionPreference = 'Continue'
Write-Host "[build] Online=$Online NoDb=$NoDb SelfTest=$SelfTest Version=$VersionName ($VersionCode)"

$proj = $PSScriptRoot
if (-not $proj) { $proj = Split-Path -Parent $MyInvocation.MyCommand.Path }
$proj = [IO.Path]::GetFullPath($proj)

function Invoke-Cmd([string]$Command) {
    & cmd.exe /c $Command
}

function Get-NumericVersion([string]$Name) {
    $m = [regex]::Match($Name, '\d+(\.\d+){0,3}')
    if ($m.Success) {
        try { return [version]$m.Value } catch { }
    }
    return [version]'0.0'
}

function Find-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
        'C:\Android\Sdk'
    )
    foreach ($c in $candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if (Test-Path (Join-Path $c 'build-tools')) { return [IO.Path]::GetFullPath($c) }
    }
    throw 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}

function Find-JavaHome {
    $candidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jre'
    )
    foreach ($c in $candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if (Test-Path (Join-Path $c 'bin\javac.exe')) { return [IO.Path]::GetFullPath($c) }
    }
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) {
        $bin = Split-Path $javac.Source -Parent
        if (Test-Path (Join-Path (Split-Path $bin -Parent) 'bin\java.exe')) {
            return (Split-Path $bin -Parent)
        }
    }
    throw 'JDK not found. Set JAVA_HOME or put javac on PATH.'
}

$sdk = Find-AndroidSdk
$jh = Find-JavaHome
$java = Join-Path $jh 'bin\java.exe'
$javac = Join-Path $jh 'bin\javac.exe'
$keytool = Join-Path $jh 'bin\keytool.exe'

$btRoot = Join-Path $sdk 'build-tools'
if ($BuildTools) {
    $bt = Join-Path $btRoot $BuildTools
    if (-not (Test-Path (Join-Path $bt 'aapt2.exe'))) {
        throw "build-tools $BuildTools not found under $btRoot"
    }
} else {
    $btDir = Get-ChildItem $btRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'aapt2.exe') } |
        Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1
    if (-not $btDir) { throw "No build-tools with aapt2.exe under $btRoot" }
    $bt = $btDir.FullName
}

$platDir = Join-Path (Join-Path $sdk 'platforms') $Platform
if (-not (Test-Path (Join-Path $platDir 'android.jar'))) {
    $platDir = Get-ChildItem (Join-Path $sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
        Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1
    if (-not $platDir) { throw "No Android platform with android.jar under $sdk\platforms" }
    $platDir = $platDir.FullName
}
$aj = Join-Path $platDir 'android.jar'

$aapt2 = Join-Path $bt 'aapt2.exe'
$zipalign = Join-Path $bt 'zipalign.exe'
$d8jar = Join-Path $bt 'lib\d8.jar'
$apksigner = Join-Path $bt 'lib\apksigner.jar'

# Signing password: environment first, then Windows Credential Manager if available.
$ksPass = $env:CG_KS_PASS
if (-not $ksPass) {
    $helper = Join-Path $proj 'scripts\keystore-credential.ps1'
    if (Test-Path $helper) {
        . $helper
        $ksPass = Get-CallGuardKeystorePassword
    }
}
if (-not $ksPass) {
    throw 'Keystore password not set. Set CG_KS_PASS, or store it with scripts\keystore-credential.ps1.'
}
$env:CG_KS_PASS = $ksPass
$ks = if ($Keystore) { [IO.Path]::GetFullPath($Keystore) } else { Join-Path $proj 'callguard.keystore' }

# Build with a DB only when the file is actually present.
if (-not $NoDb -and -not (Test-Path (Join-Path $proj 'assets\phone.dat'))) {
    Write-Host '[build] assets/phone.dat not found; building without a prefix database'
    $NoDb = $true
}

# Keep intermediate files inside the project.
$tmp = Join-Path $proj '.tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp

$out = Join-Path $proj 'build'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
$gen = Join-Path $out 'gen'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dex, $gen | Out-Null

# Build a variant manifest: version fields plus optional INTERNET injection.
# The source tree keeps a single offline manifest.
if ($VersionName -notmatch '^[0-9A-Za-z._-]+$') { throw "Invalid version name: $VersionName" }
$manifest = Join-Path $out 'AndroidManifest.build.xml'
$text = [IO.File]::ReadAllText((Join-Path $proj 'AndroidManifest.xml'), [Text.Encoding]::UTF8)
$text = [regex]::Replace($text, 'android:versionCode="\d+"', "android:versionCode=`"$VersionCode`"")
$text = [regex]::Replace($text, 'android:versionName="[^"]*"', "android:versionName=`"$VersionName`"")
$suffix = ''
if ($Online) {
    $suffix = '-online'
    $inject = '<uses-permission android:name="android.permission.INTERNET" />'
    $text = $text.Replace(
        '<uses-permission android:name="android.permission.READ_CONTACTS" />',
        "<uses-permission android:name=`"android.permission.READ_CONTACTS`" />`r`n    $inject")
}
if ($SelfTest) {
    $text = [regex]::Replace($text,
        '(android:name="\.SelfTestReceiver"\s+android:exported=")false(")',
        '${1}true${2}')
}
[IO.File]::WriteAllText($manifest, $text, (New-Object Text.UTF8Encoding($false)))
if ($NoDb) { $suffix += '-nodb' }
if ($SelfTest) { $suffix += '-selftest' }

$flat = Join-Path $out 'res.zip'
$resDir = Join-Path $proj 'res'
Invoke-Cmd "`"$aapt2`" compile --dir `"$resDir`" -o `"$flat`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

$unsigned = Join-Path $out "base$suffix-unsigned.apk"
$assetsDir = Join-Path $out 'assets'
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
if (-not $NoDb) {
    Copy-Item (Join-Path $proj 'assets\phone.dat') (Join-Path $assetsDir 'phone.dat') -Force
}
Invoke-Cmd "`"$aapt2`" link -o `"$unsigned`" -I `"$aj`" --manifest `"$manifest`" -A `"$assetsDir`" --min-sdk-version 33 --target-sdk-version 36 --java `"$gen`" `"$flat`" --auto-add-overlay 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$srcs = Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem $gen -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javacArgs = "-encoding UTF-8 -nowarn -source 17 -target 17 -classpath `"$aj`" -d `"$classes`" " + (($srcs | ForEach-Object { "`"$_`"" }) -join ' ')
Invoke-Cmd "`"$javac`" $javacArgs 2>&1"
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

$cls = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$d8Args = "-cp `"$d8jar`" com.android.tools.r8.D8 --release --min-api 33 --lib `"$aj`" --output `"$dex`" " + (($cls | ForEach-Object { "`"$_`"" }) -join ' ')
Invoke-Cmd "`"$java`" $d8Args 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Join-Path $out "base$suffix.apk"
Copy-Item $unsigned $apk -Force
$zip = [System.IO.Compression.ZipFile]::Open($apk, 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $dex 'classes.dex'), 'classes.dex', 'Optimal') | Out-Null
$zip.Dispose()

$aligned = Join-Path $out "base$suffix-aligned.apk"
Invoke-Cmd "`"$zipalign`" -f -p 4 `"$apk`" `"$aligned`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path $ks)) {
    Invoke-Cmd "`"$keytool`" -genkeypair -keystore `"$ks`" -storepass:env CG_KS_PASS -keypass:env CG_KS_PASS -alias callguard -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=CallGuard, O=Local, C=CN`" 2>&1"
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$signed = Join-Path $proj "CallGuard$suffix.apk"
$signArgs = "-jar `"$apksigner`" sign --ks `"$ks`" --ks-pass env:CG_KS_PASS --key-pass env:CG_KS_PASS --ks-key-alias callguard --min-sdk-version 33 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$signed`" `"$aligned`""
Invoke-Cmd "`"$java`" $signArgs 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Invoke-Cmd "`"$java`" -jar `"$apksigner`" verify --print-certs `"$signed`" 2>&1"
$perm = if ($Online) { 'with INTERNET' } else { 'no INTERNET' }
if ($NoDb) { $perm += ', no DB' }
"OK -> $signed  ($([math]::Round((Get-Item $signed).Length/1KB)) KB, $perm)"
Remove-Item Env:CG_KS_PASS -ErrorAction SilentlyContinue
