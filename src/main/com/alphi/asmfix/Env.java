package com.alphi.asmfix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 两个 ClassLoader 的分工（实测结论，别搞反）：
 *
 *   module  = Installer.class.getClassLoader()
 *             → LSPosed 生成的 LspModuleClassLoader，**父加载器不是目标进程的**。
 *               它只能看到：模块自己的类（q1.* / t1.* / HookEntity / v1.a / q0.a …）
 *               + boot classpath（android.app.AppGlobals / IUidObserver …）。
 *
 *   target  = lpparam.classLoader
 *             → 目标进程的 PathClassLoader（system_server 里是 services.jar +
 *               miui-services.jar + miuix.jar …），**看不到模块类**。
 *
 * 所以：模块类走 module，框架的 com.android.server.* 走 target。
 * 原版模块之所以没这个问题，是因为它直接以字节码引用这些类（由各自的加载器解析）。
 *
 * 另外：R8 把 q1.h / q1.c 的 <init> 删掉了（原版用 DEX 的 new-instance + 直接调
 * Object.<init>），Java 侧只能用 Unsafe.allocateInstance 复刻。
 */
final class Env {

    private static ClassLoader module;
    private static ClassLoader target;
    private static Object unsafe;
    private static Method allocateInstance;

    private Env() {
    }

    static void init(ClassLoader moduleCl, ClassLoader targetCl) {
        module = moduleCl != null ? moduleCl : Env.class.getClassLoader();
        target = targetCl != null ? targetCl : module;
        try {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            Field f = u.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            allocateInstance = u.getMethod("allocateInstance", Class.class);
        } catch (Throwable ignored) {
            unsafe = null;
            allocateInstance = null;
        }
    }

    static ClassLoader moduleLoader() {
        return module;
    }

    static ClassLoader targetLoader() {
        return target;
    }

    /** 模块自己的类 */
    static Class<?> moduleClass(String name) {
        try {
            return Class.forName(name, false, module);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目标进程里的框架类；找不到再退回模块加载器（boot 类两边都能看到） */
    static Class<?> targetClass(String name) {
        try {
            return Class.forName(name, false, target);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(name, false, module);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 不跑构造器地创建实例（等价于 DEX 的 new-instance + 直接调父类 <init>） */
    static Object allocate(Class<?> c) throws Throwable {
        if (allocateInstance != null) {
            return allocateInstance.invoke(unsafe, c);
        }
        // 退路：真有构造器就用它
        java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** 按名字找方法（不依赖参数类型的 Class，避免遇到加载器差异导致的 NoSuchMethod） */
    static Method findMethod(Class<?> c, String name, int paramCount) {
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && (paramCount < 0 || m.getParameterTypes().length == paramCount)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    static boolean hasUnsafe() {
        return allocateInstance != null;
    }
}
