package com.alphi.asmfix;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 负责把 system_server 侧的 Binder 稳定地"推"给模块 App。
 *
 * 原版只在一个时机推：IUidObserver.onUidActive（uid 由不活跃变活跃的**那一次**）。
 * 只要那一次失败（provider 还没起来、进程刚重启、时序问题…），App 就永远显示"未激活"，
 * 而且没有任何重试和日志——这是"LSPosed 装了但 App 检测不到"最典型的第二个原因。
 *
 * 这里补上：
 *   - 安装完成后立刻推一次；
 *   - 自己再注册一个 UID_OBSERVER_ACTIVE 兜底（原注册失败也能推）；
 *   - 只要 system_server 观察到"模块 App 被拉起"，就再推一次（带防抖与重试）。
 */
public final class Pusher {

    static final String PKG = "com.alphi.activitystartmanager";

    private static final Object LOCK = new Object();
    private static volatile int appUid = -1;
    private static volatile long lastPushAt;
    private static volatile long lastOkAt;
    private static volatile boolean pushOk;
    private static volatile int attempts;
    private static volatile boolean selfHookInstalled;

    private Pusher() {
    }

    static void arm() {
        appUid = resolveAppUid();
        Diag.log("模块 App uid = " + appUid + "（appId=" + (appUid < 0 ? -1 : appUid % 100000) + "）");
        if (appUid < 0) {
            Diag.log("拿不到 uid，推送无法进行（包名是否被改？）");
            return;
        }
        registerActiveObserver();
        hookSelfStart();
        push("post-install");
    }

    /* ---------------- uid ---------------- */

    private static int resolveAppUid() {
        try {
            Class<?> ag = Env.targetClass("android.app.AppGlobals");
            if (ag == null) {
                return -1;
            }
            Object ipm = Diag.call(ag, "getPackageManager", new Class<?>[]{});
            if (ipm == null) {
                return -1;
            }
            Class<?> ipmCls = ipm.getClass();
            try {
                Object r = ipmCls.getMethod("getPackageUid", String.class, long.class, int.class)
                        .invoke(ipm, PKG, 0L, 0);
                if (r instanceof Integer) {
                    return (Integer) r;
                }
            } catch (NoSuchMethodException ignored) {
            }
            Object r = ipmCls.getMethod("getPackageUid", String.class, int.class, int.class)
                    .invoke(ipm, PKG, 0, 0);
            if (r instanceof Integer) {
                return (Integer) r;
            }
        } catch (Throwable t) {
            Diag.log("resolveAppUid 失败", t);
        }
        return -1;
    }

    /* ---------------- 推送 ---------------- */

    private static void push(final String reason) {
        long now = System.currentTimeMillis();
        if (now - lastPushAt < 1500L) {
            return; // 防抖
        }
        if (pushOk && now - lastOkAt < 30_000L) {
            return; // 刚成功过，别刷
        }
        lastPushAt = now;
        attempts++;
        final int attempt = attempts;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                doPush(reason, attempt);
            }
        }, "asmfix-push");
        th.setDaemon(true);
        th.start();
    }

    private static void doPush(String reason, int attempt) {
        try {
            Class<?> q1h = Env.moduleClass("q1.h");
            if (q1h == null) {
                Diag.log("推送失败：q1.h 找不到 reason=" + reason);
                return;
            }
            Object observer = XposedHelpers.getStaticObjectField(q1h, "c");
            if (observer == null) {
                Diag.log("推送失败：q1.h.c 为空（原安装器未完成） reason=" + reason);
                return;
            }
            Integer uid = appUid;
            Diag.call(observer, "onUidActive", new Class<?>[]{int.class}, uid);
            if (!pushOk) {
                pushOk = true;
                lastOkAt = System.currentTimeMillis();
                Diag.log("推送成功 reason=" + reason + " attempt=" + attempt + " uid=" + uid);
            } else {
                lastOkAt = System.currentTimeMillis();
            }
        } catch (Throwable t) {
            Diag.log("推送失败 reason=" + reason + " attempt=" + attempt, t);
            if (attempt < 4) {
                long delay = attempt == 1 ? 1000L : (attempt == 2 ? 3000L : 9000L);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                }
                doPush(reason + "/retry", attempt + 1);
            }
        }
    }

    /* ---------------- 触发：模块 App 被拉起 ---------------- */

    /** 热路径缓存：executeRequest 每个 Activity 启动都会走，不能每次反射查字段。 */
    private static volatile Field fRequest;
    private static volatile Field fCallingPkg;
    private static volatile Field fActivityInfo;
    private static volatile Field fIntent;

    private static void hookSelfStart() {
        if (selfHookInstalled) {
            return;
        }
        selfHookInstalled = true;
        try {
            Class<?> starter = Env.targetClass("com.android.server.wm.ActivityStarter");
            if (starter == null) {
                Diag.log("[--] ActivityStarter 找不到，跳过补推送");
                return;
            }
            XposedBridge.hookAllMethods(starter, "executeRequest", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Field fr = fRequest;
                        if (fr == null) {
                            fr = fieldOf(p.thisObject.getClass(), "mRequest");
                            fRequest = fr;
                        }
                        if (fr == null) {
                            return;
                        }
                        Object req = fr.get(p.thisObject);
                        if (req == null) {
                            return;
                        }
                        Class<?> rc = req.getClass();

                        Field fp = fCallingPkg;
                        if (fp == null) {
                            fp = fieldOf(rc, "callingPackage");
                            fCallingPkg = fp;
                        }
                        if (fp != null && PKG.equals(fp.get(req))) {
                            push("start:caller=self");
                            return;
                        }

                        Field fa = fActivityInfo;
                        if (fa == null) {
                            fa = fieldOf(rc, "activityInfo");
                            fActivityInfo = fa;
                        }
                        if (fa != null) {
                            Object ai = fa.get(req);
                            if (ai instanceof ActivityInfo && PKG.equals(((ActivityInfo) ai).packageName)) {
                                push("start:target=self");
                                return;
                            }
                        }

                        Field fi = fIntent;
                        if (fi == null) {
                            fi = fieldOf(rc, "intent");
                            fIntent = fi;
                        }
                        if (fi != null) {
                            Object it = fi.get(req);
                            if (it instanceof Intent) {
                                Intent intent = (Intent) it;
                                if (intent.getComponent() != null
                                        && PKG.equals(intent.getComponent().getPackageName())) {
                                    push("start:intent=self");
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 热路径，绝不抛出
                    }
                }
            });
            Diag.log("[OK] 已挂 ActivityStarter.executeRequest（监听模块 App 被拉起 → 补推送）");
        } catch (Throwable t) {
            Diag.log("[--] 挂 executeRequest 失败（只影响补推送）", t);
        }
    }

    private static Field fieldOf(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    /* ---------------- 触发：自注册 ACTIVE 观察者 ---------------- */

    private static void registerActiveObserver() {
        try {
            final Class<?> iu = Env.targetClass("android.app.IUidObserver");
            if (iu == null) {
                Diag.log("[--] IUidObserver 找不到");
                return;
            }
            final IBinder token = new Binder();
            Object proxy = Proxy.newProxyInstance(Env.targetLoader(), new Class<?>[]{iu}, new InvocationHandler() {
                @Override
                public Object invoke(Object p, Method m, Object[] a) {
                    try {
                        String n = m.getName();
                        if ("asBinder".equals(n)) {
                            return token;
                        }
                        if ("onUidActive".equals(n) && a != null && a.length == 1 && a[0] instanceof Integer) {
                            if ((Integer) a[0] == appUid) {
                                push("onUidActive");
                            }
                        }
                        return defaultValue(m.getReturnType());
                    } catch (Throwable ignored) {
                        return defaultValue(m.getReturnType());
                    }
                }
            });

            Class<?> am = Env.targetClass("android.app.ActivityManager");
            Object iam = Diag.call(am, "getService", new Class<?>[]{});
            // UID_OBSERVER_ACTIVE = 1 << 2 = 4 ; cutpoint 用 PROCESS_STATE_UNKNOWN(-1)
            Diag.call(iam, "registerUidObserver",
                    new Class<?>[]{iu, int.class, int.class, String.class}, proxy, 4, -1, null);
            Diag.log("[OK] 兜底 UID_OBSERVER_ACTIVE 已注册");
        } catch (Throwable t) {
            Diag.log("[--] 兜底 uid observer 注册失败（原模块的观察者仍可能工作）", t);
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == void.class) {
            return null;
        }
        if (t == boolean.class) {
            return Boolean.FALSE;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        return null;
    }
}
