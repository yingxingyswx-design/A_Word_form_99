package com.example.yiyan.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** xposed_init 入口：只在桌面进程里装玻璃。 */
public class GlassHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;
        if (!"com.android.launcher3".equals(lpparam.packageName)) return;
        XposedBridge.log("DailyLineGlass: loaded into " + lpparam.packageName);
        try {
            LauncherGlass.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}