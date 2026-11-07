package com.alphi.asmfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 新的模块入口（assets/xposed_init 指向这里）。
 *
 * 与原版 HookEntity 的关系：本类先做自检与触发点加固，再调用原 HookEntity，
 * 因此原模块的拦截逻辑一行没改，只是"更容易被装上、装不上时有话说"。
 */
public final class Entry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if ("android".equals(lpparam.packageName)) {
                // system_server
                if ("android".equals(lpparam.processName)) {
                    Installer.onLoadPackage(lpparam);
                }
                return;
            }
            if (Pusher.PKG.equals(lpparam.packageName)) {
                // 作用域把模块 App 自身也勾上了：留痕迹 + 做机制自检（不需要重启框架就能验证）
                Diag.setSuffix("_app");
                Diag.start("package=" + lpparam.packageName + " process=" + lpparam.processName);
                Diag.log("模块被加载进自己的 App 进程（作用域包含 App 自身，这不是必须的）");
                Env.init(Entry.class.getClassLoader(), lpparam.classLoader);
                Compat.probe(Env.moduleLoader(), Env.targetLoader());
                UiFix.installAppSide();
                SelfTest.runMechanics();
            }
        } catch (Throwable t) {
            Diag.log("Entry 顶层异常", t);
        }
    }
}
