package com.alphi.asmfix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 修复"重新签名导致设置界面卡死"（白屏 / 无响应）。
 *
 * 背景：原版在 MainActivity / DisposeAskLinkStartActivity / LogRecoderActivity 的 onCreate 里
 * 调用 m1.c.W(Activity) 做**重打包自校验**：签名不匹配就 Looper.loop() —— 在 UI 线程上死循环。
 *
 * 坑：在"模块自己的 App 进程"里，m1.c 会有**两份**：
 *   - App 自己的 base.apk 里的那份（真正被执行的是它，由 lpparam.classLoader 加载）
 *   - LSPosed 模块加载器里的那份（InMemoryDexFile，我们反射默认拿到的是它）
 * 只 hook 后者没有任何效果，必须两份都挂。
 *
 * 前提：LSPosed 里要把"Activity 跳转拦截器"自己的 App 也勾进作用域。
 */
final class UiFix {

    private static volatile boolean installed;

    private UiFix() {
    }

    static void installAppSide() {
        if (installed) {
            return;
        }
        installed = true;
        Set<Class<?>> done = new HashSet<Class<?>>();
        int total = 0;

        ClassLoader[] loaders = new ClassLoader[]{Env.targetLoader(), Env.moduleLoader()};
        for (ClassLoader loader : loaders) {
            Class<?> mc = Compat.cls(loader, "m1.c");
            if (mc == null || !done.add(mc)) {
                continue;
            }
            total += hook(mc, loader == Env.targetLoader() ? "app" : "module");
        }
        Diag.log("已跳过重打包自校验（共 hook " + total + " 个 m1.c.W）→ 设置界面不再白屏");
    }

    private static int hook(Class<?> mc, String tag) {
        int n = 0;
        for (Method m : mc.getDeclaredMethods()) {
            if (!"W".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        p.setResult(null); // 原方法 void，直接跳过校验
                    }
                });
                n++;
                Diag.log("  hook m1.c.W(" + tag + " 侧, 参数=" + m.getParameterTypes()[0].getName() + ")");
            } catch (Throwable t) {
                Diag.log("  hook m1.c.W(" + tag + " 侧) 失败", t);
            }
        }
        return n;
    }
}
