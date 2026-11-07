package com.alphi.asmfix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 补装原安装器"没装成"的两个 Hook。
 *
 * 背景（真机实测）：
 *   原版 q1.h.b() 里一路 findAndHookMethod 往下挂，**没有任何 try/catch**。
 *   在 MIUI 14 / Android 13 上，ActivityRecord.finishIfPossible 是
 *   (int, Intent, NeededUriGrants, String, boolean) 五个参数，而原版按
 *   (int, Intent, String, boolean) 四个参数去挂 → NoSuchMethodError → 异常中断安装，
 *   于是**排在它后面的 ActivityManagerService.getContentProvider Hook 永远不会被装上**，
 *   结果是 CP: 规则（拦 ContentProvider 拉活）完全失效。
 *
 * 修复思路：不重写任何规则逻辑，直接复用原模块自己的 Hook 对象
 *   - ActivityRecord.finishIfPossible  → q1.a(2)   （清 resultTo）
 *   - AMS.getContentProvider            → q1.c      （CP: 规则裁决）
 * 只是把"方法签名探测 + 单独 try/catch"换成按参数类型自适应匹配。
 */
final class ExtraHooks {

    private ExtraHooks() {
    }

    static void install() {
        Diag.log("---------- 补装 Hook（签名自适应）----------");
        try {
            finishHook();
        } catch (Throwable t) {
            Diag.log("[--] 补装 finishIfPossible 失败", t);
        }
        try {
            contentProviderHook();
        } catch (Throwable t) {
            Diag.log("[--] 补装 getContentProvider 失败", t);
        }
    }

    /* ---------------- ActivityRecord.finishIfPossible ---------------- */

    private static void finishHook() {
        Class<?> rec = Env.targetClass("com.android.server.wm.ActivityRecord");
        if (rec == null) {
            rec = Env.targetClass("com.android.server.wm.ActivityStack");
        }
        if (rec == null) {
            Diag.log("[--] 找不到 ActivityRecord/ActivityStack，跳过");
            return;
        }

        Method target = null;
        Method anyFinish = null;
        for (Method m : rec.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 2 || p[0] != int.class || !p[1].getName().equals("android.content.Intent")) {
                continue;
            }
            if ("finishIfPossible".equals(m.getName())) {
                target = m;      // 优先要原版真正想挂的那个
                break;
            }
            if (anyFinish == null) {
                anyFinish = m;   // 退路：同形态的其它 finish 方法
            }
        }
        if (target == null) {
            target = anyFinish;
        }
        if (target == null) {
            Diag.log("[--] 没找到 (int, Intent, …) 形态的 finishIfPossible，跳过");
            return;
        }

        Object hook = wrapDiscriminated(2);
        if (hook == null) {
            return;
        }
        XposedBridge.hookMethod(target, (XC_MethodHook) hook);
        Diag.log("[OK] 已补装 " + rec.getSimpleName() + "#" + target.getName()
                + "（" + target.getParameterTypes().length + " 参）→ 清 resultTo 逻辑恢复");
    }

    /* ---------------- ActivityManagerService.getContentProvider ---------------- */

    private static void contentProviderHook() {
        Class<?> ams = Env.targetClass("com.android.server.am.ActivityManagerService");
        if (ams == null) {
            Diag.log("[--] 找不到 ActivityManagerService，跳过");
            return;
        }

        Method target = null;
        for (Method m : ams.getDeclaredMethods()) {
            if (!"getContentProvider".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length >= 4
                    && p[0].getName().contains("IApplicationThread")
                    && p[1] == String.class
                    && p[2] == String.class) {
                target = m;
                break;
            }
        }
        if (target == null) {
            Diag.log("[--] 没找到符合特征的 getContentProvider 重载，跳过（CP 拦截不可用）");
            return;
        }

        Object hook = wrapPlain();
        if (hook == null) {
            return;
        }
        XposedBridge.hookMethod(target, (XC_MethodHook) hook);
        Diag.log("[OK] 已补装 AMS#getContentProvider（" + target.getParameterTypes().length
                + " 参）→ CP: 规则（ContentProvider 拉活拦截）恢复");
    }

    /* ---------------- 工具 ---------------- */

    /** new q1.a(discriminator) → 用 v1.a 包成 XC_MethodHook */
    private static Object wrapDiscriminated(int discriminator) {
        try {
            Class<?> impl = Env.moduleClass("q1.a");
            if (impl == null) {
                Diag.log("[--] q1.a 找不到");
                return null;
            }
            Constructor<?> c = impl.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            Object inner = c.newInstance(discriminator);
            return wrapWith(inner);
        } catch (Throwable t) {
            Diag.log("[--] 构造 q1.a(" + discriminator + ") 失败", t);
            return null;
        }
    }

    /** new q1.c() —— 注意 q1.c 的 <init> 被 R8 删了，必须 Unsafe 分配 */
    private static Object wrapPlain() {
        try {
            Class<?> impl = Env.moduleClass("q1.c");
            if (impl == null) {
                Diag.log("[--] q1.c 找不到");
                return null;
            }
            return wrapWith(Env.allocate(impl));
        } catch (Throwable t) {
            Diag.log("[--] 分配 q1.c 失败", t);
            return null;
        }
    }

    /** new v1.a(q0.a) —— v1.a 是 XC_MethodHook 子类，负责把 before/after 转发给 q0.a */
    private static Object wrapWith(Object inner) {
        try {
            Class<?> base = Env.moduleClass("q0.a");
            Class<?> wrapper = Env.moduleClass("v1.a");
            if (base == null || wrapper == null) {
                Diag.log("[--] q0.a / v1.a 找不到");
                return null;
            }
            Constructor<?> w = wrapper.getDeclaredConstructor(base);
            w.setAccessible(true);
            return w.newInstance(inner);
        } catch (Throwable t) {
            Diag.log("[--] v1.a 包装失败", t);
            return null;
        }
    }
}
