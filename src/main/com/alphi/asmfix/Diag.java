package com.alphi.asmfix;

import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 自诊断日志。
 *
 * 目的：这个模块原本"装了但检测不到"时完全无声（LSPosed 日志里也看不出断在哪一步）。
 * 这里把每个阶段写进两个地方：
 *   1) LSPosed 日志（tag: ASM-FIX）
 *   2) /sdcard/Android/media/com.alphi.activitystartmanager/logfiles/fix_diag.txt
 *      —— 不需要 root，用文件管理器/adb pull 就能取，方便排查。
 */
public final class Diag {

    public static final String TAG = "ASM-FIX";
    private static final String[] CANDIDATE_DIRS = {
            // system_server 在开机早期外置存储还没挂载、且 SELinux 不允许它写 /data/local/tmp，
            // 所以第一个"一定可写"的落点放在 /data/misc（模块自己的目录也在这里）
            "/data/misc/xposed_configs/aslm",
            "/storage/emulated/0/Android/media/com.alphi.activitystartmanager/logfiles",
            "/sdcard/Android/media/com.alphi.activitystartmanager/logfiles",
            "/data/system",
            "/data/local/tmp",
    };

    private static final StringBuilder BUF = new StringBuilder();
    private static final Object LOCK = new Object();
    private static File outFile;
    private static boolean fileReady;
    private static String suffix = "";

    private Diag() {
    }

    /** 不同进程写不同文件，避免 App 进程覆盖 system_server 的诊断。 */
    public static void setSuffix(String s) {
        suffix = s == null ? "" : s;
        outFile = null;
        fileReady = false;
    }

    public static void start(String header) {
        synchronized (LOCK) {
            BUF.setLength(0);
            BUF.append("=== Activity Jump Interceptor FIX diag ===\n");
            BUF.append("time: ").append(now()).append('\n');
            BUF.append(header).append('\n');
            BUF.append("-----------------------------------------\n");
        }
        raw("diag file target: " + pickFile());
        flush();
    }

    public static void log(String msg) {
        raw(msg);
        flush();
    }

    public static void log(String msg, Throwable t) {
        raw(msg + "  <<" + describe(t) + ">>");
        flush();
    }

    /** 把反射包装异常拆开，直接显示真正的原因，省得只看到 InvocationTargetException。 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage());
            }
            Throwable next = cur.getCause();
            if (next == null || next == cur) {
                break;
            }
            cur = next;
            depth++;
        }
        // 附带最内层的第一条堆栈帧，便于定位
        if (cur != null && cur.getStackTrace() != null && cur.getStackTrace().length > 0) {
            sb.append(" @ ").append(cur.getStackTrace()[0]);
        }
        return sb.toString();
    }

    /** 同时写 LSPosed 日志；flush 由调用者决定，避免重复写文件。 */
    private static void raw(String msg) {
        String line = now() + "  " + msg;
        XposedBridge.log("[" + TAG + "] " + msg);
        synchronized (LOCK) {
            BUF.append(line).append('\n');
        }
    }

    public static void flush() {
        File f = pickFile();
        if (f == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(f, false);
                try {
                    fos.write(BUF.toString().getBytes("UTF-8"));
                    fos.flush();
                } finally {
                    fos.close();
                }
                fileReady = true;
            } catch (Throwable t) {
                // 只往 LSPosed 日志报一次，避免刷屏
                if (fileReady || outFile == null) {
                    XposedBridge.log("[" + TAG + "] diag file write failed: " + t);
                }
                outFile = null;
            }
        }
    }

    private static File pickFile() {
        if (outFile != null) {
            return outFile;
        }
        if (!fileReady) {
            for (String dir : CANDIDATE_DIRS) {
                try {
                    File d = new File(dir);
                    if (!d.exists() && !d.mkdirs()) {
                        continue;
                    }
                    File f = new File(d, "fix_diag" + suffix + ".txt");
                    FileOutputStream probe = new FileOutputStream(f, true);
                    probe.close();
                    outFile = f;
                    return outFile;
                } catch (Throwable ignored) {
                }
            }
        }
        return outFile;
    }

    public static String now() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 反射调用工具：取方法并调用，失败返回 null / 抛出的异常交给上层记录。 */
    public static Object call(Object target, String name, Class<?>[] sig, Object... args) throws Throwable {
        Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
        Method m = null;
        Class<?> cur = c;
        while (cur != null && m == null) {
            try {
                m = cur.getDeclaredMethod(name, sig);
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            }
        }
        if (m == null) {
            throw new NoSuchMethodException(c.getName() + "#" + name);
        }
        m.setAccessible(true);
        return m.invoke(target instanceof Class ? null : target, args);
    }

    public static Object field(Object target, String name) throws Throwable {
        Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
        java.lang.reflect.Field f = null;
        Class<?> cur = c;
        while (cur != null && f == null) {
            try {
                f = cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        if (f == null) {
            throw new NoSuchFieldException(c.getName() + "#" + name);
        }
        f.setAccessible(true);
        return f.get(target instanceof Class ? null : target);
    }

    public static boolean hasField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                cur.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return false;
    }
}
