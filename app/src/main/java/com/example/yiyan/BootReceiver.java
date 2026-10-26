package com.example.yiyan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后如果「回桌面自动换一句」是开的，把 root 监听脚本重新拉起来。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        if (RootHomeWatcher.isEnabled(context)) {
            RootHomeWatcher.start(context);
        }
    }
}
