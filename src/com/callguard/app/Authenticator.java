package com.callguard.app;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.ContactsContract;

/**
 * 空壳认证器：没有登录、没有服务器、没有密码。
 * 只在系统账户体系里登记一个本地账户，好让通讯录的「要显示的联系人」能单独列出 CallGuard。
 */
public class Authenticator extends AbstractAccountAuthenticator {

    private final Context ctx;

    public Authenticator(Context context) {
        super(context);
        this.ctx = context;
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                             String authTokenType, String[] requiredFeatures, Bundle options) {
        Bundle result = new Bundle();
        try {
            Account acc = AccountSetup.ensure(ctx);
            if (acc == null) {
                result.putString(AccountManager.KEY_ERROR_MESSAGE, "cannot create account");
            } else {
                ContentResolver.setIsSyncable(acc, ContactsContract.AUTHORITY, 1);
                result.putString(AccountManager.KEY_ACCOUNT_NAME, acc.name);
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, acc.type);
            }
        } catch (Throwable t) {
            result.putString(AccountManager.KEY_ERROR_MESSAGE, String.valueOf(t));
        }
        return result;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return null;
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account,
                                     Bundle options) {
        return null;
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account,
                               String authTokenType, Bundle options) {
        Bundle b = new Bundle();
        b.putString(AccountManager.KEY_ERROR_CODE,
                String.valueOf(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION));
        b.putString(AccountManager.KEY_ERROR_MESSAGE, "local account only");
        return b;
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return AccountSetup.NAME;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account,
                                    String authTokenType, Bundle options) {
        return null;
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account,
                              String[] features) {
        Bundle b = new Bundle();
        b.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
        return b;
    }
}
