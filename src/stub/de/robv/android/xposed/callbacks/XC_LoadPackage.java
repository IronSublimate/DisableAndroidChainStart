package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/** COMPILE-ONLY STUB. 注意真实包名是 de.robv.android.xposed.callbacks。 */
public final class XC_LoadPackage {

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;

        private LoadPackageParam() {
        }
    }

    private XC_LoadPackage() {
    }
}
