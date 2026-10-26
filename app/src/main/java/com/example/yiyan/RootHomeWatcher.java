package com.example.yiyan;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 「回到桌面自动换一句」。
 *
 * 小组件收不到「进入桌面」这个事件（Android 没有这种广播），所以这里用一个每秒一次的
 * 轻量脚本监听当前焦点窗口，焦点从别的应用切回桌面时发一次
 * `am broadcast -a com.example.yiyan.NEXT_QUOTE`。
 *
 * 两条后端，都按 shell 权限执行：
 * - Root：脚本写在应用私有目录，用 `su -c` 拉起来；
 * - Shizuku：脚本写到 /data/local/tmp，通过 Shizuku 的 shell 进程拉起
 *   （需要 Shizuku 正在运行并授权本应用）。
 *
 * 不占应用进程、不起前台服务、不弹通知；开机广播会把脚本重新拉起。
 */
public final class RootHomeWatcher {

    private static final String TAG = "99YIYAN_LOG";
    public static final String KEY_ENABLED = "rootHomeWatch";
    public static final String KEY_BACKEND = "homeWatchBackend";
    public static final String BACKEND_ROOT = "root";
    public static final String BACKEND_SHIZUKU = "shizuku";
    public static final int REQUEST_SHIZUKU = 4510;

    /** 用同一个名字，pgrep/pkill 才能同时命中两条后端。 */
    private static final String SCRIPT_NAME = "dailylin_homewatch.sh";
    private static final String SHIZUKU_SCRIPT = "/data/local/tmp/" + SCRIPT_NAME;

    private RootHomeWatcher() {
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static String backend(Context context) {
        return prefs(context).getString(KEY_BACKEND, "");
    }

    // ---- 可用性 ----

    public static boolean hasRoot() {
        String output = runSu("id", 4000);
        return output != null && output.contains("uid=0");
    }

    public static boolean hasShizuku() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shizukuGranted() {
        try {
            return rikka.shizuku.Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 让 Shizuku 弹授权框（需要 Activity）；结果在 onRequestPermissionsResult 里。 */
    public static void requestShizukuPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(REQUEST_SHIZUKU);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku 授权请求失败", t);
        }
    }

    // ---- 启停 ----

    /** 起监听脚本。返回 true 表示已经在跑。 */
    public static boolean start(Context context) {
        if (isRunning(context)) return true;

        String script = buildScript(context);
        if (hasRoot()) {
            File file = writePrivateScript(context, script);
            if (file != null
                    && runSu("nohup sh " + file.getAbsolutePath() + " >/dev/null 2>&1 &", 4000) != null) {
                boolean running = isRunning(context);
                if (running) {
                    prefs(context).edit().putString(KEY_BACKEND, BACKEND_ROOT).apply();
                    Log.i(TAG, "回桌面监听已启动（root）");
                }
                return running;
            }
        }

        if (hasShizuku() && shizukuGranted()) {
            String command = "cat > " + SHIZUKU_SCRIPT + " <<'YIYAN_EOF'\n" + script + "YIYAN_EOF\n"
                    + "chmod 700 " + SHIZUKU_SCRIPT + "\n"
                    + "nohup sh " + SHIZUKU_SCRIPT + " >/dev/null 2>&1 &\n";
            String output = runShizuku(command, 6000);
            boolean running = isRunning(context);
            Log.i(TAG, "回桌面监听（Shizuku）" + (running ? "已启动" : "启动失败") + " / " + output);
            if (running) {
                prefs(context).edit().putString(KEY_BACKEND, BACKEND_SHIZUKU).apply();
            }
            return running;
        }

        Log.w(TAG, "既没有 root，也没有可用的 Shizuku");
        return false;
    }

    public static void stop(Context context) {
        runSu("pkill -f " + SCRIPT_NAME, 3000);
        if (hasShizuku() && shizukuGranted()) {
            runShizuku("pkill -f " + SCRIPT_NAME, 3000);
        }
        Log.i(TAG, "回桌面监听已停止");
    }

    public static boolean isRunning(Context context) {
        String output = runSu("pgrep -f " + SCRIPT_NAME, 3000);
        if (output != null && output.trim().length() > 0) return true;
        if (hasShizuku() && shizukuGranted()) {
            String shizuku = runShizuku("pgrep -f " + SCRIPT_NAME, 3000);
            return shizuku != null && shizuku.trim().length() > 0;
        }
        return false;
    }

    /** 开机时若开关是开的，重新拉起。 */
    public static void resumeIfEnabled(Context context) {
        if (isEnabled(context)) start(context);
    }

    // ---- 脚本 ----

    private static String buildScript(Context context) {
        String homePackage = resolveHomePackage(context);
        return "#!/system/bin/sh\n"
                + "# 回到桌面时触发 DailyLine 换一句（由 App 生成，可随时删除）\n"
                + "HOME_PKG=\"" + homePackage + "\"\n"
                + "COMP=\"com.example.yiyan/.QuoteWidgetProvider\"\n"
                + "ACTION=\"com.example.yiyan.NEXT_QUOTE\"\n"
                + "last=\"\"\n"
                + "while true; do\n"
                + "  focus=$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)\n"
                + "  case \"$focus\" in\n"
                + "    *\"$HOME_PKG\"*)\n"
                + "      if [ \"$last\" != \"home\" ]; then\n"
                + "        am broadcast -a \"$ACTION\" -n \"$COMP\" >/dev/null 2>&1\n"
                + "        last=\"home\"\n"
                + "      fi\n"
                + "      ;;\n"
                + "    *) last=\"other\" ;;\n"
                + "  esac\n"
                + "  sleep 1\n"
                + "done\n";
    }

    private static File writePrivateScript(Context context, String script) {
        File file = new File(context.getFilesDir(), SCRIPT_NAME);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(script.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (Exception e) {
            Log.w(TAG, "写脚本失败", e);
            return null;
        }
    }

    private static String resolveHomePackage(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        try {
            android.content.pm.ResolveInfo info = context.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null) {
                String pkg = info.activityInfo.packageName;
                if (pkg != null && !pkg.isEmpty() && !"android".equals(pkg)) return pkg;
            }
        } catch (Exception ignored) {
        }
        return "com.android.launcher3";
    }

    // ---- 执行 ----

    private static String runSu(String command, long timeoutMs) {
        return exec(new String[]{"su", "-c", command}, timeoutMs);
    }

    /**
     * 通过 Shizuku 以 shell 身份执行命令。
     *
     * Shizuku 13 起 `Shizuku.newProcess` 变成私有，官方通道是 user service；
     * 这里直接拿 IShizukuService 的 newProcess（与 ShizukuRemoteProcess 内部同一调用），
     * 只需要把远端 stdout 读出来。
     */
    private static String runShizuku(String command, long timeoutMs) {
        try {
            moe.shizuku.server.IShizukuService service =
                    moe.shizuku.server.IShizukuService.Stub.asInterface(
                            rikka.shizuku.Shizuku.getBinder());
            if (service == null) return null;
            moe.shizuku.server.IRemoteProcess remote =
                    service.newProcess(new String[]{"sh", "-c", command}, null, null);
            if (remote == null) return null;

            StringBuilder sb = new StringBuilder();
            android.os.ParcelFileDescriptor descriptor = remote.getInputStream();
            if (descriptor != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                }
            }
            remote.waitForTimeout(timeoutMs, "MILLISECONDS");
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku 执行失败：" + command + " / " + t);
            return null;
        }
    }

    private static String exec(String[] command, long timeoutMs) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return read(process, timeoutMs);
        } catch (Exception e) {
            Log.w(TAG, "执行失败：" + String.join(" ", command) + " / " + e);
            return null;
        }
    }

    private static String read(Process process, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        try {
            if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
                return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            process.destroy();
        }
        return sb.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", 0);
    }
}
