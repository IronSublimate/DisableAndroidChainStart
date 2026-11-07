package com.alphi.asmfix;

import android.os.Build;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * 安装器：负责在 system_server 里把原模块装起来，并在原入口失手时兜底。
 *
 * 原版的问题（也是"检测不到"的根因类别）：
 *   1. 唯一入口 = AppStateTrackerImpl.onSystemServicesReady()；只要这个 hook 没挂上/没触发，
 *      整个模块就没有任何后续动作，而且没有任何日志。
 *   2. 挂 hook 的过程没有任何 try/catch：任何一个 hook 抛错，后面的全部不装。
 *   3. 没有任何"我到底装到哪一步了"的输出。
 *
 * 本类的做法：
 *   - 保留原入口（PackageMonitor 卸载清理等仍由原代码负责）；
 *   - 额外挂 4 个独立触发点（每个单独 try/catch），任一触发就调用原安装器；
 *   - 原安装器只在确认"从未跑过"时才由我们调用，避免重复安装；
 *   - 25s/70s 两次兜底，保证"只要 system_server 起来了就一定装"。
 */
public final class Installer {

    private static final Object LOCK = new Object();

    private static XC_LoadPackage.LoadPackageParam param;
    private static boolean started;
    private static boolean installerInvokedByUs;
    private static volatile boolean postDone;

    private Installer() {
    }

    public static void onLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;
            param = lpparam;
            Env.init(Installer.class.getClassLoader(), lpparam.classLoader);
        }

        Diag.start("package=" + lpparam.packageName
                + "  process=" + lpparam.processName
                + "  sdk=" + Build.VERSION.SDK_INT
                + "  rom=" + Build.MANUFACTURER + "/" + Build.MODEL
                + "  fp=" + Build.FINGERPRINT);
        Diag.log("LSPosed 已把模块加载进该进程");
        Diag.log("moduleClassLoader = " + Env.moduleLoader());
        Diag.log("targetClassLoader = " + Env.targetLoader());
        Diag.log("Unsafe.allocateInstance 可用 = " + Env.hasUnsafe());

        Compat.probe(Env.moduleLoader(), Env.targetLoader());
        Compat.run();

        // 1) 原模块入口（保留原有行为：注册 AppStateTracker 入口 hook + 卸载自清理）
        callOriginalEntry(lpparam);

        // 2) 额外触发点
        armTriggers();

        // 3) 首次机会 + 兜底
        if (originalInstallerRan()) {
            Diag.log("原安装器已执行（t1.b.a != null），直接进入 post");
            postInstall("after-original");
        } else {
            Diag.log("原安装器尚未执行，等待触发点…");
        }
        startSafetyNet(25, "safety-25s");
        startSafetyNet(70, "safety-70s");
    }

    /* ---------------- 原模块入口 ---------------- */

    private static void callOriginalEntry(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> he = Env.moduleClass("com.alphi.activitystartmanager.xhandles.HookEntity");
            if (he == null) {
                Diag.log("原模块 HookEntity 找不到（APK 里 classes.dex 是否完整？）");
                return;
            }
            Object inst = Env.allocate(he);
            // 参数类型不显式指定：避免因加载器差异拿不到 XC_LoadPackage$LoadPackageParam 的 Class
            Method m = Env.findMethod(he, "handleLoadPackage", 1);
            if (m == null) {
                Diag.log("原模块 HookEntity.handleLoadPackage 方法找不到");
                return;
            }
            m.invoke(inst, lpparam);
            Diag.log("原 HookEntity.handleLoadPackage 调用成功");
        } catch (Throwable t) {
            Diag.log("原 HookEntity.handleLoadPackage 失败（不影响后续兜底）", t);
        }
    }

    /** 原安装器的"已执行"标志：它做的第一件事就是创建并保存 t1.b 单例。 */
    static boolean originalInstallerRan() {
        try {
            Class<?> tb = Env.moduleClass("t1.b");
            if (tb == null) {
                return false;
            }
            Object a = XposedHelpers.getStaticObjectField(tb, "a");
            return a != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 直接调用原安装器 q1.h.b(null)。
     * 注意：q1.h 的 <init> 被 R8 删了，必须用 Unsafe 分配；b() 只用静态状态，传 null 安全。
     */
    private static void invokeOriginalInstaller(String why) {
        synchronized (LOCK) {
            if (installerInvokedByUs) {
                return;
            }
            installerInvokedByUs = true;
        }
        try {
            Class<?> q1h = Env.moduleClass("q1.h");
            if (q1h == null) {
                Diag.log("q1.h 找不到，无法兜底安装");
                postInstall(why);
                return;
            }
            Object inst = Env.allocate(q1h);
            Method b = Env.findMethod(q1h, "b", 1);
            if (b == null) {
                Diag.log("q1.h.b 方法找不到");
                postInstall(why);
                return;
            }
            b.invoke(inst, new Object[]{null});
            Diag.log("已通过兜底触发调用原安装器 q1.h.b()，触发原因=" + why);
        } catch (Throwable t) {
            Diag.log("兜底调用 q1.h.b() 失败，原因=" + why, t);
        }
        postInstall(why);
    }

    /* ---------------- 触发点 ---------------- */

    private static void armTriggers() {
        hookAfterAll("com.android.server.DeviceIdleController", "onSystemServicesReady", "DeviceIdle");
        hookAfterAll("com.android.server.AppStateTrackerImpl", "onSystemServicesReady", "AppStateImpl");
        hookAfterAll("com.android.server.AppStateTracker", "onSystemServicesReady", "AppState");
        hookAfterAll("com.android.server.am.ActivityManagerService", "systemReady", "AMS.systemReady");
        hookAfterAll("com.android.server.wm.ActivityTaskManagerService", "systemReady", "ATMS.systemReady");
    }

    /** 用 hookAllMethods 挂全部重载，免得因为签名猜错而挂不上。框架类必须用 target 加载器。 */
    private static void hookAfterAll(String className, String methodName, final String tag) {
        try {
            Class<?> c = Env.targetClass(className);
            if (c == null) {
                Diag.log("[--] 触发点类不存在: " + className + " (" + tag + ")");
                return;
            }
            if (Compat.countMethods(c, methodName) == 0) {
                Diag.log("[--] 触发点方法不存在: " + className + "#" + methodName + " (" + tag + ")");
                return;
            }
            XposedBridge.hookAllMethods(c, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    onTrigger(tag);
                }
            });
            Diag.log("[OK] 触发点已挂: " + className + "#" + methodName + " (" + tag + ")");
        } catch (Throwable t) {
            Diag.log("[--] 触发点挂载失败: " + className + "#" + methodName + " (" + tag + ")", t);
        }
    }

    private static void onTrigger(final String tag) {
        try {
            if (originalInstallerRan()) {
                postInstall(tag);
                return;
            }
            // 注意：原 HookEntity 也在这个方法上挂了 after hook。
            // 如果这里立刻调用 q1.h.b()，就可能和它各装一遍（重复 hook）。
            // 所以先延迟 3s 再看：它装了就用它的结果，确实没装才兜底。
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        if (!originalInstallerRan()) {
                            Diag.log("入口 " + tag + " 触发后 3s 仍未安装 → 兜底安装");
                            invokeOriginalInstaller(tag + "/deferred");
                        } else {
                            postInstall(tag + "/deferred");
                        }
                    } catch (Throwable t) {
                        Diag.log("延迟兜底异常 (" + tag + ")", t);
                    }
                }
            }, "asmfix-defer");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            Diag.log("触发点处理异常 (" + tag + ")", t);
        }
    }

    private static void startSafetyNet(final int seconds, final String tag) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException ignored) {
                }
                try {
                    if (!originalInstallerRan()) {
                        Diag.log(tag + " 到点，原安装器仍未执行 → 强制安装");
                        invokeOriginalInstaller(tag);
                    } else {
                        postInstall(tag);
                    }
                } catch (Throwable t) {
                    Diag.log(tag + " 异常", t);
                }
            }
        }, "asmfix-" + tag);
        th.setDaemon(true);
        th.start();
    }

    /* ---------------- 安装后 ---------------- */

    private static void postInstall(String why) {
        if (postDone) {
            return;
        }
        postDone = true;
        Diag.log("post-install 开始（原因=" + why + "）");
        try {
            ExtraHooks.install();
        } catch (Throwable t) {
            Diag.log("ExtraHooks.install 失败", t);
        }
        try {
            logRuleStats();
        } catch (Throwable t) {
            Diag.log("规则统计失败", t);
        }
        try {
            Pusher.arm();
        } catch (Throwable t) {
            Diag.log("Pusher.arm 失败", t);
        }
    }

    /** 打印原模块规则引擎里实际加载了多少条规则（诊断用）。 */
    private static void logRuleStats() {
        Class<?> sc = Env.moduleClass("s1.c");
        if (sc == null) {
            return;
        }
        Object inst = XposedHelpers.getStaticObjectField(sc, "g");
        if (inst == null) {
            return;
        }
        Object a = XposedHelpers.getObjectField(inst, "a");
        Object b = XposedHelpers.getObjectField(inst, "b");
        Object c = XposedHelpers.getObjectField(inst, "c");
        Object d = XposedHelpers.getObjectField(inst, "d");
        Diag.log("规则统计: Activity 规则组=" + size(a) + " 通配caller=" + size(b)
                + " CP规则组=" + size(c) + " CP通配=" + size(d));
    }

    private static int size(Object o) {
        if (o instanceof java.util.Map) {
            return ((java.util.Map<?, ?>) o).size();
        }
        if (o instanceof java.util.Collection) {
            return ((java.util.Collection<?>) o).size();
        }
        return -1;
    }
}
