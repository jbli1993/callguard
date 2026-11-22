package com.callguard.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 同步适配器的服务外壳（系统要求与 SyncAdapter 成对出现）。 */
public class SyncService extends Service {

    private static final Object LOCK = new Object();
    private static SyncAdapter adapter;

    @Override
    public void onCreate() {
        synchronized (LOCK) {
            if (adapter == null) {
                adapter = new SyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter == null ? null : adapter.getSyncAdapterBinder();
    }
}
