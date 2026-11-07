# chain_start_fix —— Activity Jump Interceptor 修复工程

把原版 `app-release.apk`（com.alphi.activitystartmanager 2.6）**重新编译成一个可安装的修复版**，
目标是解决"LSPosed 明明装了，但模块 App 显示未激活 / 检测不到框架 / 装了没反应"这一类问题。

- 输入：`../app-release.apk`（原版，未改动）
- 输出：`../app-release-fixed.apk`（含修复代码、已重新签名）
- 构建：`pwsh -File build.ps1`（离线，不需要 Gradle / 不需要联网）

---

## 1. 原版为什么会"检测不到"

原版的激活状态完全依赖这条**单向、一次性**的链路：

```
system_server（Xposed 注入）
   └─ q1.h.b()  ← 只由 AppStateTrackerImpl.onSystemServicesReady() 触发
        ├─ 建 Binder t1.b
        ├─ 注册 IUidObserver（UID_OBSERVER_ACTIVE）
        └─ 挂拦截 Hook
   之后，仅当"模块 App 的 uid 由不活跃变活跃"的那一次：
        q1.g.onUidActive() → getContentProviderExternal("ProxyServiceProvider")
                           → IContentProvider.call(sysBinder= t1.b)
   App 侧 ServiceProvider.call() 校验 getCallingPackage()=="android" → 存进 n1.b.a
   App UI 读到 n1.b.a 才认为"已激活"
```

任何一环出问题，App 就永远显示未激活，而且**原版没有任何日志、没有重试、没有兜底**：

| 断点 | 结果 | 原版能否自愈 |
|---|---|---|
| LSPosed 里没启用 / 没勾「系统框架」作用域 | system_server 里一行模块代码都没跑 | 不能（用户只看到"未激活"） |
| `onSystemServicesReady` 钩子没挂上（类/方法改名、时序） | 后面的 Hook 全部不装 | 不能 |
| 挂 Hook 途中某个抛异常 | 后面的 Hook 全部不装（无 try/catch 隔离） | 不能 |
| 那一次 `onUidActive` 推送失败（provider 没就绪、时序） | App 永远收不到 Binder | 不能（没有重试） |

**实测结论（本机 Redmi 23049RAD8C / Android 13 / MIUI 14.0.27 / LSPosed 1.9.1）：**

- 代码兼容性**没有问题**，模块依赖的每一个点都在 ROM 里存在（见 `诊断报告-为什么检测不到.md`）。
- LSPosed 数据库里该模块 `enabled=0`、`scope` 表里一行都没有 → 即"装了但没启用、也没勾作用域"，
  system_server 从未加载它。这就是"检测不到"的直接原因。

## 2. 修复版做了什么

**不修改原模块的任何拦截逻辑**（`classes.dex` 原样保留），只新增 `classes2.dex`（约 22 KB，11 个类），
并把 `assets/xposed_init` 指向新入口 `com.alphi.asmfix.Entry`。新入口先加固、再调用原 `HookEntity`。

| 改动 | 解决什么 |
|---|---|
| **多入口触发**：`DeviceIdleController.onSystemServicesReady`、`AppStateTrackerImpl`/`AppStateTracker.onSystemServicesReady`、`AMS.systemReady`、`ATMS.systemReady`，每个独立 `try/catch`；`hookAllMethods` 挂全部重载，不靠猜签名 | 任一入口失效不再"全盘皆输" |
| **幂等 + 兜底安装**：确认原安装器没跑过时，直接反射调用 `q1.h.b()`；25s / 70s 两次安全网 | 入口钩子没触发也能装 |
| **补装丢失的 Hook**：原版挂 `ActivityRecord.finishIfPossible` 时按 4 参匹配，而本 ROM 是 5 参 `(int, Intent, NeededUriGrants, String, boolean)` → `NoSuchMethodError` 中断安装，导致排在后面的 `AMS.getContentProvider`（CP 拦截）从未装上。修复层**复用原模块自己的 Hook 对象**（`q1.c`、`q1.a(2)`）按参数类型自适应补装 | **ContentProvider 拉活拦截（`CP:` 规则）在 MIUI/A13 上恢复**；`resultTo` 清理恢复 |
| **推送自愈**：安装后立刻推一次；自注册 `UID_OBSERVER_ACTIVE` 兜底；并挂 `ActivityStarter.executeRequest`——只要模块 App 被拉起就补推（1.5s 防抖 + 1s/3s/9s 退避重试） | 修掉"那一次推送失败就永久未激活" |
| **跳过重打包自校验**：原版 `m1.c.W()` 发现签名不匹配就 `Looper.loop()` 把 UI 线程死循环（白屏）。修复层在模块 App 进程里 hook 掉它 | 重新签名后**设置界面不再白屏** |
| **兼容性自检**：把 17 个拦截器字段、5 个 Hook 点、原模块内部 10 个类逐个检查并写日志 | 一眼看出是"环境问题"还是"ROM 不兼容" |
| **自诊断文件**：`/data/misc/xposed_configs/aslm/fix_diag.txt`（system_server 侧）+ 外部存储 `fix_diag_app.txt`（App 侧），同时写 LSPosed 日志（tag `ASM-FIX`） | 不用 root 也能把断点发给别人看 |

### 2.1 实施过程中踩到、并已在代码里解决的 4 个坑

这些坑都是"照直觉写就一定会失败"的地方，记录在此便于以后再编译：

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **两个 ClassLoader 分工** | 用 `lpparam.classLoader` 加载模块类 → `ClassNotFoundException: q1.h`；用模块加载器加载框架类 → `com.android.server.*` 全部 false | 模块类（`q1.*`/`t1.*`/`HookEntity`）用 `Entry/Installer.class.getClassLoader()`；框架类用 `lpparam.classLoader`。见 `Env.java` |
| **Xposed API 类名被随机化** | `XC_MethodHook$MethodHookParam` 实际叫 `vY.kg.w.h.ktyAdGYFoJtk.XC_MethodHook$MethodHookParam`，每次启动都不同 | 绝不要 `Class.forName("de.robv...")`；用"按方法名找方法"（`Env.findMethod`）再 `invoke` |
| **R8 删掉了无参构造器** | `q1.h.<init>` / `q1.c.<init>` 不存在（原版由 DEX `new-instance` + 直接调 `Object.<init>` 实现） | `sun.misc.Unsafe.allocateInstance`（`Env.allocate`） |
| **模块 App 里的重复类** | 在模块自己的 App 进程里，`m1.c` 有两份（App 的 `base.apk` + LSPosed 的内存 dex），只 hook 一份 → 白屏依旧 | 两个加载器都 hook（`UiFix` 会打印 `app 侧` / `module 侧`） |

### 2.2 已验证结果（本机 Redmi Note 12 Turbo / Android 13 / MIUI 14 / LSPosed 1.9.1）

```
[OK] 入口 AppStateTrackerImpl.onSystemServicesReady
[OK] 拦截器所需 17 个字段全部存在
[OK] ActivityStarter.executeRequest / ActivityRecord.finishIfPossible / AMS.getContentProvider
原 HookEntity.handleLoadPackage 调用成功
已通过兜底触发调用原安装器 q1.h.b()，触发原因=AppStateImpl
[OK] 已补装 ActivityRecord#finishIfPossible（5 参）
[OK] 已补装 AMS#getContentProvider（5 参）→ CP 规则恢复
规则统计: Activity 规则组=3 通配caller=0 CP规则组=0 CP通配=0
[OK] 兜底 UID_OBSERVER_ACTIVE 已注册
推送成功 reason=start:target=self attempt=2
已跳过重打包自校验（共 hook 2 个 m1.c.W）→ 设置界面不再白屏
```

界面实测：标题栏 + 语法说明 + 规则编辑器完整显示，编辑器里的 11 条规则是**从 system_server 通过 Binder 取回的**
（不是本地 assets），即"已激活 + 规则同步"双重验证通过。

### 2.3 三个容易踩的运维点

1. **system_server 只在整机启动时被注入**。`killall system_server` 软重启**不会**让 LSPosed 重新注入（实测：连其它模块也都不加载）。
2. **卸载+重装会重置 LSPosed 记录**（`enabled=0`、作用域清空，mid 会变），必须重新启用+勾作用域。
3. **卸载会触发原模块的"卸载自清理"**，把 `/data/misc/xposed_configs/aslm/` 整个删掉（规则丢失）。本工程 README 的安装步骤因此是"覆盖安装"，不是"卸载重装"。

> 说明：原版 `-b` 只在自启动分支生效、系统应用只判 `FLAG_SYSTEM`、`CP:` 默认放行等**功能语义**问题位于
> `classes.dex` 的混淆代码里，本次没有改动（要改需源码级重写），不在"检测不到"的修复范围内。

## 3. 构建

```powershell
pwsh -File chain_start_fix\build.ps1
```

流程：`javac` → `jar` → `d8`(build-tools) → 注入 `classes2.dex` + 改写 `assets/xposed_init`
→ `zipalign` → `apksigner`（首次运行自动生成 `asmfix.keystore`，口令 `android`）。

**依赖全部自动探测，不需要改脚本**。优先级：

| 依赖 | 1. 参数 | 2. 环境变量 | 3. 其它探测位置 |
|---|---|---|---|
| JDK（≥8，建议 ≥11） | `-Jdk <目录>` | `JAVA_HOME` | `%ProgramFiles%\Java\jdk*`、Adoptium/Corretto/Zulu/Liberica、Android Studio 的 `jbr`、JetBrains Toolbox `jbr`、PATH 里的 `javac`。多个候选时**取版本最高** |
| Android SDK | `-Sdk <目录>` | `ANDROID_HOME`、`ANDROID_SDK_ROOT` | `local.properties` 的 `sdk.dir`、`%LOCALAPPDATA%\Android\Sdk`、`%ProgramFiles%\Android\Sdk`、`D:\Android\SDK` 等。多个时取第 1 个并**打印全部候选** |
| build-tools | 自动 | — | SDK 下含 `zipalign.exe` + `lib\d8.jar` + `lib\apksigner.jar` 的**最高版本** |
| `android.jar` | `-AndroidJar <路径>` | — | SDK `platforms` 里 `android-≥29` 的**最高版本** |
| 7-Zip | `-SevenZip <路径>` | `SEVENZIP` | PATH 的 `7z/7za`、`%ProgramFiles%\7-Zip`、chocolatey。**找不到就用 .NET 内置 zip 兜底**（会校验 `resources.arsc` 保持未压缩） |
| adb（仅 verify.ps1） | `-Adb <路径>` | `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `ADB` | SDK 的 `platform-tools`、PATH |

构建开始时脚本会先把解析结果打印出来（`[...env]` 开头的行），出问题一眼能看出用的是哪套工具：

```
=== 0/8 环境探测 ===
[env] JDK          : C:\Program Files\Java\jdk-11.0.11  (javac 11.0.11)
[env] Android SDK  : D:\Android\SDK
[env] build-tools  : D:\Android\SDK\build-tools\33.0.1  (v33.0.1)
[env] android.jar  : D:\Android\SDK\platforms\android-32\android.jar
[env] 7-Zip        : C:\ProgramData\chocolatey\bin\7z.exe
[env] adb          : D:\Android\SDK\platform-tools\adb.exe
```

常用覆盖示例：

```powershell
# 指定整套工具链（可复现构建）
pwsh -File build.ps1 -Jdk "C:\Program Files\Java\jdk-11.0.11" -Sdk "D:\Android\SDK"

# 换用别的 android.jar / 7-Zip
pwsh -File build.ps1 -AndroidJar "D:\Android\SDK\platforms\android-33\android.jar" -SevenZip "C:\Program Files\7-Zip\7z.exe"

# 强制走 .NET 内置 zip（验证"没有 7-Zip"的场景）
pwsh -File build.ps1 -ForceDotNetZip
```

> 注意：`build-tools/d8.bat`、`apksigner.bat` 里用的是 `-Djava.ext.dirs`（JDK8 语法，JDK9+ 已删除），
> 所以脚本绕开 .bat，直接 `java -cp lib/d8.jar com.android.tools.r8.D8` 与
> `java -cp lib/apksigner.jar com.android.apksigner.ApkSignerTool`。
> 已在 JDK 8 / 11 / 21 + build-tools 33.0.1 / 36.0.0 + android-32 / android-36 上验证通过。

## 4. 安装与启用（关键步骤）

签名与原版不同，**必须先卸载原版**：

```powershell
adb uninstall com.alphi.activitystartmanager
adb install -r app-release-fixed.apk
```

然后在 LSPosed 里（这一步是"检测不到"的真正解药）：

1. LSPosed → 模块 → 勾选 **Activity 跳转拦截器**
2. 点进它的**作用域**，勾选 **系统框架**（`android`）→ 保存
3. **重启手机**（或重启系统框架），让 system_server 重新注入
4. 打开模块 App，应显示已激活；`/sdcard/Android/media/com.alphi.activitystartmanager/logfiles/fix_diag.txt` 会生成

> MIUI 上如果 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`，用 root 装：
> `adb push app-release-fixed.apk /data/local/tmp/ && adb shell su -c "pm install -r /data/local/tmp/app-release-fixed.apk"`

## 5. 看诊断结果

```powershell
pwsh -File chain_start_fix\verify.ps1
```

会打印 `fix_diag.txt` 里的兼容性清单与安装/推送状态，并顺带抓 LSPosed 日志里的 `ASM-FIX` 行。
adb 路径同样自动探测（`-Adb` > `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`ADB` > PATH > 常见 SDK 目录），多设备时用 `-Serial <序列号>` 指定。

`fix_diag.txt` 每行的含义：

| 行 | 含义 |
|---|---|
| `LSPosed 已把模块加载进该进程` | 作用域配置正确，system_server 拿到了模块 |
| `[OK] 触发点已挂: ...` | 各入口钩子安装成功 |
| `已通过兜底触发调用原安装器` | 原入口没触发，兜底生效（说明原版在这里本来会失效） |
| `推送成功 reason=...` | App 已收到 system_server 的 Binder → 应显示已激活 |
| `[--]` 开头的行 | 该 ROM 缺这一项（`诊断报告-为什么检测不到.md` 第 3 节有逐项对照） |
| **文件根本不存在** | System_server 从未加载模块 → 回到第 4 步检查"启用 + 作用域 + 重启" |

## 6. 回滚

- 卸载修复版、装回原版即可（规则文件 `/data/misc/xposed_configs/aslm/` 保留，不会丢规则）。
- 若启用后出现系统异常：LSPosed 管理器里关掉本模块 → 重启；或用 `verify.ps1` 同目录的说明用 adb 关闭。

## 7. 目录结构

```
chain_start_fix/
├─ build.ps1                    一键构建
├─ verify.ps1                   拉取诊断/日志
├─ asmfix.keystore              构建时自动生成
├─ src/
│  ├─ stub/de/robv/android/xposed/…     Xposed API 编译期桩（不打包）
│  └─ main/com/alphi/asmfix/
│     ├─ Entry.java             新入口（xposed_init 指向它）
│     ├─ Installer.java         多入口触发 + 幂等安装 + 安全网
│     ├─ Pusher.java            Binder 推送自愈（观察者/启动检测/重试）
│     ├─ Compat.java            兼容性自检
│     └─ Diag.java              日志 + 诊断文件
└─ build/                       中间产物（classes2.dex 等）
```
