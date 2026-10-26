package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 编译用桩：运行时由 Vector / LSPosed 提供同名接口。 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}