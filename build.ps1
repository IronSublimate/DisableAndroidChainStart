#requires -Version 5.1
<#
    一键构建：把修复代码编译成 classes2.dex，注入原始 APK，改写 assets/xposed_init，重新签名。

    不需要联网、不需要 Gradle：
      javac(JDK11) → jar → d8(build-tools) → 7z 注入 → zipalign → apksigner

    用法:
      pwsh -File build.ps1                     # 使用默认路径
      pwsh -File build.ps1 -Original <apk> -Out <apk>
#>
[CmdletBinding()]
param(
    [string]$Original = "",
    [string]$Out = ""
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Original) { $Original = Join-Path (Split-Path $root -Parent) 'app-release.apk' }
if (-not $Out)      { $Out      = Join-Path (Split-Path $root -Parent) 'app-release-fixed.apk' }

$jdk      = 'C:\Program Files\Java\jdk-11.0.11'
$javac    = Join-Path $jdk 'bin\javac.exe'
$keytool  = Join-Path $jdk 'bin\keytool.exe'
$jar      = Join-Path $jdk 'bin\jar.exe'
$java     = Join-Path $jdk 'bin\java.exe'
$bt       = 'D:\Android\SDK\build-tools\33.0.1'
$zipalign = Join-Path $bt 'zipalign.exe'
$androidJar = 'D:\Android\SDK\platforms\android-32\android.jar'
$sevenZip = 'C:\ProgramData\chocolatey\bin\7z.exe'

foreach ($t in @($javac, $keytool, $jar, $java, $zipalign, $androidJar, $sevenZip, $Original)) {
    if (-not (Test-Path $t)) { throw "缺少依赖: $t" }
}
$env:JAVA_HOME = $jdk
$env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH

$build = Join-Path $root 'build'
$stubOut = Join-Path $build 'stub-classes'
$mainOut = Join-Path $build 'main-classes'
$dexOut  = Join-Path $build 'dex'
$stage   = Join-Path $build 'stage'
$keystore = Join-Path $root 'asmfix.keystore'

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stubOut, $mainOut, $dexOut, $stage | Out-Null

# ---------------------------------------------------------------- 1. stubs
Step '1/8 编译 Xposed API stub（仅编译期使用，不打包）'
$stubSrc = Get-ChildItem (Join-Path $root 'src\stub') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $androidJar -d $stubOut @stubSrc
if ($LASTEXITCODE -ne 0) { throw 'stub 编译失败' }

# ---------------------------------------------------------------- 2. main
Step '2/8 编译修复代码'
$mainSrc = Get-ChildItem (Join-Path $root 'src\main') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $androidJar -cp $stubOut -d $mainOut @mainSrc
if ($LASTEXITCODE -ne 0) { throw '修复代码编译失败' }

# ---------------------------------------------------------------- 3. jar
Step '3/8 打包 class -> jar'
$moduleJar = Join-Path $build 'module.jar'
Push-Location $mainOut
try { & $jar cf $moduleJar . } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'jar 打包失败' }

# ---------------------------------------------------------------- 4. d8
Step '4/8 d8 生成 classes2.dex'
# build-tools 的 d8.bat 用了 -Djava.ext.dirs，JDK11 已移除该选项，所以直接调 D8 主类
& $java -cp (Join-Path $bt 'lib\d8.jar') com.android.tools.r8.D8 --release --min-api 29 `
    --lib $androidJar --classpath $stubOut --output $dexOut $moduleJar
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
$dex = Join-Path $dexOut 'classes.dex'
if (-not (Test-Path $dex)) { throw 'd8 没有产出 classes.dex' }
Write-Host ("    classes2.dex = {0:N0} bytes" -f (Get-Item $dex).Length)

# ---------------------------------------------------------------- 5. 注入
Step '5/8 注入 APK：classes2.dex + 改写 assets/xposed_init'
$work = Join-Path $stage 'app.apk'
Copy-Item $Original $work -Force
Copy-Item $dex (Join-Path $stage 'classes2.dex') -Force

$assetsDir = Join-Path $stage 'assets'
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
$initFile = Join-Path $assetsDir 'xposed_init'
Set-Content -Path $initFile -Value 'com.alphi.asmfix.Entry' -Encoding ascii -NoNewline

Push-Location $stage
try {
    & $sevenZip u -tzip $work 'classes2.dex' 'assets\xposed_init' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '7z 注入失败' }
} finally { Pop-Location }

# 校验注入结果
$entries = & $sevenZip l $work | Out-String
foreach ($need in @('classes2.dex', 'assets\xposed_init')) {
    if ($entries -notmatch [regex]::Escape($need)) { throw "注入校验失败，APK 内找不到 $need" }
}
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
    Write-Host '    已生成 asmfix.keystore（口令 android）'
} else {
    Write-Host '    复用已有 asmfix.keystore'
}

# ---------------------------------------------------------------- 8. sign
Step '8/8 apksigner 签名'
$apksignerCp = Join-Path $bt 'lib\apksigner.jar'
& $java -cp $apksignerCp com.android.apksigner.ApkSignerTool sign `
    --ks $keystore --ks-key-alias asmfix --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 29 --v1-signing-enabled false --v2-signing-enabled true `
    --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }

& $java -cp $apksignerCp com.android.apksigner.ApkSignerTool verify --verbose --print-certs $Out | Select-Object -First 12

Write-Host "`n构建完成:" -ForegroundColor Green
Write-Host ("  {0}  ({1:N0} bytes)" -f $Out, (Get-Item $Out).Length)
Write-Host ''
Write-Host '安装（注意：签名与原版不同，必须先卸载原版）:'
Write-Host '  adb uninstall com.alphi.activitystartmanager'
Write-Host ("  adb install -r `"{0}`"" -f $Out)
Write-Host '然后在 LSPosed 里给本模块勾选【系统框架】作用域，并重启一次手机（或重启 framework）。'
