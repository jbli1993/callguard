package com.callguard.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 系统账户框架的绑定入口。 */
public class AuthenticatorService extends Service {

    private Authenticator authenticator;

    @Override
    public void onCreate() {
        authenticator = new Authenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
