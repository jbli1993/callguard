package com.callguard.app;

import android.content.Context;
import android.os.Bundle;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.SyncResult;

/** 空同步适配器：只为了让系统把 CallGuard 认作一个完整的通讯录账户，不做任何同步。 */
public class SyncAdapter extends AbstractThreadedSyncAdapter {

    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        // 不联网、不同步
    }
}
