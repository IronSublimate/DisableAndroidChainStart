package com.alphi.asmfix;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 机制自检：验证"用 Unsafe 分配无构造器的类""按名字找方法""用 v1.a 包装回调"这几件事。
 * 可以在模块 App 进程里跑，因此**不用重启框架**就能确认修复的核心机制是否成立。
 */
final class SelfTest {

    private SelfTest() {
    }

    static void runMechanics() {
        Diag.log("---- 机制自检（不需要重启框架）----");
        Diag.log("Unsafe.allocateInstance 可用 = " + Env.hasUnsafe());

        try {
            Class<?> q1h = Env.moduleClass("q1.h");
            Diag.log("q1.h 可加载 = " + (q1h != null));
            if (q1h != null) {
                Object o = Env.allocate(q1h);
                Diag.log("Env.allocate(q1.h) = " + (o != null));
                Method b = Env.findMethod(q1h, "b", 1);
                Diag.log("q1.h.b 参数类型 = " + (b == null ? "未找到" : b.getParameterTypes()[0].getName()));
            }
        } catch (Throwable t) {
            Diag.log("q1.h 自检失败", t);
        }

        try {
            Class<?> q1c = Env.moduleClass("q1.c");
            if (q1c == null) {
                Diag.log("q1.c 可加载 = false");
            } else {
                Diag.log("Env.allocate(q1.c) = " + (Env.allocate(q1c) != null));
            }
        } catch (Throwable t) {
            Diag.log("q1.c 自检失败", t);
        }

        try {
            Class<?> he = Env.moduleClass("com.alphi.activitystartmanager.xhandles.HookEntity");
            Diag.log("HookEntity.handleLoadPackage 可找到 = "
                    + (he != null && Env.findMethod(he, "handleLoadPackage", 1) != null));
        } catch (Throwable t) {
            Diag.log("HookEntity 自检失败", t);
        }

        try {
            Class<?> q1aCls = Env.moduleClass("q1.a");
            Class<?> q0a = Env.moduleClass("q0.a");
            Class<?> v1a = Env.moduleClass("v1.a");
            if (q1aCls == null || q0a == null || v1a == null) {
                Diag.log("wrap 自检：q1.a/q0.a/v1.a 有缺失");
                return;
            }
            Constructor<?> c = q1aCls.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            Object inner = c.newInstance(2);
            Constructor<?> w = v1a.getDeclaredConstructor(q0a);
            w.setAccessible(true);
            Object hook = w.newInstance(inner);
            Diag.log("v1.a(q1.a(2)) 构造 = " + (hook != null));
            Diag.log("回调是 XC_MethodHook = "
                    + (hook instanceof de.robv.android.xposed.XC_MethodHook));
        } catch (Throwable t) {
            Diag.log("wrap 自检失败", t);
        }
        Diag.log("---------------------------------------");
    }
}
