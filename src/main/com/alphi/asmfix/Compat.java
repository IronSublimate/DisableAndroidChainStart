package com.alphi.asmfix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 兼容性自检：把原模块"隐式依赖"的每个类/方法/字段都显式检查一遍并写进诊断日志。
 *
 * 框架类用 target 加载器（com.android.server.* 只在目标进程里），
 * 模块类用 module 加载器（LSPosed 的 LspModuleClassLoader 看不到框架类）。
 */
public final class Compat {

    private static final String[] INTERCEPTOR_FIELDS = {
            "mIntent", "mRInfo", "mAInfo", "mResolvedType",
            "mCallingPid", "mCallingUid", "mRealCallingPid", "mRealCallingUid",
            "mCallingPackage", "mCallingFeatureId", "mUserId", "mStartFlags",
            "mActivityOptions", "mInTask", "mSupervisor", "mService", "mRootWindowContainer",
    };

    public static boolean hasInterceptorFields;
    public static boolean hasExecuteRequest;
    public static boolean hasGetContentProvider;

    private Compat() {
    }

    /**
     * ClassLoader 探测：确认"模块类走 module、框架类走 target"这一前提。
     */
    public static void probe(ClassLoader moduleCl, ClassLoader targetCl) {
        Diag.log("---------- ClassLoader 探测 ----------");
        for (String n : new String[]{"q1.h", "t1.b", "q1.c", "v1.a",
                "com.alphi.activitystartmanager.xhandles.HookEntity"}) {
            Diag.log("  " + n + "  module=" + (cls(moduleCl, n) != null)
                    + "  target=" + (cls(targetCl, n) != null));
        }
        for (String n : new String[]{"com.android.server.wm.ActivityStarter",
                "com.android.server.am.ActivityManagerService",
                "com.android.server.AppStateTrackerImpl", "android.app.AppGlobals"}) {
            Diag.log("  " + n + "  module=" + (cls(moduleCl, n) != null)
                    + "  target=" + (cls(targetCl, n) != null));
        }
        Diag.log("--------------------------------------");
    }

    public static void run() {
        Diag.log("---------- 兼容性自检 ----------");

        Class<?> impl = Env.targetClass("com.android.server.AppStateTrackerImpl");
        Class<?> old = Env.targetClass("com.android.server.AppStateTracker");
        Diag.log(check("入口 AppStateTrackerImpl.onSystemServicesReady",
                impl != null && hasMethod(impl, "onSystemServicesReady")));
        if (impl == null) {
            Diag.log(check("入口 AppStateTracker.onSystemServicesReady（旧版回退）",
                    old != null && hasMethod(old, "onSystemServicesReady")));
        }
        Class<?> dic = Env.targetClass("com.android.server.DeviceIdleController");
        Diag.log(check("入口 DeviceIdleController.onSystemServicesReady（本 ROM 的真实调用方）",
                dic != null && hasMethod(dic, "onSystemServicesReady")));

        Class<?> intr = Env.targetClass("com.android.server.wm.ActivityStartInterceptor");
        if (intr == null) {
            Diag.log("[--] ActivityStartInterceptor 不存在 → 无法拦截 Activity 跳转");
            hasInterceptorFields = false;
        } else {
            Diag.log("[OK] ActivityStartInterceptor 存在");
            StringBuilder missing = new StringBuilder();
            for (String f : INTERCEPTOR_FIELDS) {
                if (!Diag.hasField(intr, f)) {
                    missing.append(missing.length() == 0 ? "" : ",").append(f);
                }
            }
            hasInterceptorFields = missing.length() == 0;
            if (hasInterceptorFields) {
                Diag.log("[OK] 拦截器所需 " + INTERCEPTOR_FIELDS.length + " 个字段全部存在");
            } else {
                Diag.log("[!!] 拦截器缺字段: " + missing + " → ask/deny 路径会失败");
            }
            Diag.log(check("ActivityStartInterceptor.interceptSuspendedPackageIfNeeded",
                    hasMethod(intr, "interceptSuspendedPackageIfNeeded")));
        }

        Class<?> starter = Env.targetClass("com.android.server.wm.ActivityStarter");
        hasExecuteRequest = starter != null && hasMethod(starter, "executeRequest");
        Diag.log(check("ActivityStarter.executeRequest（resultTo 注入点）", hasExecuteRequest));
        if (!hasExecuteRequest) {
            Diag.log(check("ActivityStarter.execute（SDK<30 回退）",
                    starter != null && hasMethod(starter, "execute")));
        }

        Class<?> rec = Env.targetClass("com.android.server.wm.ActivityRecord");
        Class<?> stack = Env.targetClass("com.android.server.wm.ActivityStack");
        Diag.log(check("ActivityRecord.finishIfPossible / ActivityStack.finishCurrentActivityLocked",
                (rec != null && hasMethod(rec, "finishIfPossible"))
                        || (stack != null && hasMethod(stack, "finishCurrentActivityLocked"))));

        Class<?> ams = Env.targetClass("com.android.server.am.ActivityManagerService");
        int cps = ams == null ? 0 : countMethods(ams, "getContentProvider");
        hasGetContentProvider = cps > 0;
        Diag.log(check("ActivityManagerService.getContentProvider（CP 拉活拦截，重载 " + cps + " 个）",
                hasGetContentProvider));

        Class<?> atms = Env.targetClass("com.android.server.wm.ActivityTaskManagerService");
        if (atms != null) {
            for (String f : new String[]{"mService", "mAmInternal", "mRootWindowContainer"}) {
                Diag.log(check("ActivityTaskManagerService." + f, Diag.hasField(atms, f)));
            }
        } else {
            Diag.log("[--] ActivityTaskManagerService 不存在（极不正常）");
        }
        Class<?> rwc = Env.targetClass("com.android.server.wm.RootWindowContainer");
        Diag.log(check("RootWindowContainer.getTopResumedActivity（判「是否前台」）",
                rwc != null && hasMethod(rwc, "getTopResumedActivity")));

        Diag.log("---------- 原模块内部结构 ----------");
        for (String n : new String[]{"q1.h", "q1.g", "q1.e", "q1.a", "q1.b", "q1.c", "t1.b", "t1.a", "s1.c", "m1.a"}) {
            Diag.log(check("class " + n, Env.moduleClass(n) != null));
        }
        Diag.log("--------------------------------");
    }

    private static String check(String what, boolean ok) {
        return (ok ? "[OK] " : "[--] ") + what;
    }

    static Class<?> cls(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean hasMethod(Class<?> c, String name) {
        return countMethods(c, name) > 0;
    }

    static int countMethods(Class<?> c, String name) {
        int n = 0;
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    n++;
                }
            }
            cur = cur.getSuperclass();
        }
        return n;
    }

    static Field fieldOrNull(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
