#requires -Version 5.1
<#
    验证脚本：把设备上的诊断文件和 LSPosed 日志抓下来。
    用法: pwsh -File verify.ps1
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [int]$LogMinutes = 30
)

$ErrorActionPreference = 'Continue'
$adb = 'D:\Android\SDK\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb" }
$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }

function Run($desc, [scriptblock]$sb) {
    Write-Host "`n=== $desc ===" -ForegroundColor Cyan
    try { & $sb } catch { Write-Host "  失败: $($_.Exception.Message)" -ForegroundColor Yellow }
}

Run '设备' {
    & $adb @adbArgs shell "getprop ro.build.version.release; getprop ro.build.fingerprint"
}

Run '模块安装状态' {
    & $adb @adbArgs shell "dumpsys package com.alphi.activitystartmanager | grep -E 'versionName|versionCode=|signatures' | head -3"
}

Run 'system_server 侧诊断文件' {
    $paths = @(
        '/data/misc/xposed_configs/aslm/fix_diag.txt',
        '/storage/emulated/0/Android/media/com.alphi.activitystartmanager/logfiles/fix_diag.txt',
        '/sdcard/Android/media/com.alphi.activitystartmanager/logfiles/fix_diag.txt',
        '/data/system/fix_diag.txt'
    )
    $found = $null
    foreach ($p in $paths) {
        $raw = & $adb @adbArgs shell "su -c 'cat $p 2>/dev/null'"
        if ($raw -and ($raw -join '').Trim().Length -gt 0) {
            Write-Host "  (来源: $p)" -ForegroundColor DarkGray
            $found = $raw
            break
        }
    }
    if (-not $found) {
        Write-Host '  !! 没有诊断文件 —— system_server 从未加载本模块。' -ForegroundColor Red
        Write-Host '     请检查：LSPosed 是否【启用】本模块 + 作用域是否勾选【系统框架】+ 是否【整机重启】过。'
        Write-Host '     注意：只重启 framework（kill system_server）不会让 LSPosed 重新注入 system_server。'
    } else {
        $found
    }
}

Run 'LSPosed 日志里的 ASM-FIX' {
    $log = & $adb @adbArgs shell "su -c 'cat /data/adb/lspd/log/modules.log 2>/dev/null | grep ASM-FIX | tail -40'"
    if (-not $log) { $log = & $adb @adbArgs logcat -d -s LSPosed-Bridge | Select-String 'ASM-FIX' | Select-Object -Last 40 }
    if (-not $log) { Write-Host '  （暂无 ASM-FIX 记录）' } else { $log }
}

Run 'LSPosed 是否加载本模块到 system_server' {
    $hit = & $adb @adbArgs shell "su -c 'grep -h `"Loading legacy module com.alphi`" /data/adb/lspd/log/modules.log 2>/dev/null | tail -5'"
    if (-not $hit) {
        $hit = & $adb @adbArgs logcat -d | Select-String 'Loading legacy module com.alphi' | Select-Object -Last 5
    }
    if (-not $hit) { Write-Host '  未发现加载记录（system_server 尚未重启，或作用域未生效）' } else { $hit }
}

Run '模块自己的运行日志（外部存储）' {
    & $adb @adbArgs shell "ls -la /sdcard/Android/media/com.alphi.activitystartmanager/logfiles/ 2>/dev/null"
}

Write-Host "`n提示：如果“system_server 侧诊断文件”一节显示 no such file，且“LSPosed 是否加载”也为空，" -ForegroundColor Yellow
Write-Host "      基本可以断定是 LSPosed 的启用/作用域/重启 三件事之一没做。" -ForegroundColor Yellow
