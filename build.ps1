#requires -Version 5.1
<#
    一键构建：把修复代码编译成 classes2.dex，注入原始 APK，改写 assets/xposed_init，重新签名。

    不需要联网、不需要 Gradle：
      javac → jar → d8(build-tools) → 注入 zip → zipalign → apksigner

    小工具定位策略（环境变量优先，其次自动探测，最后可用参数覆盖）：
      JDK        : -Jdk  | $env:JAVA_HOME | 常见安装目录（含 Android Studio 的 jbr）
      Android SDK: -Sdk  | $env:ANDROID_HOME | $env:ANDROID_SDK_ROOT | local.properties | 常见目录
      android.jar: -AndroidJar | 从 SDK 的 platforms 里挑最高的 android-XX
      7-Zip      : -SevenZip | $env:SEVENZIP | PATH | 常见目录；找不到则用 .NET 内置 zip 实现
      adb        : 仅 verify.ps1 需要，从 SDK platform-tools 或 PATH 探测

    用法:
      pwsh -File build.ps1
      pwsh -File build.ps1 -Original ..\app-release.apk -Out ..\app-release-fixed.apk
      pwsh -File build.ps1 -Jdk "C:\Program Files\Java\jdk-17" -Sdk "D:\Android\SDK"
#>
[CmdletBinding()]
param(
    [string]$Original = "",
    [string]$Out = "",
    [string]$Jdk = "",
    [string]$Sdk = "",
    [string]$AndroidJar = "",
    [string]$SevenZip = "",
    [switch]$ForceDotNetZip
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Original) { $Original = Join-Path (Split-Path $root -Parent) 'app-release.apk' }
if (-not $Out)      { $Out      = Join-Path (Split-Path $root -Parent) 'app-release-fixed.apk' }

function Info($m) { Write-Host "[env] $m" -ForegroundColor DarkGray }
function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

# ------------------------------------------------------------------ 工具定位
function Test-Exe([string]$path) {
    return ($path -and (Test-Path -LiteralPath $path -PathType Leaf))
}

function Get-JdkInfo([string]$dir) {
    if (-not $dir) { return $null }
    $javac = Join-Path $dir 'bin\javac.exe'
    $java  = Join-Path $dir 'bin\java.exe'
    $keytool = Join-Path $dir 'bin\keytool.exe'
    $jar   = Join-Path $dir 'bin\jar.exe'
    if (-not ((Test-Exe $javac) -and (Test-Exe $java) -and (Test-Exe $keytool) -and (Test-Exe $jar))) {
        return $null
    }
    $verStr = (& $javac -version 2>&1 | Out-String).Trim()
    $major = 0
    if ($verStr -match 'javac\s+1\.(\d+)') {
        $major = [int]$Matches[1]          # 旧式: javac 1.8.0_281 -> 8
    } elseif ($verStr -match 'javac\s+(\d+)') {
        $major = [int]$Matches[1]          # 新式: javac 11.0.11 -> 11
    }
    if ($major -lt 8) { return $null }
    return [pscustomobject]@{
        Home = $dir; Javac = $javac; Java = $java; Keytool = $keytool; Jar = $jar
        Major = $major; Version = $verStr
    }
}

function Resolve-Jdk([string]$explicit) {
    # 优先级：-Jdk 参数 > $env:JAVA_HOME > 自动探测（选可用的最高版本）
    if ($explicit) {
        $info = Get-JdkInfo $explicit
        if (-not $info) { throw "指定的 -Jdk 不可用（需 bin\javac.exe/keytool.exe/jar.exe，且版本 >= 8）: $explicit" }
        return $info
    }
    if ($env:JAVA_HOME) {
        $info = Get-JdkInfo $env:JAVA_HOME
        if ($info) { return $info }
        Write-Host "[warn] `$env:JAVA_HOME 不可用（没有 javac 或版本 < 8），改为自动探测：$env:JAVA_HOME" -ForegroundColor Yellow
    }

    $cands = New-Object System.Collections.Generic.List[string]
    $globs = @(
        "$env:ProgramFiles\Java\jdk*",
        "$env:ProgramFiles\Eclipse Adoptium\jdk*",
        "$env:ProgramFiles\Microsoft\jdk*",
        "$env:ProgramFiles\Zulu\zulu*",
        "$env:ProgramFiles\Amazon Corretto\jdk*",
        "$env:ProgramFiles\BellSoft\LibericaJDK*",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk*",
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "$env:ProgramFiles\JetBrains\*\jbr",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\*\*\jbr",
        "C:\Program Files\Java\jdk1.8*",
        "C:\Program Files\Java\jdk-*",
        "D:\Program Files\Java\jdk*",
        "D:\Java\jdk*"
    )
    foreach ($g in $globs) {
        foreach ($d in (Get-Item $g -ErrorAction SilentlyContinue)) { $cands.Add($d.FullName) }
    }
    $javacOnPath = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacOnPath) { $cands.Add((Split-Path (Split-Path $javacOnPath.Source -Parent) -Parent)) }

    $found = @()
    foreach ($c in ($cands | Where-Object { $_ } | Select-Object -Unique)) {
        $info = Get-JdkInfo $c
        if ($info) { $found += $info }
    }
    if ($found.Count -eq 0) {
        throw "找不到可用的 JDK（需要 bin\javac.exe 等，版本 >= 8）。请设置 `$env:JAVA_HOME 或用 -Jdk <目录> 指定。"
    }
    # d8 / apksigner（build-tools 33+）需要 Java 11+，所以优先高版本
    $best = ($found | Sort-Object -Property @{ Expression = 'Major'; Descending = $true })[0]
    if ($best.Major -lt 11) {
        Write-Host ("[warn] 只找到 JDK {0}；build-tools 的 d8/apksigner 建议用 JDK 11+，若失败请用 -Jdk 指定" -f $best.Major) -ForegroundColor Yellow
    }
    return $best
}

function Resolve-Sdk([string]$explicit) {
    $cands = New-Object System.Collections.Generic.List[string]
    if ($explicit) { $cands.Add($explicit) }
    if ($env:ANDROID_HOME) { $cands.Add($env:ANDROID_HOME) }
    if ($env:ANDROID_SDK_ROOT) { $cands.Add($env:ANDROID_SDK_ROOT) }

    # local.properties: sdk.dir=...
    $lp = Join-Path (Split-Path $root -Parent) 'local.properties'
    if (-not (Test-Path $lp)) { $lp = Join-Path $root 'local.properties' }
    if (Test-Path $lp) {
        $line = Get-Content $lp -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $cands.Add(($line -replace '^\s*sdk\.dir\s*=', '').Trim() -replace '\\', '\') }
    }
    $cands.Add("$env:LOCALAPPDATA\Android\Sdk")
    $cands.Add("$env:ProgramFiles\Android\Sdk")
    $cands.Add("${env:ProgramFiles(x86)}\Android\Sdk")
    $cands.Add('D:\Android\SDK'); $cands.Add('C:\Android\SDK'); $cands.Add('D:\Android\Sdk')

    $valid = @()
    foreach ($c in ($cands | Where-Object { $_ } | Select-Object -Unique)) {
        if (Test-Path (Join-Path $c 'build-tools')) { $valid += (Resolve-Path $c).Path }
    }
    if ($valid.Count -eq 0) {
        throw "找不到 Android SDK。请设置 `$env:ANDROID_HOME 或用 -Sdk <目录> 指定。"
    }
    if ($valid.Count -gt 1) {
        Info ("发现多个 SDK，已选第 1 个；如需其它可用 -Sdk 指定: " + ($valid -join ' | '))
    }
    return $valid[0]
}

function Resolve-BuildTools([string]$sdkRoot) {
    $best = $null; $bestVer = $null
    foreach ($d in (Get-ChildItem (Join-Path $sdkRoot 'build-tools') -Directory -ErrorAction SilentlyContinue)) {
        $ok = (Test-Exe (Join-Path $d.FullName 'zipalign.exe')) -and
              (Test-Exe (Join-Path $d.FullName 'lib\d8.jar')) -and
              (Test-Exe (Join-Path $d.FullName 'lib\apksigner.jar'))
        if (-not $ok) { continue }
        $v = $null
        if (-not [version]::TryParse($d.Name, [ref]$v)) { $v = [version]'0.0' }
        if (-not $bestVer -or $v -gt $bestVer) { $best = $d.FullName; $bestVer = $v }
    }
    if (-not $best) { throw "SDK 里没有可用的 build-tools（需要 zipalign.exe + lib\d8.jar + lib\apksigner.jar）" }
    return [pscustomobject]@{ Dir = $best; Version = $bestVer.ToString() }
}

function Resolve-AndroidJar([string]$sdkRoot, [string]$explicit) {
    if ($explicit) {
        if (-not (Test-Exe $explicit)) { throw "指定的 android.jar 不存在: $explicit" }
        return $explicit
    }
    $best = $null; $bestApi = -1
    foreach ($d in (Get-ChildItem (Join-Path $sdkRoot 'platforms') -Directory -ErrorAction SilentlyContinue)) {
        $jar = Join-Path $d.FullName 'android.jar'
        if (-not (Test-Exe $jar)) { continue }
        if ($d.Name -notmatch '^android-(\d+)$') { continue }
        $api = [int]$Matches[1]
        if ($api -ge 29 -and $api -gt $bestApi) { $best = $jar; $bestApi = $api }
    }
    if (-not $best) { throw "SDK 的 platforms 里没有 android-29 及以上的 android.jar（请用 sdkmanager 安装 platform）" }
    return $best
}

function Resolve-SevenZip([string]$explicit) {
    if ($explicit) {
        if (Test-Exe $explicit) { return $explicit }
        throw "指定的 7-Zip 不存在: $explicit"
    }
    if ($env:SEVENZIP -and (Test-Exe $env:SEVENZIP)) { return $env:SEVENZIP }
    foreach ($n in @('7z.exe', '7za.exe')) {
        $c = Get-Command $n -ErrorAction SilentlyContinue
        if ($c) { return $c.Source }
    }
    foreach ($p in @(
        "$env:ProgramFiles\7-Zip\7z.exe",
        "${env:ProgramFiles(x86)}\7-Zip\7z.exe",
        "$env:ProgramData\chocolatey\bin\7z.exe",
        "$env:LOCALAPPDATA\Programs\7-Zip\7z.exe"
    )) {
        if (Test-Exe $p) { return $p }
    }
    return $null   # 允许为空：后面用 .NET 内置 zip 兜底
}

function Resolve-Adb([string]$sdkRoot) {
    $p = Join-Path $sdkRoot 'platform-tools\adb.exe'
    if (Test-Exe $p) { return $p }
    $c = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    return $null
}

# ------------------------------------------------------------------ 解析环境
Step '0/8 环境探测'
$J = Resolve-Jdk $Jdk
$sdkRoot = Resolve-Sdk $Sdk
$B = Resolve-BuildTools $sdkRoot
$androidJarPath = Resolve-AndroidJar $sdkRoot $AndroidJar
$sevenZipPath = Resolve-SevenZip $SevenZip
if ($ForceDotNetZip) { $sevenZipPath = $null }
$adbPath = Resolve-Adb $sdkRoot

Info "JDK          : $($J.Home)  ($($J.Version))"
Info "Android SDK  : $sdkRoot"
Info "build-tools  : $($B.Dir)  (v$($B.Version))"
Info "android.jar  : $androidJarPath"
if ($sevenZipPath) { Info "7-Zip        : $sevenZipPath" }
else { Info '7-Zip        : 未找到 → 将使用 .NET 内置 zip 注入（无需安装 7-Zip）' }
if ($adbPath) { Info "adb          : $adbPath" }
Info "原始 APK     : $Original"
Info "输出 APK     : $Out"

foreach ($f in @($Original)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "找不到原始 APK: $f（可用 -Original 指定）" }
}

# 让 d8/apksigner/keytool 用同一套 JDK
$env:JAVA_HOME = $J.Home
$env:PATH = (Join-Path $J.Home 'bin') + ';' + $env:PATH
$javac = $J.Javac; $java = $J.Java; $jarExe = $J.Jar; $keytool = $J.Keytool
$zipalign = Join-Path $B.Dir 'zipalign.exe'
$d8Jar = Join-Path $B.Dir 'lib\d8.jar'
$apksignerJar = Join-Path $B.Dir 'lib\apksigner.jar'

$build = Join-Path $root 'build'
$stubOut = Join-Path $build 'stub-classes'
$mainOut = Join-Path $build 'main-classes'
$dexOut  = Join-Path $build 'dex'
$stage   = Join-Path $build 'stage'
$keystore = Join-Path $root 'asmfix.keystore'

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stubOut, $mainOut, $dexOut, $stage | Out-Null

# ---------------------------------------------------------------- 1. stubs
Step '1/8 编译 Xposed API stub（仅编译期使用，不打包）'
$stubSrc = Get-ChildItem (Join-Path $root 'src\stub') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $androidJarPath -d $stubOut @stubSrc
if ($LASTEXITCODE -ne 0) { throw 'stub 编译失败' }

# ---------------------------------------------------------------- 2. main
Step '2/8 编译修复代码'
$mainSrc = Get-ChildItem (Join-Path $root 'src\main') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $androidJarPath -cp $stubOut -d $mainOut @mainSrc
if ($LASTEXITCODE -ne 0) { throw '修复代码编译失败' }

# ---------------------------------------------------------------- 3. jar
Step '3/8 打包 class -> jar'
$moduleJar = Join-Path $build 'module.jar'
Push-Location $mainOut
try { & $jarExe cf $moduleJar . } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'jar 打包失败' }

# ---------------------------------------------------------------- 4. d8
Step '4/8 d8 生成 classes2.dex'
# build-tools 的 d8.bat 用了 -Djava.ext.dirs（JDK11 已移除），所以直接调 D8 主类
& $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 29 `
    --lib $androidJarPath --classpath $stubOut --output $dexOut $moduleJar
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
$dex = Join-Path $dexOut 'classes.dex'
if (-not (Test-Path $dex)) { throw 'd8 没有产出 classes.dex' }
Write-Host ("    classes2.dex = {0:N0} bytes" -f (Get-Item $dex).Length)

# ---------------------------------------------------------------- 5. 注入
Step '5/8 注入 APK：classes2.dex + 改写 assets/xposed_init'
$work = Join-Path $stage 'app.apk'
Copy-Item -LiteralPath $Original $work -Force
Copy-Item $dex (Join-Path $stage 'classes2.dex') -Force

$assetsDir = Join-Path $stage 'assets'
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
# 不加行尾换行：与已验证可用的写法一致（LSPosed 逐行读取，最好别带 CR）
[IO.File]::WriteAllText((Join-Path $assetsDir 'xposed_init'), 'com.alphi.asmfix.Entry',
    (New-Object Text.UTF8Encoding($false)))

$ok = $false
if ($sevenZipPath) {
    Push-Location $stage
    try {
        & $sevenZipPath u -tzip $work 'classes2.dex' 'assets\xposed_init' | Out-Null
        $ok = ($LASTEXITCODE -eq 0)
    } finally { Pop-Location }
    if (-not $ok) { throw '7z 注入失败' }
    Write-Host '    注入方式: 7-Zip'
} else {
    # 7-Zip 缺失时的兜底：.NET 内置 zip 就地更新
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($work, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($rel in @('classes2.dex', 'assets/xposed_init')) {
            $e = $zip.GetEntry($rel)
            if ($e) { $e.Delete() }
            $src = Join-Path $stage ($rel -replace '/', '\')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $src, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $zip.Dispose() }
    # Android 11+ 要求 resources.arsc 不能被压缩，这里显式校验
    $zip2 = [System.IO.Compression.ZipFile]::OpenRead($work)
    try {
        $arsc = $zip2.GetEntry('resources.arsc')
        if ($arsc -and ($arsc.CompressedLength -ne $arsc.Length)) {
            throw 'resources.arsc 被压缩了（Android 11+ 会拒绝安装）。请安装 7-Zip 并设置 $env:SEVENZIP，或用 -SevenZip 指定。'
        }
    } finally { $zip2.Dispose() }
    Write-Host '    注入方式: .NET 内置 zip（未找到 7-Zip）'
}

# 校验注入结果
$verifyZip = [System.IO.Compression.ZipFile]::OpenRead($work)
try {
    foreach ($need in @('classes2.dex', 'assets/xposed_init')) {
        if (-not $verifyZip.GetEntry($need)) { throw "注入校验失败，APK 内找不到 $need" }
    }
} finally { $verifyZip.Dispose() }
Write-Host '    注入校验通过'

# ---------------------------------------------------------------- 6. zipalign
Step '6/8 zipalign'
$aligned = Join-Path $build 'aligned.apk'
& $zipalign -p -f 4 $work $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

# ---------------------------------------------------------------- 7. keystore
Step '7/8 准备签名密钥'
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -keystore $keystore -alias asmfix -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname 'CN=ASM-FIX, O=chain_start_fix, C=CN' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool 生成密钥失败' }
    Write-Host '    已生成 asmfix.keystore（口令 android）—— 请离线备份，换签名会强制卸载重装'
} else {
    Write-Host '    复用已有 asmfix.keystore'
}

# ---------------------------------------------------------------- 8. sign
Step '8/8 apksigner 签名'
& $java -cp $apksignerJar com.android.apksigner.ApkSignerTool sign `
    --ks $keystore --ks-key-alias asmfix --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 29 --v1-signing-enabled false --v2-signing-enabled true `
    --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }

& $java -cp $apksignerJar com.android.apksigner.ApkSignerTool verify --verbose --print-certs $Out | Select-Object -First 12

Write-Host "`n构建完成:" -ForegroundColor Green
Write-Host ("  {0}  ({1:N0} bytes)" -f $Out, (Get-Item $Out).Length)
Write-Host ''
Write-Host '安装（覆盖安装，千万别卸载：卸载会触发模块自清理并重置 LSPosed 配置）:'
if ($adbPath) {
    Write-Host ("  `"{0}`" install -r `"{1}`"" -f $adbPath, $Out)
} else {
    Write-Host ("  adb install -r `"{0}`"" -f $Out)
}
Write-Host 'system_server 侧改动需要整机重启才会生效；App 侧改动重启 App 进程即可。'
