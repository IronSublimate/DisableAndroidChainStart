package de.robv.android.xposed;

import java.lang.reflect.Member;

/** COMPILE-ONLY STUB. */
public abstract class XC_MethodHook {

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        public Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }

    public static final class Unhook {
        private final Member hookedMethod;
        private final XC_MethodHook callback;

        public Unhook(Member hookedMethod, XC_MethodHook callback) {
            this.hookedMethod = hookedMethod;
            this.callback = callback;
        }

        public Member getHookedMethod() {
            return hookedMethod;
        }

        public XC_MethodHook getCallback() {
            return callback;
        }

        public void unhook() {
        }
    }

    protected XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }
}
