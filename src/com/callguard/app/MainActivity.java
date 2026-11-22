package com.callguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

public class MainActivity extends Activity {

    private static final int REQ_ROLE = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final int REQ_CONTACTS = 1003;
    private static final int REQ_NOTIFY = 1004;
    private static final int REQ_WRITE_CONTACTS = 1005;
    private static final int REQ_CALLLOG = 1006;

    private TextView emergencyStatus;
    private TextView emergencyAction;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Rules rules;

    private int colAccent;
    private int colOnSurface;
    private int colOnSurfaceVariant;
    private int colSurface;
    private int colSurfaceVariant;
    private int colOutline;
    private int colError;

    private TextView headSub;
    private TextView regionTitle;
    private TextView statusChip;
    private TextView statusSub;
    private TextView regionView;
    private TextView dbStatus;
    private TextView logView;
    private TextView queryResult;
    private EditText onlineInput;
    private EditText onlineKeyInput;
    private TextView onlineResult;
    private TextView onlineUsed;
    private TextView onlineCostNote;
    private TextView onlineFreeNote;
    private TextView onlineOffNote;
    private TextView onlineKeyNote;
    private LinearLayout onlineKeyRow;
    private Switch paidSwitch;
    private LinearLayout historyList;
    private Button onlineBtn;
    private LinearLayout onlineBody;
    private Button roleButton;
    private TextView contactsStatus;
    private TextView contactsAction;
    private Switch notifySwitch;
    private Switch overlaySwitch;
    private Switch writeSwitch;
    private View hideRow;
    private TextView contactsNameStatus;
    private Button writeContactsBtn;
    private boolean hasInternet;

    private Map<String, List<String>> provinceCities = new LinkedHashMap<>();
    private boolean dark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rules = new Rules(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        resolveColors();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg());
        scroll.setClipToPadding(true);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets b = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        TextView title = text(getString(R.string.app_name), 26, colOnSurface, true);
        title.setLetterSpacing(-0.01f);
        root.addView(title);

        hasInternet = getPackageManager()
                .checkPermission("android.permission.INTERNET", getPackageName())
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        headSub = text("", 13, colOnSurfaceVariant, false);
        headSub.setPadding(0, dp(4), 0, dp(4));
        root.addView(headSub);

        if (!rules.setupSeen()) root.addView(buildIntroCard(root));
        root.addView(buildStatusCard());
        root.addView(buildReminderCard());
        root.addView(buildMainCard());
        root.addView(buildExtraCard());
        root.addView(buildContactsNameCard());
        root.addView(buildRegionCard());
        root.addView(buildQueryCard());
        root.addView(buildOnlineCard());
        root.addView(buildLogCard());
        root.addView(buildHistoryCard());
        root.addView(buildDbCard());
        root.addView(buildAboutCard());

        setContentView(scroll);

        Db.preload(this);
        // 用户自己后来把号码存进通讯录的，把我们写的那条清掉（避免聚合后名字被顶掉）
        new Thread(() -> {
            int n = AccountSetup.pruneUserDuplicates(getApplicationContext());
            if (n > 0) runOnUiThread(() -> {
                ContactsLookup.invalidate();
                refreshContactsNameStatus();
                toast(getString(R.string.toast_contacts_pruned, n));
            });
        }, "prune-contacts").start();
        loadProvinceCities();

        refreshRegionView();
        refreshLog();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshLog();
        refreshHistory();
        refreshEmergencyStatus();
    }

    // ================= 各个卡片 =================

    /**
     * 第一次打开时的三步提示。点「知道了」永久消失。
     *
     * <p>为什么值得占首屏一次：这套东西装完不会自己变成"能看见的样子"——
     * 没授予角色就不拦截，用系统拨号器又不开通知就什么都看不到。
     * 与其让用户在十几张卡片里找，不如第一次就说清楚三件事。
     */
    private View buildIntroCard(ViewGroup root) {
        LinearLayout card = card(getString(R.string.section_intro));
        int[] steps = {R.string.intro_step1, R.string.intro_step2, R.string.intro_step3};
        for (int res : steps) {
            TextView line = text(getString(res), 13, colOnSurfaceVariant, false);
            line.setPadding(0, dp(2), 0, dp(4));
            card.addView(line);
        }
        Button ok = pill(getString(R.string.intro_ok), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(44));
        lp.topMargin = dp(10);
        ok.setLayoutParams(lp);
        ok.setOnClickListener(v -> {
            rules.setSetupSeen(true);
            root.removeView(card);
        });
        card.addView(ok);
        return card;
    }

    private View buildStatusCard() {
        LinearLayout card = card(null);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView shield = icon(R.drawable.ic_cg_shield);
        shield.setColorFilter(colAccent);
        LinearLayout.LayoutParams shieldLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        shieldLp.setMarginEnd(dp(14));
        row.addView(shield, shieldLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(getString(R.string.status_title), 16, colOnSurface, true));
        statusSub = text("", 13, colOnSurfaceVariant, false);
        statusSub.setPadding(0, dp(2), 0, 0);
        col.addView(statusSub);
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        statusChip = text("", 12, colAccent, true);
        statusChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.addView(statusChip);
        card.addView(row);

        roleButton = pill(getString(R.string.btn_grant), true);
        LinearLayout.LayoutParams roleLp = new LinearLayout.LayoutParams(MATCH, dp(44));
        roleLp.topMargin = dp(14);
        roleButton.setLayoutParams(roleLp);
        roleButton.setOnClickListener(v -> requestRole());
        card.addView(roleButton);
        return card;
    }

    private View buildMainCard() {
        LinearLayout card = card(null);
        card.setPadding(dp(16), dp(4), dp(16), dp(10));
        card.addView(toggleRow(getString(R.string.enable_blocking), null,
                rules.enabled(), rules::setEnabled));
        card.addView(divider());
        card.addView(subheader(getString(R.string.section_action)));
        card.addView(actionGroup());
        return card;
    }

    private View buildExtraCard() {
        LinearLayout card = card(getString(R.string.section_extra));
        card.addView(toggleRow(getString(R.string.extra_virtual_title), getString(R.string.extra_virtual_sub),
                rules.blockVirtual(), rules::setBlockVirtual));
        card.addView(divider());
        card.addView(toggleRow(getString(R.string.extra_intl_title), getString(R.string.extra_intl_sub),
                rules.blockIntl(), rules::setBlockIntl));
        card.addView(divider());
        card.addView(toggleRow(getString(R.string.extra_unknown_title), getString(R.string.extra_unknown_sub),
                rules.blockUnknown(), rules::setBlockUnknown));
        card.addView(divider());

        card.addView(contactsRow());
        card.addView(divider());
        card.addView(toggleRow(getString(R.string.extra_relay_title), getString(R.string.extra_relay_sub),
                rules.silenceRelay(), rules::setSilenceRelay));
        card.addView(divider());
        card.addView(emergencyRow());
        return card;
    }

    /**
     * 来电时显示什么。
     *
     * <p>这是装完之后唯一必须做的选择（除了授予角色）：
     * <ul>
     *   <li>用本项目的拨号器（自带通话界面）→ 只留「向拨号器提供号码标签」，下面两项都关；</li>
     *   <li>用系统拨号器 → 打开通知或悬浮窗其中一项，否则看不到归属地和标记。</li>
     * </ul>
     *
     * <p>查询不受这三个开关影响：只要有人要数据（拨号器或显示），
     * 来电时照样会查，结果写进缓存。
     */
    private View buildReminderCard() {
        LinearLayout card = card(getString(R.string.section_reminder));

        TextView hint = text(getString(R.string.reminder_hint), 12, colOnSurfaceVariant, false);
        hint.setPadding(0, dp(2), 0, dp(10));
        card.addView(hint);

        card.addView(toggleRow(getString(R.string.label_provider_title),
                getString(R.string.label_provider_sub),
                rules.labelProvider(), rules::setLabelProvider));
        card.addView(divider());
        notifySwitch = new Switch(this);
        card.addView(toggleRowWith(getString(R.string.notify_toggle_title),
                getString(R.string.notify_toggle_sub), rules.notifyOnCall(), notifySwitch, on -> {
                    rules.setNotifyOnCall(on);
                    if (on && !Notifier.canPost(this)) {
                        requestPermissions(
                                new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                    }
                }));
        card.addView(divider());
        overlaySwitch = new Switch(this);
        card.addView(toggleRowWith(getString(R.string.overlay_title), getString(R.string.overlay_sub),
                rules.overlayOn(), overlaySwitch, on -> {
                    if (on && !Overlay.canShow(this)) {
                        // 没授权就先别打开，带用户去系统设置
                        overlaySwitch.setChecked(false);
                        toast(getString(R.string.overlay_need_permission));
                        Overlay.requestPermission(this);
                        return;
                    }
                    rules.setOverlayOn(on);
                }));
        return card;
    }

    /**
     * 紧急呼叫豁免：状态 + 立即解除。
     *
     * <p>打完 110/119/120 后 2 小时内全部放行来电，避免派出所/急救中心回电被拦。
     * 这里只控制"拦不拦"，归属地和标签照常显示。
     */
    private View emergencyRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));

        box.addView(text(getString(R.string.emergency_title), 15, colOnSurface, false));
        TextView sub = text(getString(R.string.emergency_sub), 13, colOnSurfaceVariant, false);
        sub.setPadding(0, dp(2), 0, dp(8));
        box.addView(sub);

        emergencyStatus = text("", 13, colOnSurfaceVariant, false);
        box.addView(emergencyStatus);

        emergencyAction = text("", 13, colOnSurface, true);
        emergencyAction.setPadding(0, dp(8), 0, 0);
        emergencyAction.setOnClickListener(v -> {
            if (!hasCallLogPermission()) {
                requestPermissions(new String[]{"android.permission.READ_CALL_LOG"}, REQ_CALLLOG);
                return;
            }
            Emergency.clear(this);
            toast(getString(R.string.emergency_cleared));
            refreshEmergencyStatus();
        });
        box.addView(emergencyAction);

        refreshEmergencyStatus();
        return box;
    }

    /**
     * 通讯录白名单。
     *
     * <p>不是开关：**通讯录里的号码永远豁免所有规则**（国际 / 未知号段 / 地区 / 700）。
     * 这里只显示权限状态；没授权就给一个入口。
     *
     * <p>为什么不做成开关：这正是"系统白名单"的意义——用户已经在通讯录里表达了"这人我认识"，
     * 再让他回到我们 App 里维护第二份名单，就是两份名单互相打架。
     */
    private View contactsRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));

        box.addView(text(getString(R.string.extra_contacts_title), 15, colOnSurface, false));
        TextView sub = text(getString(R.string.extra_contacts_sub), 13, colOnSurfaceVariant, false);
        sub.setPadding(0, dp(2), 0, dp(8));
        box.addView(sub);

        contactsStatus = text("", 13, colOnSurfaceVariant, false);
        box.addView(contactsStatus);

        contactsAction = text("", 13, colOnSurface, true);
        contactsAction.setPadding(0, dp(8), 0, 0);
        contactsAction.setOnClickListener(v -> requestPermissions(
                new String[]{android.Manifest.permission.READ_CONTACTS}, REQ_CONTACTS));
        box.addView(contactsAction);

        refreshContactsStatus();
        return box;
    }

    private void refreshContactsStatus() {
        if (contactsStatus == null) return;
        boolean ok = checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        contactsStatus.setText(getString(ok
                ? R.string.contacts_white_on
                : R.string.contacts_white_off));
        if (contactsAction != null) {
            contactsAction.setText(getString(R.string.emergency_grant));
            contactsAction.setVisibility(ok ? View.GONE : View.VISIBLE);
        }
    }

    private boolean hasCallLogPermission() {
        return checkSelfPermission("android.permission.READ_CALL_LOG")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void refreshEmergencyStatus() {
        if (emergencyStatus == null || emergencyAction == null) return;
        if (!hasCallLogPermission()) {
            emergencyStatus.setText(getString(R.string.emergency_need_permission));
            emergencyAction.setText(getString(R.string.emergency_grant));
            emergencyAction.setVisibility(View.VISIBLE);
            return;
        }
        long left = Emergency.remainingMillis(this);
        if (left <= 0) {            emergencyStatus.setText(getString(R.string.emergency_inactive));
            emergencyAction.setVisibility(View.GONE);
            return;
        }
        emergencyStatus.setText(getString(R.string.emergency_active, formatRemaining(left)));
        emergencyAction.setText(getString(R.string.emergency_clear));
        emergencyAction.setVisibility(View.VISIBLE);
    }

    private String formatRemaining(long ms) {
        long hours = ms / 3_600_000L;
        if (hours >= 24) return (hours / 24) + "d " + (hours % 24) + "h";
        long minutes = Math.max(1, ms / 60_000L);
        return hours + "h " + (minutes % 60) + "m";
    }

    private View buildContactsNameCard() {
        LinearLayout card = card(getString(R.string.section_contactsname));
        card.addView(rowIconText(R.drawable.ic_cg_person, getString(R.string.account_label),
                getString(R.string.contacts_desc)));

        contactsNameStatus = text("", 13, colOnSurfaceVariant, false);
        contactsNameStatus.setPadding(0, dp(6), 0, dp(10));
        card.addView(contactsNameStatus);

        card.addView(divider());
        writeSwitch = new Switch(this);
        card.addView(toggleRowWith(getString(R.string.contacts_write_title),
                getString(R.string.contacts_write_sub), rules.contactsWrite(), writeSwitch, on -> {
                    rules.setContactsWrite(on);
                    if (!on) {
                        new Thread(() -> {
                            AccountSetup.clear(getApplicationContext());
                            runOnUiThread(() -> {
                                ContactsLookup.invalidate();
                                refreshContactsNameStatus();
                            });
                        }, "clear-contacts-off").start();
                    }
                    refreshContactsUi();
                }));
        card.addView(divider());
        card.addView(toggleRow(getString(R.string.save_lookup_title),
                getString(R.string.save_lookup_sub), rules.saveOnLookup(), rules::setSaveOnLookup));
        card.addView(divider());
        hideRow = toggleRow(getString(R.string.contacts_hide_title),
                getString(R.string.contacts_hide_sub), rules.contactsHidden(), on -> {
                    rules.setContactsHidden(on);
                    new Thread(() -> {
                        AccountSetup.setHidden(getApplicationContext(), on);
                        runOnUiThread(this::refreshContactsNameStatus);
                    }, "hide-contacts").start();
                });
        card.addView(hideRow);
        card.addView(divider());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        writeContactsBtn = pill(getString(R.string.btn_write_contacts, Directory.size()), true);
        Button clearBtn = pill(getString(R.string.btn_clear_contacts), false);
        row.addView(writeContactsBtn, weightLp(1f, 0, dp(44)));
        row.addView(clearBtn, fixedLp(dp(10), dp(44), dp(88)));
        writeContactsBtn.setOnClickListener(v -> {
            boolean canRead = checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean canWrite = checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!canRead || !canWrite) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_CONTACTS,
                        android.Manifest.permission.WRITE_CONTACTS}, REQ_WRITE_CONTACTS);
                return;
            }
            doWriteContacts();
        });
        clearBtn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.contacts_clear_title)
                .setMessage(R.string.contacts_clear_msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_ok, (d, w) -> doClearContacts())
                .show());
        card.addView(row);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));

        refreshContactsNameStatus();
        refreshContactsUi();
        return card;
    }

    private void doWriteContacts() {
        if (writeContactsBtn != null) writeContactsBtn.setEnabled(false);
        final boolean hide = rules.contactsHidden();
        new Thread(() -> {
            int n = AccountSetup.write(getApplicationContext(), hide);
            runOnUiThread(() -> {
                if (writeContactsBtn != null) writeContactsBtn.setEnabled(true);
                toast(n < 0 ? getString(R.string.toast_contacts_write_denied)
                        : getString(R.string.toast_contacts_written, n));
                refreshContactsNameStatus();
            });
        }, "seed-contacts").start();
    }

    private void doClearContacts() {
        new Thread(() -> {
            int n = AccountSetup.clear(getApplicationContext());
            runOnUiThread(() -> {
                toast(getString(R.string.toast_contacts_cleared, n));
                refreshContactsNameStatus();
            });
        }, "clear-contacts").start();
    }

    private void refreshContactsNameStatus() {
        if (contactsNameStatus == null) return;
        int n = AccountSetup.count(this);
        if (!rules.contactsWrite()) {
            contactsNameStatus.setText(getString(R.string.contacts_status_overlay_only));
        } else if (n <= 0) {
            contactsNameStatus.setText(getString(R.string.contacts_status_none));
        } else {
            contactsNameStatus.setText(getString(AccountSetup.isHidden(this)
                    ? R.string.contacts_status_hidden : R.string.contacts_status_visible, n));
        }
    }

    /** 关掉「写入通讯录」时，隐藏相关行、禁用写入按钮。 */
    private void refreshContactsUi() {
        boolean write = rules.contactsWrite();
        if (hideRow != null) hideRow.setVisibility(write ? View.VISIBLE : View.GONE);
        if (writeContactsBtn != null) {
            writeContactsBtn.setEnabled(write);
            writeContactsBtn.setAlpha(write ? 1f : 0.4f);
        }
        refreshContactsNameStatus();
    }

    private View buildRegionCard() {
        LinearLayout card = card(getString(R.string.section_region));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(4), 0, dp(4));
        ImageView mapIcon = icon(R.drawable.ic_cg_map);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        mapLp.setMarginEnd(dp(12));
        head.addView(mapIcon, mapLp);
        regionTitle = text(getString(R.string.region_black_title), 15, colOnSurface, false);
        head.addView(regionTitle, new LinearLayout.LayoutParams(0, WRAP, 1f));
        card.addView(head);

        regionView = text("", 13, colOnSurfaceVariant, false);
        regionView.setPadding(0, dp(2), 0, dp(12));
        card.addView(regionView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = pill(getString(R.string.btn_pick_region), true);
        Button clearRegion = pill(getString(R.string.btn_clear), false);
        buttons.addView(pick, weightLp(1f, 0, dp(44)));
        buttons.addView(clearRegion, weightLp(1f, dp(10), dp(44)));
        pick.setOnClickListener(v -> showProvinceDialog());
        clearRegion.setOnClickListener(v -> {
            rules.setRegions(new java.util.HashSet<>());
            refreshRegionView();
        });
        card.addView(buttons);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        return card;
    }

    private View buildQueryCard() {
        LinearLayout card = card(getString(R.string.section_query));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText numInput = field(getString(R.string.hint_query));
        Button doQuery = pill(getString(R.string.btn_query), false);
        row.addView(numInput, weightLp(1f, 0, dp(52)));
        row.addView(doQuery, fixedLp(dp(10), dp(52), dp(92)));
        card.addView(row);
        queryResult = text("", 13, colOnSurfaceVariant, false);
        queryResult.setPadding(0, dp(10), 0, dp(2));
        queryResult.setVisibility(View.GONE);
        card.addView(queryResult);
        doQuery.setOnClickListener(v -> {
            String n = numInput.getText().toString();
            if (n.isEmpty()) return;
            String digits = Rules.normalize(n);
            Db d = Db.get(this);
            Db.Rec rec = (digits != null && digits.length() >= 7 && d != null)
                    ? d.lookup(Integer.parseInt(digits.substring(0, 7))) : null;
            Rules.Verdict verdict = rules.evaluate(this, n);
            String area = rec == null ? getString(R.string.none) : rec.area;
            String known = digits == null ? null : Directory.label(this, digits);
            int ruleRes = verdict.action == Rules.ACTION_SILENCE ? R.string.query_rule_silence
                    : verdict.block ? R.string.query_rule_block : R.string.query_rule_allow;
            queryResult.setVisibility(View.VISIBLE);
            queryResult.setText(getString(R.string.query_region,
                    rec == null ? getString(R.string.query_unknown_region) : rec.label(this))
                    + "\n" + getString(R.string.query_area, area)
                    + (known == null ? "" : "\n" + getString(R.string.directory_hit, known))
                    + "\n" + getString(ruleRes, verdict.reason));
        });
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        return card;
    }

    private View buildOnlineCard() {
        LinearLayout card = card(getString(R.string.section_online));

        Switch sw = new Switch(this);
        card.addView(toggleRowWith(getString(R.string.online_enable_title),
                getString(R.string.online_enable_sub), rules.onlineEnabled(), sw, on -> {
                    rules.setOnlineEnabled(on);
                    updateOnlineVisibility();
                }));

        onlineBody = new LinearLayout(this);
        onlineBody.setOrientation(LinearLayout.VERTICAL);

        // ---- 数据源：免费 / 付费各一个开关 ----
        onlineBody.addView(toggleRow(getString(R.string.online_free_title),
                getString(R.string.online_free_sub), rules.onlineFree(), on -> {
                    rules.setOnlineFree(on);
                    refreshOnlineNotes();
                }));
        paidSwitch = new Switch(this);
        onlineBody.addView(toggleRowWith(getString(R.string.online_paid_title),
                getString(R.string.online_paid_sub), rules.onlinePaid(), paidSwitch, on -> {
                    rules.setOnlinePaid(on);
                    refreshOnlineNotes();
                }));

        onlineKeyInput = field(getString(R.string.online_key_hint));
        onlineKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        onlineKeyInput.setText(rules.onlineKey());

        onlineCostNote = text(getString(R.string.online_cost_note), 12, colOnSurfaceVariant, false);
        onlineCostNote.setPadding(0, dp(10), 0, 0);
        onlineFreeNote = text(getString(R.string.online_free_note), 12, colOnSurfaceVariant, false);
        onlineFreeNote.setPadding(0, dp(10), 0, 0);
        onlineUsed = text("", 12, colOnSurfaceVariant, false);
        onlineUsed.setPadding(0, dp(10), 0, 0);
        onlineOffNote = text(getString(R.string.online_both_off), 12, colError, false);
        onlineOffNote.setPadding(0, dp(10), 0, 0);
        onlineKeyNote = text(getString(R.string.online_key_note), 12, colOnSurfaceVariant, false);
        onlineKeyNote.setPadding(0, dp(6), 0, 0);

        Button saveKey = pill(getString(R.string.btn_save_key), false);
        onlineKeyRow = new LinearLayout(this);
        onlineKeyRow.setOrientation(LinearLayout.HORIZONTAL);
        onlineKeyRow.setGravity(Gravity.CENTER_VERTICAL);
        onlineKeyRow.addView(onlineKeyInput, weightLp(1f, 0, dp(52)));
        onlineKeyRow.addView(saveKey, fixedLp(dp(10), dp(52), dp(84)));
        saveKey.setOnClickListener(v -> {
            rules.setOnlineKey(onlineKeyInput.getText().toString());
            // 存了 Key 就默认要用它，否则用户以为在走 Tmini，其实还在走免费接口
            if (!rules.onlineKey().isEmpty() && !rules.onlinePaid()) {
                rules.setOnlinePaid(true);
                if (paidSwitch != null) paidSwitch.setChecked(true);
            }
            refreshOnlineNotes();
            toast(getString(R.string.toast_key_saved));
        });

        onlineBody.addView(onlineKeyRow);
        onlineBody.addView(onlineKeyNote);
        onlineBody.addView(onlineCostNote);
        onlineBody.addView(onlineFreeNote);
        onlineBody.addView(onlineUsed);
        onlineBody.addView(onlineOffNote);

        // ---- 查询时机 ----
        onlineBody.addView(subheader(getString(R.string.section_query_when)));
        onlineBody.addView(queryModeGroup());
        onlineOffNote.setText(getString(R.string.online_both_off));
        refreshOnlineNotes();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        onlineInput = field(getString(R.string.hint_query));
        onlineInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        onlineBtn = pill(getString(R.string.online_query_btn), true);
        row.addView(onlineInput, weightLp(1f, 0, dp(52)));
        row.addView(onlineBtn, fixedLp(dp(10), dp(52), dp(120)));
        onlineBtn.setOnClickListener(v -> runOnlineLookup());
        onlineBody.addView(row);

        onlineResult = text("", 13, colOnSurfaceVariant, false);
        onlineResult.setPadding(0, dp(10), 0, dp(2));
        onlineResult.setVisibility(View.GONE);
        onlineBody.addView(onlineResult);

        card.addView(onlineBody);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        updateOnlineVisibility();
        refreshOnlineUsed();
        return card;
    }

    private void updateOnlineVisibility() {
        if (onlineBody != null) {
            onlineBody.setVisibility(rules.onlineEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshOnlineUsed() {
        if (onlineUsed != null) {
            onlineUsed.setText(getString(R.string.online_used, rules.onlineUsed()));
        }
    }

    /** 数据源开关变化后：只显示对应的说明；两个都关掉时给一条红色提示。 */
    private void refreshOnlineNotes() {
        boolean free = rules.onlineFree();
        boolean paid = rules.onlinePaid();
        if (onlineCostNote != null) onlineCostNote.setVisibility(paid ? View.VISIBLE : View.GONE);
        if (onlineFreeNote != null) onlineFreeNote.setVisibility(free ? View.VISIBLE : View.GONE);
        // Key 输入框一直显示（否则用户找不到在哪填），付费关着时给一行说明
        if (onlineKeyNote != null) onlineKeyNote.setVisibility(paid ? View.GONE : View.VISIBLE);
        if (onlineUsed != null) onlineUsed.setVisibility(paid ? View.VISIBLE : View.GONE);
        if (onlineOffNote != null) {
            onlineOffNote.setVisibility((!free && !paid) ? View.VISIBLE : View.GONE);
        }
    }

    private RadioGroup queryModeGroup() {
        RadioGroup g = new RadioGroup(this);
        RadioButton manual = radioTwoLine(getString(R.string.query_when_manual_title),
                getString(R.string.query_when_manual_sub));
        RadioButton auto = radioTwoLine(getString(R.string.query_when_auto_title),
                getString(R.string.query_when_auto_sub));
        RadioButton confirm = radioTwoLine(getString(R.string.query_when_confirm_title),
                getString(R.string.query_when_confirm_sub));
        g.addView(manual);
        g.addView(auto);
        g.addView(confirm);
        int cur = rules.queryMode();
        g.check(cur == Rules.QUERY_AUTO ? auto.getId()
                : cur == Rules.QUERY_CONFIRM ? confirm.getId() : manual.getId());
        g.setOnCheckedChangeListener((grp, id) -> {
            if (id == auto.getId()) rules.setQueryMode(Rules.QUERY_AUTO);
            else if (id == confirm.getId()) rules.setQueryMode(Rules.QUERY_CONFIRM);
            else rules.setQueryMode(Rules.QUERY_MANUAL);
        });
        return g;
    }

    private void runOnlineLookup() {
        String key = rules.onlineKey();
        String digits = onlineInput.getText().toString().replaceAll("[^0-9]", "");
        if (digits.length() < 5) {
            toast(getString(R.string.toast_allow_bad));
            return;
        }
        onlineBtn.setEnabled(false);
        onlineBtn.setText(getString(R.string.online_querying));
        onlineResult.setVisibility(View.VISIBLE);
        onlineResult.setText(getString(R.string.online_querying));

        final String fKey = key;
        final String fDigits = digits;
        new Thread(() -> {
            Online.Result r = Online.query(getApplicationContext(), fKey,
                    rules.onlineFree(), rules.onlinePaid(), fDigits);
            runOnUiThread(() -> {
                if (onlineBtn != null) {
                    onlineBtn.setEnabled(true);
                    onlineBtn.setText(getString(R.string.online_query_btn));
                }
                if (!r.ok) {
                    String msg = r.error == null ? "" : r.error;
                    if (msg.contains("Permission denied") || msg.contains("Security")) {
                        msg = getString(R.string.online_no_internet);
                    }
                    onlineResult.setText(getString(R.string.online_fail, msg));
                    return;
                }
                if (!r.fromCache && !fKey.trim().isEmpty()) rules.bumpOnlineUsed();
                refreshOnlineUsed();
                onlineResult.setText(r.fromCache
                        ? r.text + "\n" + getString(fKey.trim().isEmpty()
                                ? R.string.online_cached_free : R.string.online_cached)
                        : r.text);
            });
        }, "online-lookup").start();
    }

    private View buildLogCard() {        LinearLayout card = card(getString(R.string.section_log));
        logView = text("", 12, colOnSurfaceVariant, false);
        logView.setTypeface(Typeface.MONOSPACE);
        card.addView(logView);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = pill(getString(R.string.btn_refresh), false);
        Button clearLog = pill(getString(R.string.btn_clear), false);
        buttons.addView(refresh, weightLp(1f, 0, dp(44)));
        buttons.addView(clearLog, weightLp(1f, dp(10), dp(44)));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        btnLp.topMargin = dp(12);
        buttons.setLayoutParams(btnLp);
        refresh.setOnClickListener(v -> refreshLog());
        clearLog.setOnClickListener(v -> {
            BlockLog.clear(this);
            refreshLog();
        });
        card.addView(buttons);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        return card;
    }

    private View buildHistoryCard() {
        LinearLayout card = card(getString(R.string.section_history));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        card.addView(historyList);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = pill(getString(R.string.btn_refresh), false);
        Button clear = pill(getString(R.string.btn_clear), false);
        refresh.setOnClickListener(v -> refreshHistory());
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    HistoryLog.clear(this);
                    refreshHistory();
                })
                .show());
        buttons.addView(refresh, weightLp(1f, 0, dp(44)));
        buttons.addView(clear, weightLp(1f, dp(10), dp(44)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH, WRAP);
        blp.topMargin = dp(12);
        buttons.setLayoutParams(blp);
        card.addView(buttons);

        Button cached = pill(getString(R.string.btn_cached_labels), false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, dp(44));
        clp.topMargin = dp(8);
        cached.setLayoutParams(clp);
        cached.setOnClickListener(v -> showCachedDialog());
        card.addView(cached);

        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        refreshHistory();
        return card;
    }

    private void refreshHistory() {
        if (historyList == null) return;
        historyList.removeAllViews();
        java.util.List<HistoryLog.Entry> list = HistoryLog.recent(this, 20);
        if (list.isEmpty()) {
            historyList.addView(text(getString(R.string.history_empty), 12, colOnSurfaceVariant, false));
            return;
        }
        for (HistoryLog.Entry e : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView top = text(e.time() + "   " + (e.digits.isEmpty() ? "-" : e.digits),
                    12, colOnSurface, false);
            top.setTypeface(Typeface.MONOSPACE);
            row.addView(top);

            String sub = e.blocked ? getString(R.string.history_blocked)
                    : getString(R.string.history_allowed);
            if (!e.reason.isEmpty()) sub = sub + " · " + e.reason;
            row.addView(text(sub, 11, colOnSurfaceVariant, false));

            row.setBackground(ripple());
            row.setOnClickListener(v -> showCallDetail(e));
            historyList.addView(row);
        }
    }

    /** 点一条历史，看到归属地 / 原因 / 缓存里的标记。 */
    private void showCallDetail(HistoryLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        String region = "";
        if (e.digits.length() >= 7) {
            Db db = Db.get(this);
            Db.Rec rec = db == null ? null : db.lookup(Integer.parseInt(e.digits.substring(0, 7)));
            if (rec != null) region = Geo.provinceName(rec.province) + " " + rec.carrierName(this);
        }
        sb.append(getString(R.string.detail_time)).append(": ").append(e.time()).append('\n');
        if (!region.isEmpty()) sb.append(getString(R.string.detail_region)).append(": ").append(region).append('\n');
        sb.append(getString(R.string.detail_verdict)).append(": ")
                .append(e.blocked ? getString(R.string.history_blocked)
                        : getString(R.string.history_allowed)).append('\n');
        if (!e.reason.isEmpty()) {
            sb.append(getString(R.string.detail_reason)).append(": ").append(e.reason).append('\n');
        }
        String label = Online.cachedLabel(this, e.digits);
        String known = Directory.label(this, e.digits);
        String mine = ContactsLookup.ownName(this, e.digits);
        if (mine != null) sb.append(getString(R.string.detail_own)).append(": ").append(mine).append('\n');
        if (known != null) sb.append(getString(R.string.detail_known)).append(": ").append(known).append('\n');
        if (label != null) sb.append(getString(R.string.detail_cached)).append(": ").append(label);

        final String regionArg = region;
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.digits.isEmpty() ? getString(R.string.log_hidden_number) : e.digits)
                .setMessage(sb.toString().trim())
                .setNegativeButton(R.string.btn_close, null)
                .setPositiveButton(R.string.detail_query, (d, w) -> {
                    if (e.digits.length() < 5) return;
                    new Thread(() -> LookupReceiver.lookup(getApplicationContext(), e.digits, regionArg),
                            "cg-lookup").start();
                })
                .show();
    }

    /** 缓存里所有还没过期的在线标记。 */
    private void showCachedDialog() {
        java.util.List<String[]> list = Online.cacheEntries(this);
        if (list.isEmpty()) {
            toast(getString(R.string.cached_empty));
            return;
        }
        String[] items = new String[list.size()];
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        for (int i = 0; i < list.size(); i++) {
            String[] row = list.get(i);
            String shortLabel = row[1].isEmpty() ? getString(R.string.online_clean) : row[1];
            items[i] = row[0] + "  ·  " + shortLabel + "  ·  "
                    + f.format(new java.util.Date(Long.parseLong(row[3])));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.section_cached))
                .setItems(items, (d, which) -> new android.app.AlertDialog.Builder(this)
                        .setTitle(list.get(which)[0])
                        .setMessage(list.get(which)[2])
                        .setPositiveButton(R.string.btn_close, null)
                        .show())
                .setNegativeButton(R.string.btn_close, null)
                .show();
    }

    private View buildDbCard() {
        LinearLayout card = card(getString(R.string.section_db));
        card.addView(rowIconText(R.drawable.ic_cg_db, "phone.dat", getString(R.string.db_desc)));

        dbStatus = text("", 13, colOnSurfaceVariant, false);
        dbStatus.setPadding(0, dp(8), 0, 0);
        card.addView(dbStatus);
        refreshDbStatus();

        Button importDb = pill(getString(R.string.btn_import), false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(44));
        lp.topMargin = dp(12);
        importDb.setLayoutParams(lp);
        importDb.setOnClickListener(v -> pickDbFile());
        card.addView(importDb);

        Button getDb = pill(getString(R.string.btn_get_db), false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(MATCH, dp(44));
        gp.topMargin = dp(8);
        getDb.setLayoutParams(gp);
        getDb.setOnClickListener(v -> openDbSource());
        card.addView(getDb);

        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        return card;
    }

    private void refreshDbStatus() {
        if (dbStatus == null) return;
        Db db = Db.get(this);
        if (db == null) {
            dbStatus.setText(getString(R.string.db_missing));
        } else {
            dbStatus.setText(getString(R.string.db_loaded,
                    db.version(), String.format("%,d", db.recordCount())));
        }
    }

    private void openDbSource() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/xluohome/phonedata")));
        } catch (Throwable t) {
            toast(getString(R.string.toast_no_browser));
        }
    }

    private View buildAboutCard() {
        LinearLayout card = card(getString(R.string.section_about));
        String ver = "?";
        long code = 0;
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = pi.getLongVersionCode();
        } catch (Throwable ignored) {
        }
        card.addView(kv(getString(R.string.about_version), ver + " (" + code + ")"));
        card.addView(kv(getString(R.string.about_build),
                getString(hasInternet ? R.string.about_build_online : R.string.about_build_offline)));
        card.addView(kv(getString(R.string.about_package), getPackageName()));
        card.addView(kv(getString(R.string.about_account), AccountSetup.NAME + " · " + AccountSetup.TYPE));
        Db db = Db.get(this);
        if (db != null) {
            card.addView(kv(getString(R.string.about_db),
                    "v" + db.version() + " · " + String.format("%,d", db.recordCount())));
        }
        TextView note = text(getString(R.string.about_note), 12, colOnSurfaceVariant, false);
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        return card;
    }

    /** 一行「标签 → 值」，用于关于卡片。 */
    private View kv(String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));

        TextView l = text(label, 13, colOnSurfaceVariant, false);
        r.addView(l, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView v = text(value, 13, colOnSurface, false);
        v.setGravity(Gravity.END);
        v.setTextIsSelectable(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        vlp.setMarginStart(dp(12));
        r.addView(v, vlp);
        return r;
    }

    // ================= 组件工厂 =================

    private void resolveColors() {
        TypedArray ta = getTheme().obtainStyledAttributes(new int[]{
                android.R.attr.colorAccent,
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
        });
        colAccent = ta.getColor(0, 0xFF1565C0);
        colOnSurface = ta.getColor(1, dark ? 0xFFE6E1E5 : 0xFF1B1B1F);
        colOnSurfaceVariant = ta.getColor(2, dark ? 0xFFC4C7C5 : 0xFF44474E);
        ta.recycle();
        colError = 0xFFB3261E;
        colSurface = blend(colAccent, colorBg(), dark ? 0.10f : 0.06f);
        colSurfaceVariant = blend(colAccent, colorBg(), dark ? 0.18f : 0.10f);
        colOutline = blend(colOnSurface, colorBg(), dark ? 0.22f : 0.12f);
    }

    private int colorBg() {
        TypedArray ta = getTheme().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
        int c = ta.getColor(0, dark ? 0xFF141218 : 0xFFFEFBFF);
        ta.recycle();
        return c;
    }

    private static int blend(int fg, int bg, float alpha) {
        int r = (int) (Color.red(fg) * alpha + Color.red(bg) * (1 - alpha));
        int g = (int) (Color.green(fg) * alpha + Color.green(bg) * (1 - alpha));
        int b = (int) (Color.blue(fg) * alpha + Color.blue(bg) * (1 - alpha));
        return Color.rgb(r, g, b);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable round(float radiusDp, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int) radiusDp));
        return d;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private CharSequence twoLine(String title, String sub) {
        SpannableString ss = new SpannableString(title + "\n" + sub);
        int start = title.length() + 1;
        ss.setSpan(new RelativeSizeSpan(0.78f), start, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(colOnSurfaceVariant), start, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private LinearLayout card(String header) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(24, colSurface));
        c.setPadding(dp(16), header == null ? dp(4) : dp(12), dp(16), dp(8));
        if (header != null) {
            TextView h = text(header, 13, colAccent, true);
            h.setLetterSpacing(0.05f);
            h.setPadding(0, dp(2), 0, dp(6));
            c.addView(h);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView subheader(String s) {
        TextView t = text(s, 13, colAccent, true);
        t.setLetterSpacing(0.04f);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(colOutline);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 1);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    private ImageView icon(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(colOnSurfaceVariant);
        return iv;
    }

    private View rowIconText(int iconRes, String title, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        ImageView iv = icon(iconRes);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ivLp.setMarginEnd(dp(12));
        r.addView(iv, ivLp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, colOnSurface, false));
        if (sub != null) {
            TextView s = text(sub, 12, colOnSurfaceVariant, false);
            s.setPadding(0, dp(1), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        return r;
    }

    private View toggleRow(String title, String sub, boolean checked, Consumer<Boolean> onChange) {
        return toggleRowWith(title, sub, checked, new Switch(this), onChange);
    }

    private View toggleRowWith(String title, String sub, boolean checked, Switch sw,
                               Consumer<Boolean> onChange) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        r.setMinimumHeight(dp(56));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, colOnSurface, false));
        if (sub != null) {
            TextView s = text(sub, 12, colOnSurfaceVariant, false);
            s.setPadding(0, dp(1), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        sw.setChecked(checked);
        sw.setClickable(false);
        sw.setFocusable(false);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        swLp.setMarginStart(dp(12));
        r.addView(sw, swLp);

        r.setBackground(ripple());
        r.setOnClickListener(v -> {
            boolean target = !sw.isChecked();
            sw.setChecked(target);
            onChange.accept(target);
        });
        return r;
    }

    private RadioGroup actionGroup() {
        RadioGroup g = new RadioGroup(this);
        RadioButton reject = radioTwoLine(getString(R.string.action_reject_title),
                getString(R.string.action_reject_sub));
        RadioButton silence = radioTwoLine(getString(R.string.action_silence_title),
                getString(R.string.action_silence_sub));
        g.addView(reject);
        g.addView(silence);
        g.check(rules.action() == Rules.ACTION_SILENCE ? silence.getId() : reject.getId());
        g.setOnCheckedChangeListener((grp, id) ->
                rules.setAction(id == silence.getId() ? Rules.ACTION_SILENCE : Rules.ACTION_REJECT));
        return g;
    }

    private RadioButton radioTwoLine(String title, String sub) {
        RadioButton r = new RadioButton(this);
        r.setText(twoLine(title, sub));
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        r.setTextColor(colOnSurface);
        r.setId(View.generateViewId());
        r.setPadding(0, dp(8), 0, dp(8));
        r.setLineSpacing(dp(2), 1f);
        return r;
    }

    private Button pill(String label, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        if (filled) {
            b.setBackground(round(999, colAccent));
            b.setTextColor(pickOnAccent());
        } else {
            GradientDrawable outline = round(999, Color.TRANSPARENT);
            outline.setStroke(dp(1), blend(colAccent, colorBg(), 0.5f));
            b.setBackground(outline);
            b.setTextColor(colAccent);
        }
        return b;
    }

    private int pickOnAccent() {
        int lum = (int) (0.299 * Color.red(colAccent) + 0.587 * Color.green(colAccent) + 0.114 * Color.blue(colAccent));
        return lum > 150 ? 0xFF1B1B1F : 0xFFFFFFFF;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(colOnSurfaceVariant);
        e.setTextColor(colOnSurface);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setSingleLine(true);
        e.setBackground(round(14, colSurfaceVariant));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(blend(colAccent, colorBg(), 0.18f)), null, null);
    }

    private LinearLayout.LayoutParams weightLp(float weight, int startMargin, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, weight);
        lp.setMarginStart(startMargin);
        return lp;
    }

    private LinearLayout.LayoutParams fixedLp(int startMargin, int height, int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMarginStart(startMargin);
        return lp;
    }

    // ================= 状态 / 权限 =================

    private void requestRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            toast(getString(R.string.toast_role_unsupported));
            return;
        }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            toast(getString(R.string.toast_already_granted));
            refreshStatus();
            return;
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importDb(data.getData());
        }
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALLLOG) {
            refreshEmergencyStatus();
        }
        if (requestCode == REQ_CONTACTS) {
            refreshContactsStatus();
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) toast(getString(R.string.contacts_white_off));
        }
        if (requestCode == REQ_NOTIFY) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                rules.setNotifyOnCall(false);
                if (notifySwitch != null) notifySwitch.setChecked(false);
                toast(getString(R.string.toast_notify_denied));
            }
        }
        if (requestCode == REQ_WRITE_CONTACTS) {
            boolean granted = grantResults.length >= 2
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) doWriteContacts();
            else toast(getString(R.string.toast_contacts_write_denied));
        }
    }

    private void refreshStatus() {
        RoleManager rm = getSystemService(RoleManager.class);
        boolean held = rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        if (statusChip != null) {
            statusChip.setText(getString(held ? R.string.chip_granted : R.string.chip_denied));
            statusChip.setTextColor(held ? colAccent : colError);
            statusChip.setBackground(round(999, blend(held ? colAccent : colError, colorBg(), 0.14f)));
        }
        if (roleButton != null) roleButton.setVisibility(held ? View.GONE : View.VISIBLE);
        if (statusSub != null) {
            statusSub.setText(getString(held ? R.string.status_sub_granted : R.string.status_sub_denied));
        }
        refreshHeader();
    }

    /** 号段库可能在运行中导入/替换，标题副行不能停留在启动时的状态。 */
    private void refreshHeader() {
        if (headSub == null) return;
        Db db = Db.get(this);
        if (db == null) {
            headSub.setText(getString(R.string.db_not_loaded));
            return;
        }
        headSub.setText(getString(hasInternet ? R.string.header_subtitle_online : R.string.header_subtitle,
                db.version(), String.format("%,d", db.recordCount())));
    }

    // ================= 地区 =================

    private void refreshRegionView() {
        if (regionView == null) return;
        Set<String> regions = rules.regions();
        if (regions.isEmpty()) {
            regionView.setText(getString(R.string.region_empty_black));
            return;
        }
        Map<String, List<String>> byProv = new TreeMap<>();
        for (String k : regions) {
            String[] p = k.split("\\|", 2);
            byProv.computeIfAbsent(p[0], x -> new ArrayList<>()).add(p.length > 1 ? p[1] : "*");
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> e : byProv.entrySet()) {
            if (!first) sb.append("　·　");
            first = false;
            sb.append(Geo.provinceName(e.getKey())).append(" ");
            if (e.getValue().contains("*")) {
                sb.append(getString(R.string.region_whole_province));
            } else {
                List<String> cities = new ArrayList<>(e.getValue());
                for (int i = 0; i < cities.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(Geo.cityName(cities.get(i)));
                }
            }
        }
        sb.append("\n").append(getString(R.string.region_count, regions.size()));
        regionView.setText(sb.toString());
    }

    private void showProvinceDialog() {
        if (provinceCities.isEmpty()) {
            if (Db.get(this) == null) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.db_missing_title)
                        .setMessage(R.string.db_missing)
                        .setPositiveButton(R.string.btn_get_db, (d, w) -> openDbSource())
                        .setNeutralButton(R.string.btn_import, (d, w) -> pickDbFile())
                        .setNegativeButton(R.string.btn_close, null)
                        .show();
            } else {
                toast(getString(R.string.toast_db_loading));
            }
            return;
        }
        List<String> provs = new ArrayList<>(provinceCities.keySet());
        Set<String> regions = rules.regions();
        String[] items = new String[provs.size()];
        for (int i = 0; i < provs.size(); i++) {
            String p = provs.get(i);
            boolean selected = false;
            for (String k : regions) {
                if (k.startsWith(p + "|")) {
                    selected = true;
                    break;
                }
            }
            items[i] = getString(selected ? R.string.province_item_selected : R.string.province_item,
                    Geo.provinceName(p));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_pick_province)
                .setItems(items, (d, which) -> showCityDialog(provs.get(which)))
                .setNegativeButton(R.string.btn_close, null)
                .show();
    }

    private void showCityDialog(String province) {
        List<String> cities = provinceCities.get(province);
        if (cities == null || cities.isEmpty()) {
            toast(getString(R.string.toast_no_city));
            return;
        }
        String[] entries = new String[cities.size() + 1];
        boolean[] checked = new boolean[cities.size() + 1];
        Set<String> regions = rules.regions();
        entries[0] = getString(R.string.dialog_whole_province);
        checked[0] = regions.contains(province + "|*");
        for (int i = 0; i < cities.size(); i++) {
            entries[i + 1] = Geo.cityName(cities.get(i));
            checked[i + 1] = regions.contains(province + "|" + cities.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(Geo.provinceName(province))
                .setMultiChoiceItems(entries, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    for (String k : new ArrayList<>(regions)) {
                        if (k.startsWith(province + "|")) regions.remove(k);
                    }
                    if (checked[0]) {
                        regions.add(province + "|*");
                    } else {
                        for (int i = 0; i < cities.size(); i++) {
                            if (checked[i + 1]) regions.add(province + "|" + cities.get(i));
                        }
                    }
                    rules.setRegions(regions);
                    refreshRegionView();
                })
                .setNeutralButton(R.string.btn_clear_province, (d, w) -> {
                    for (String k : new ArrayList<>(regions)) {
                        if (k.startsWith(province + "|")) regions.remove(k);
                    }
                    rules.setRegions(regions);
                    refreshRegionView();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ================= 日志 / 号段库 =================

    private void refreshLog() {
        if (logView == null) return;
        String log = BlockLog.read(this);
        if (log.isEmpty()) {
            logView.setText(getString(R.string.log_empty));
            return;
        }
        String[] lines = log.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 8); i++) sb.append(lines[i]).append('\n');
        logView.setText(sb.toString().trim());
    }

    private void loadProvinceCities() {
        new Thread(() -> {
            Db db = Db.get(getApplicationContext());
            Map<String, List<String>> pc = db == null
                    ? new LinkedHashMap<>()
                    : db.provinceCities(getApplicationContext());
            runOnUiThread(() -> {
                provinceCities = pc;
                refreshRegionView();
            });
        }, "cities").start();
    }

    private void pickDbFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            toast(getString(R.string.toast_no_picker));
        }
    }

    private void importDb(Uri uri) {
        File tmp = new File(getCacheDir(), "import.dat");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new java.io.IOException("null stream");
            byte[] b = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > 64L * 1024 * 1024) throw new java.io.IOException("file too large");
                out.write(b, 0, n);
            }
        } catch (Exception e) {
            toast(getString(R.string.toast_import_fail, String.valueOf(e)));
            return;
        }
        boolean ok = Db.importFrom(this, tmp);
        tmp.delete();
        toast(getString(ok ? R.string.toast_import_ok : R.string.toast_import_bad));
        if (ok) {
            Db.preload(this);
            refreshDbStatus();
            refreshStatus();
            loadProvinceCities();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
