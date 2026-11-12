package com.easypt.gui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Easy-platform-tools-GUI · Android 版。
 * 三种模式：OTG 主机（刷别人）/ 本机 root（管自己）/ 遥控 PC，功能对齐桌面版。
 */
public class MainActivity extends Activity {

    private static final int MODE_OTG = 0, MODE_LOCAL = 1, MODE_REMOTE = 2;
    private static final int REQ_IMAGE = 1001, REQ_ROM = 1002, REQ_ROM_DIR = 1003;
    private static final String ACTION_USB_PERM = "com.easypt.gui.USB_PERMISSION";
    private static final String VER = "1.1";

    /** 横屏（桌面版布局）开关，存在 prefs 里 */
    private boolean landscapeUi;

    /** 点了「停止」后置位 */
    private volatile boolean stopRequested;

    /** 模拟线刷：不碰 USB，只跑流程（假包 + 假设备） */
    private boolean dryRun;

    private AlertDialog settingsDlg;

    /** 日志滚动容器，append 时要贴到底部 */
    private ScrollView logScroll;

    /** 8 套背景色，和桌面版一致 */
    private static final int[] BG = {
            0xFF0E1117, 0xFF000000, 0xFF1F2024, 0xFF0B1E2D,
            0xFF0F221A, 0xFF25131A, 0xFFF3F4F6, 0xFFF6F1E4
    };

    private int mode = MODE_OTG;
    private int themeIdx = 0;
    private float scale = 1.0f;
    private String lang = "zh";

    private int cBg, cPanel, cText, cDim, cAccent, cOk, cErr, cBtn, cRun, cInput, cOut;

    private SharedPreferences store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FastbootUsb fb;
    private UsbDevice pendingDevice;
    private String pendingPart = "boot";

    private TextView logView, statusView;
    private Spinner modeSpinner;
    private GridLayout grid;
    private EditText input;
    private SpannableStringBuilder logBuf = new SpannableStringBuilder();

    private final List<String[]> lastParts = new ArrayList<String[]>();
    private final List<String> history = new ArrayList<String>();
    private final boolean[] bakPick = { true, true, true, true, true, false, false, false };
    private static final String[] BAK_PARTS = { "modem", "modemst1", "modemst2", "fsg", "fsc", "persist", "bluetooth", "dsp" };

    private String T(String zh, String en) { return "en".equals(lang) ? en : zh; }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = getSharedPreferences("easypT", MODE_PRIVATE);
        mode = store.getInt("mode", -1);
        themeIdx = store.getInt("theme", 0);
        scale = store.getFloat("scale", 1.0f);
        lang = store.getString("lang", "zh");
        pendingPart = store.getString("part", "boot");
        landscapeUi = store.getBoolean("landscape", false);
        setRequestedOrientation(landscapeUi
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyColors();

        buildUi();
        applyWindowInsets();
        // Android 13+ 注册非系统专用广播必须显式声明导出性，否则直接 SecurityException
        IntentFilter permFilter = new IntentFilter(ACTION_USB_PERM);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, permFilter);
        }
        fb = new FastbootUsb(new FastbootUsb.Logger() {
            public void log(String line) { append(line, guessColor(line)); }
        });

        if (mode < 0) { chooseMode(true); } else { applyMode(mode); }
        if (!store.getBoolean("layoutChosen", false)) { chooseLayoutOnFirstRun(); }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        if (fb != null) { fb.close(); }
        super.onDestroy();
    }

    // ---------------- 配色 ----------------

    private void applyColors() {
        int bg = BG[Math.max(0, Math.min(BG.length - 1, themeIdx))];
        double lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg);
        boolean dark = lum < 128;
        cBg = bg;
        cPanel = blend(bg, Color.WHITE, 0.07);
        cOut = blend(bg, Color.BLACK, 0.35);
        cInput = blend(bg, Color.BLACK, 0.25);
        cBtn = dark ? blend(bg, Color.WHITE, 0.13) : blend(bg, Color.BLACK, 0.09);
        if (!dark) { cPanel = blend(bg, Color.BLACK, 0.05); cInput = blend(bg, Color.WHITE, 0.8); cOut = blend(bg, Color.WHITE, 0.6); }
        cText = dark ? 0xFFD6DBE4 : 0xFF1C2028;
        cDim = dark ? 0xFF848C9A : 0xFF6C7482;
        cAccent = dark ? 0xFF7AB2FF : 0xFF1A58BE;
        cOk = dark ? 0xFF7ED6A0 : 0xFF12764A;
        cErr = dark ? 0xFFF08C8C : 0xFFAF2828;
        cRun = dark ? 0xFF2E5C96 : 0xFF2663C6;
    }

    private static int blend(int a, int b, double f) {
        int r = (int) Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f);
        int g = (int) Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f);
        int bl = (int) Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return Color.rgb(r, g, bl);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---------------- UI ----------------

    private void buildUi() {
        if (landscapeUi) { buildDesktopUi(); } else { buildPortraitUi(); }
    }

    /** 1.9.0 原来的竖屏布局 */
    /** 竖屏必须显示状态栏（横屏才隐藏，切回来时要恢复） */
    private void showStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) { wic.show(WindowInsets.Type.statusBars()); }
        }
    }

    private void buildPortraitUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText("Easy-platform-tools");
        title.setTextColor(cText);
        title.setTextSize(18 * scale);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(6));

        modeSpinner = new Spinner(this);
        ArrayAdapter<String> ad = modeAdapter(new String[]{
                T("OTG 主机：刷别人", "OTG host: flash another"),
                T("本机 root：管自己", "Local root: manage itself"),
                T("遥控 PC", "Remote PC")
        });
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(ad);
        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (mode != pos) { applyMode(pos); }
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        row.addView(modeSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button settings = smallBtn(T("设置", "Settings"));
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
        row.addView(settings);
        root.addView(row);

        statusView = new TextView(this);
        statusView.setTextColor(cDim);
        statusView.setTextSize(12 * scale);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        logScroll = scroll;
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(6);
        scroll.setLayoutParams(slp);
        scroll.setBackgroundColor(cOut);

        logView = new TextView(this);
        logView.setTextColor(cText);
        logView.setTextSize(11 * scale);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll);

        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(0, dp(8), 0, 0);
        root.addView(grid);

        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(0, dp(6), 0, 0);
        tools.addView(smallBtn(T("清空", "Clear"), new View.OnClickListener() {
            public void onClick(View v) { logBuf = new SpannableStringBuilder(); logView.setText(""); }
        }));
        tools.addView(smallBtn(T("存日志", "Save log"), new View.OnClickListener() {
            public void onClick(View v) { saveLog(); }
        }));
        tools.addView(smallBtn(T("历史", "History"), new View.OnClickListener() {
            public void onClick(View v) { showHistory(); }
        }));
        tools.addView(smallBtn(T("关于", "About"), new View.OnClickListener() {
            public void onClick(View v) { showAbout(); }
        }));
        root.addView(tools);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setPadding(0, dp(6), 0, 0);

        input = new EditText(this);
        input.setHint(T("输入命令", "Type a command"));
        input.setHintTextColor(cDim);
        input.setTextColor(cText);
        input.setTextSize(13 * scale);
        input.setSingleLine(true);
        input.setBackgroundColor(cInput);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button runBtn = smallBtn(T("执行", "Run"));
        runBtn.setBackgroundColor(cRun);
        runBtn.setTextColor(Color.WHITE);
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTyped(); }
        });
        inputRow.addView(runBtn);
        root.addView(inputRow);

        setContentView(root);
        showStatusBar();
    }
    /**
     * 横屏：照电脑版（Easy-platform-tools-GUI 桌面端）排版
     *   左栏：快捷命令 / 刷写与备份 分组按钮，左下角「设置」
     *   右侧：状态行 + 大日志区 + 提示行 +「模式 | 输入 | 执行 | 停止」+「清空 / 存日志」
     * 横屏隐藏状态栏，内容顶到边。
     */
    private void buildDesktopUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Easy-platform-tools");
        title.setTextColor(cText);
        title.setTextSize(13 * scale);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setTextColor(cDim);
        statusView.setTextSize(11 * scale);
        statusView.setGravity(Gravity.END);
        top.addView(statusView);
        root.addView(top);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(4), 0, 0);
        body.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        leftScroll.setFocusable(false);
        grid = new GridLayout(this);
        grid.setColumnCount(1);
        grid.setPadding(0, 0, dp(6), 0);
        grid.setFocusable(false);
        // ScrollView 里 wrap_content 会让 GridLayout 的权重列塌成 0 宽，必须显式 MATCH_PARENT
        leftScroll.addView(grid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        leftCol.addView(leftScroll);
        final ScrollView leftRef = leftScroll;
        leftScroll.post(new Runnable() {
            public void run() { leftRef.scrollTo(0, 0); }
        });

        Button settings = smallBtn(T("设置", "Settings"));
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setLp.topMargin = dp(6);
        leftCol.addView(settings, setLp);
        body.addView(leftCol);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        ScrollView scroll = new ScrollView(this);
        logScroll = scroll;
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setBackgroundColor(cOut);

        logView = new TextView(this);
        logView.setTextColor(cText);
        logView.setTextSize(10 * scale);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        right.addView(scroll);

        TextView hint = new TextView(this);
        hint.setText(T("↑↓ 翻历史 · 以 adb / fastboot 开头则按原样执行，否则套用左侧模式",
                "Up/Down: history · adb / fastboot lines run as-is"));
        hint.setTextColor(cDim);
        hint.setTextSize(10 * scale);
        hint.setPadding(0, dp(4), 0, 0);
        right.addView(hint);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(4), 0, 0);

        modeSpinner = new Spinner(this);
        ArrayAdapter<String> ad = modeAdapter(new String[]{
                T("OTG 主机", "OTG host"),
                T("本机 root", "Local root"),
                T("遥控 PC", "Remote PC")
        });
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(ad);
        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (mode != pos) { applyMode(pos); }
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        inputRow.addView(modeSpinner, new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));

        input = new EditText(this);
        input.setHint(T("输入命令", "Type a command"));
        input.setHintTextColor(cDim);
        input.setTextColor(cText);
        input.setTextSize(12 * scale);
        input.setSingleLine(true);
        input.setBackgroundColor(cInput);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button runBtn = smallBtn(T("执行", "Run"));
        runBtn.setBackgroundColor(cRun);
        runBtn.setTextColor(Color.WHITE);
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTyped(); }
        });
        inputRow.addView(runBtn);

        Button stopBtn = smallBtn(T("停止", "Stop"));
        stopBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopAll(); }
        });
        inputRow.addView(stopBtn);
        right.addView(inputRow);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.END);
        tools.setPadding(0, dp(4), 0, 0);
        tools.addView(smallBtn(T("清空", "Clear"), new View.OnClickListener() {
            public void onClick(View v) { logBuf = new SpannableStringBuilder(); logView.setText(""); }
        }));
        tools.addView(smallBtn(T("存日志", "Save log"), new View.OnClickListener() {
            public void onClick(View v) { saveLog(); }
        }));
        tools.addView(smallBtn(T("历史", "History"), new View.OnClickListener() {
            public void onClick(View v) { showHistory(); }
        }));
        tools.addView(smallBtn(T("关于", "About"), new View.OnClickListener() {
            public void onClick(View v) { showAbout(); }
        }));
        right.addView(tools);

        body.addView(right);
        root.addView(body);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                wic.hide(WindowInsets.Type.statusBars());
                wic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    /** 停止：断开当前 OTG 会话，并让后续流程不再继续 */
    private void stopAll() {
        stopRequested = true;
        try { if (fb != null) { fb.close(); } } catch (Throwable ignored) { }
        append(T("已停止", "Stopped"), cErr);
    }

    /**
     * targetSdk 35+ 强制边到边（edge-to-edge），系统栏区域压在内容上：
     * 标题会顶进状态栏、底部输入框会被手势条/键盘挡住。
     * 这里按 insets 给根视图补上下内边距（1.9.0 当年没这一步）。
     */
    private void applyWindowInsets() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        final View rootView = content.getChildAt(0);
        rootView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                    int nb = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    bottom = Math.max(nb, ime);
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
        rootView.requestApplyInsets();
    }

    private Button smallBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        // 竖屏按钮做大一点，横屏照电脑版做紧凑
        b.setTextSize((landscapeUi ? 11 : 14) * scale);
        b.setTextColor(cText);
        b.setBackgroundColor(cBtn);
        int vpad = landscapeUi ? dp(2) : dp(10);
        b.setPadding(dp(landscapeUi ? 8 : 10), vpad, dp(landscapeUi ? 8 : 10), vpad);
        int minH = landscapeUi ? dp(28) : dp(48);
        b.setMinHeight(minH);
        b.setMinimumHeight(minH);
        return b;
    }

    private Button smallBtn(String text, View.OnClickListener l) {
        Button b = smallBtn(text);
        b.setOnClickListener(l);
        return b;
    }

    /** 横屏左栏的分组标题（电脑版里的「快捷命令」「刷写与备份」） */
    private void groupLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(cDim);
        tv.setTextSize(11 * scale);
        // 必须和 act() 的按钮用同一套「宽度 0 + 列权重 1」参数：
        // 混一个 wrap_content 的子视图会把整列宽度钉死成标题宽度，按钮全被压成 0 宽。
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(4), dp(10), 0, dp(2));
        tv.setLayoutParams(lp);
        grid.addView(tv);
    }

    private void act(String label, final Runnable r) {
        Button b = smallBtn(label);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        grid.addView(b);
    }

    /** 模式下拉框：默认的 simple_spinner_item 字体跟着系统走，在横屏里显得特别大 */
    private ArrayAdapter<String> modeAdapter(String[] items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) { ((TextView) v).setTextSize(13 * scale); }
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) { ((TextView) v).setTextSize(14 * scale); }
                return v;
            }
        };
    }
    private void applyMode(int m) {
        mode = m;
        store.edit().putInt("mode", m).apply();
        if (modeSpinner.getSelectedItemPosition() != m) { modeSpinner.setSelection(m); }
        grid.removeAllViews();
        lastParts.clear();

        if (m == MODE_OTG) {
            statusView.setText(T("OTG：用 OTG 线连接另一台手机，让它进入 fastboot", "OTG: connect another phone in fastboot"));
            act(T("连接设备", "Connect"), new Runnable() { public void run() { connectUsb(); } });
            act(T("设备信息", "Device info"), new Runnable() { public void run() { otgInfo(); } });
            act(T("读分区表", "Partitions"), new Runnable() { public void run() { otgPartitions(); } });
            if (landscapeUi) { groupLabel(T("刷写与备份", "Flash & backup")); }
            act(T("刷入镜像", "Flash image"), new Runnable() { public void run() { pickImage(); } });
            act(T("线刷 ROM", "Flash ROM"), new Runnable() { public void run() { pickRom(); } });
            act(T("擦除分区", "Erase"), new Runnable() { public void run() { otgErase(); } });
            act(T("重启系统", "Reboot"), new Runnable() { public void run() { otgReboot(""); } });
            act(T("重启 Recovery", "Reboot Recovery"), new Runnable() { public void run() { otgReboot("recovery"); } });
            act(T("重启 Bootloader", "Reboot Bootloader"), new Runnable() { public void run() { otgReboot("bootloader"); } });
            act(T("进入 EDL", "Enter EDL"), new Runnable() { public void run() { otgEdl(); } });
        } else if (m == MODE_LOCAL) {
            statusView.setText(T("本机：需要 root（Magisk / 内核 root）", "Local: requires root"));
            act(T("检测 root", "Check root"), new Runnable() { public void run() { checkRoot(); } });
            act(T("读分区表", "Partitions"), new Runnable() { public void run() { localPartitions(); } });
            act(T("备份基带", "Backup modem"), new Runnable() { public void run() { backupDialog(); } });
            if (landscapeUi) { groupLabel(T("刷写与备份", "Flash & backup")); }
            act(T("刷入镜像", "Flash image"), new Runnable() { public void run() { pickImage(); } });
            act(T("线刷 ROM", "Flash ROM"), new Runnable() { public void run() { pickRom(); } });
            act(T("重启系统", "Reboot"), new Runnable() { public void run() { localReboot(""); } });
            act(T("重启 Recovery", "Reboot Recovery"), new Runnable() { public void run() { localReboot("recovery"); } });
            act(T("重启 EDL", "Reboot EDL"), new Runnable() { public void run() { localReboot("edl"); } });
        } else {
            statusView.setText(T("遥控：手机和电脑同一局域网，电脑上启动「遥控服务」", "Remote: same Wi-Fi, start the service on the PC"));
            act(T("测试连接", "Test link"), new Runnable() { public void run() { remoteStatus(); } });
            act(T("读分区表", "Partitions"), new Runnable() { public void run() { remotePartitions(); } });
            if (landscapeUi) { groupLabel(T("刷写与备份", "Flash & backup")); }
            act(T("刷入镜像", "Flash image"), new Runnable() { public void run() { pickImage(); } });
            act(T("重启系统", "Reboot"), new Runnable() { public void run() { remoteReboot(""); } });
            act(T("重启 Recovery", "Reboot Recovery"), new Runnable() { public void run() { remoteReboot("recovery"); } });
            act(T("重启 Bootloader", "Reboot Bootloader"), new Runnable() { public void run() { remoteReboot("bootloader"); } });
        }
    }

    // ---------------- 日志 ----------------

    private int guessColor(String line) {
        if (line == null) { return cText; }
        String l = line.toLowerCase(Locale.US);
        if (l.contains("fail") || l.contains("失败") || l.contains("错误")) { return cErr; }
        if (l.startsWith("ok") || l.contains("成功") || l.contains("完成")) { return cOk; }
        if (line.startsWith(">")) { return cAccent; }
        return cText;
    }

    private void append(final String line, final int color) {
        ui.post(new Runnable() {
            public void run() {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                int start = logBuf.length();
                logBuf.append("[" + ts + "] " + line + "\n");
                logBuf.setSpan(new ForegroundColorSpan(color), start, logBuf.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                logView.setText(logBuf);
                if (logScroll != null) {
                    logScroll.post(new Runnable() {
                        public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                    });
                }
                if (logBuf.length() > 100000) {
                    logBuf.delete(0, logBuf.length() - 60000);
                    logView.setText(logBuf);
                if (logScroll != null) {
                    logScroll.post(new Runnable() {
                        public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                    });
                }
                }
            }
        });
    }

    private void saveLog() {
        try {
            File dir = getExternalFilesDir("logs");
            if (dir == null) { dir = getFilesDir(); }
            dir.mkdirs();
            File f = new File(dir, "log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
            FileOutputStream out = new FileOutputStream(f);
            out.write(logBuf.toString().getBytes("UTF-8"));
            out.close();
            Toast.makeText(this, f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            append(T("已保存：", "Saved: ") + f.getAbsolutePath(), cDim);
        } catch (Exception e) {
            append(T("保存失败：", "Save failed: ") + e.getMessage(), cErr);
        }
    }

    private void showHistory() {
        if (history.isEmpty()) { append(T("没有历史命令", "No history"), cDim); return; }
        final String[] arr = history.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(T("历史命令", "History"))
                .setItems(arr, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { input.setText(arr[w]); }
                }).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle(T("关于", "About"))
                .setMessage("Easy-platform-tools-GUI\nAndroid " + VER + "\n\n"
                        + T("三种模式：OTG 主机 / 本机 root / 遥控 PC\n开源许可 MIT",
                        "Three modes: OTG host / local root / remote PC\nLicense: MIT"))
                .setPositiveButton("OK", null).show();
    }

    // ---------------- 设置 ----------------

    private void openSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        box.addView(label(T("背景配色", "Background")));
        LinearLayout swatches = new LinearLayout(this);
        for (int i = 0; i < BG.length; i++) {
            final int idx = i;
            Button sw = new Button(this);
            sw.setBackgroundColor(BG[i]);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            slp.setMargins(dp(2), dp(2), dp(2), dp(2));
            sw.setLayoutParams(slp);
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { themeIdx = idx; store.edit().putInt("theme", idx).apply(); recreate(); }
            });
            swatches.addView(sw);
        }
        box.addView(swatches);

        box.addView(label(T("字号", "Font size")));
        LinearLayout sizes = new LinearLayout(this);
        String[] names = { T("小", "Small"), T("标准", "Normal"), T("大", "Large") };
        float[] vals = { 0.9f, 1.0f, 1.15f };
        for (int i = 0; i < 3; i++) {
            final float v = vals[i];
            Button b = smallBtn(names[i], new View.OnClickListener() {
                public void onClick(View x) { store.edit().putFloat("scale", v).apply(); recreate(); }
            });
            sizes.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(sizes);

        box.addView(label(T("语言", "Language")));
        LinearLayout langs = new LinearLayout(this);
        Button zh = smallBtn("中文", new View.OnClickListener() {
            public void onClick(View v) { store.edit().putString("lang", "zh").apply(); recreate(); }
        });
        Button en = smallBtn("English", new View.OnClickListener() {
            public void onClick(View v) { store.edit().putString("lang", "en").apply(); recreate(); }
        });
        langs.addView(zh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        langs.addView(en, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(langs);

        box.addView(label(T("模拟线刷（假包，不接设备）", "Dry run (fake package)")));
        Button fake = smallBtn(T("生成假包并模拟刷入", "Fake package + simulate"), new View.OnClickListener() {
            public void onClick(View v) {
                if (settingsDlg != null) { settingsDlg.dismiss(); }
                startSimulatedRom();
            }
        });
        box.addView(fake);

        box.addView(label(T("横屏模式（和电脑版一样）", "Landscape (desktop layout)")));
        LinearLayout orients = new LinearLayout(this);
        Button portraitBtn = smallBtn(T("竖屏", "Portrait"), new View.OnClickListener() {
            public void onClick(View v) {
                store.edit().putBoolean("landscape", false).apply();
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                recreate();
            }
        });
        Button landscapeBtn = smallBtn(T("横屏", "Landscape"), new View.OnClickListener() {
            public void onClick(View v) {
                store.edit().putBoolean("landscape", true).apply();
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                recreate();
            }
        });
        orients.addView(portraitBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        orients.addView(landscapeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(orients);

        box.addView(label(T("遥控 PC", "Remote PC")));
        final EditText host = new EditText(this);
        host.setHint("192.168.1.10:8765");
        host.setText(store.getString("host", ""));
        box.addView(host);

        final EditText token = new EditText(this);
        token.setHint(T("6 位配对码", "6-digit pairing code"));
        token.setText(store.getString("token", ""));
        box.addView(token);

        final EditText part = new EditText(this);
        part.setHint(T("默认分区 boot", "Default partition"));
        part.setText(store.getString("part", "boot"));
        box.addView(part);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        settingsDlg = new AlertDialog.Builder(this)
                .setTitle(T("设置", "Settings"))
                .setView(sv)
                .setPositiveButton(T("保存", "Save"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        store.edit()
                                .putString("host", host.getText().toString().trim())
                                .putString("token", token.getText().toString().trim())
                                .putString("part", part.getText().toString().trim())
                                .apply();
                        append(T("设置已保存", "Settings saved"), cDim);
                    }
                })
                .setNeutralButton(T("切换模式", "Switch mode"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { chooseMode(false); }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .create();
        settingsDlg.show();
        // 对话框窗口默认是半透明的（背景会透出主界面，字看不清），铺一层主题底色
        if (settingsDlg.getWindow() != null) {
            settingsDlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(cBg));
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13 * scale);
        t.setTextColor(cText);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    /** 第一次进入：选竖屏模式还是沉浸刷机模式（横屏，桌面版布局） */
    private void chooseLayoutOnFirstRun() {
        new AlertDialog.Builder(this)
                .setTitle(T("选择界面布局", "Choose layout"))
                .setMessage(T("之后可以在「设置 → 横屏模式」里随时切换",
                        "You can change it later in Settings → Landscape"))
                .setPositiveButton(T("竖屏模式", "Portrait"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        store.edit().putBoolean("landscape", false).putBoolean("layoutChosen", true).apply();
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                        recreate();
                    }
                })
                .setNegativeButton(T("沉浸刷机（横屏）", "Immersive (landscape)"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        store.edit().putBoolean("landscape", true).putBoolean("layoutChosen", true).apply();
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                        recreate();
                    }
                })
                .setCancelable(false)
                .show();
    }
    private void chooseMode(final boolean first) {
        String[] items = {
                T("OTG 主机：用 OTG 线刷另一台手机", "OTG host: flash another phone"),
                T("本机 root：备份/刷写自己", "Local root: manage this phone"),
                T("遥控 PC：指挥电脑上的桌面版", "Remote: drive the desktop app")
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(T("选择使用模式", "Choose mode"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { applyMode(which); }
                });
        b.setCancelable(!first);
        b.show();
    }

    // ---------------- 执行 ----------------

    private void runBg(final Runnable r) {
        new Thread(new Runnable() {
            public void run() {
                try { r.run(); } catch (final Exception e) { append(T("异常：", "Error: ") + e, cErr); }
            }
        }).start();
    }

    private RemoteClient remote() {
        return new RemoteClient(store.getString("host", ""), store.getString("token", ""));
    }

    // ---------------- OTG ----------------

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERM.equals(intent.getAction())) { return; }
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                pendingDevice = d;
                runBg(new Runnable() { public void run() { fb.open(MainActivity.this, pendingDevice); } });
            } else {
                append(T("USB 权限被拒绝", "USB permission denied"), cErr);
            }
        }
    };

    private void connectUsb() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice d = FastbootUsb.findFastbootDevice(um);
        if (d == null) {
            append(T("没找到 fastboot 设备：检查 OTG 线和对方是否在 fastboot 界面",
                    "No fastboot device: check the OTG cable and the other phone's fastboot screen"), cErr);
            return;
        }
        if (!um.hasPermission(d)) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) { flags |= PendingIntent.FLAG_IMMUTABLE; }
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERM), flags);
            um.requestPermission(d, pi);
            append(T("等待 USB 授权…", "Waiting for USB permission…"), cDim);
            return;
        }
        final UsbDevice dev = d;
        runBg(new Runnable() { public void run() { fb.open(MainActivity.this, dev); } });
    }

    private void otgInfo() {
        runBg(new Runnable() {
            public void run() {
                if (!fb.isOpen()) { append(T("请先连接设备", "Connect first"), cErr); return; }
                append("product: " + fb.getvar("product"), cText);
                append("version: " + fb.getvar("version"), cText);
                append("serialno: " + fb.getvar("serialno"), cText);
            }
        });
    }

    private void otgPartitions() {
        runBg(new Runnable() {
            public void run() {
                if (!fb.isOpen()) { append(T("请先连接设备", "Connect first"), cErr); return; }
                List<String[]> parts = fb.getPartitions();
                lastParts.clear();
                lastParts.addAll(parts);
                showPartitions(parts, "fastboot getvar all");
            }
        });
    }

    private void otgEdl() {
        runBg(new Runnable() {
            public void run() {
                if (!fb.isOpen()) { append(T("请先连接设备", "Connect first"), cErr); return; }
                boolean ok = fb.oem("edl");
                append(ok ? T("EDL 已请求", "EDL requested") : T("EDL 失败（可能未解锁）", "EDL failed (bootloader locked?)"), ok ? cOk : cErr);
            }
        });
    }

    private void otgErase() {
        final EditText et = new EditText(this);
        et.setText(pendingPart);
        new AlertDialog.Builder(this).setTitle(T("擦除分区", "Erase partition")).setView(et)
                .setPositiveButton(T("擦除", "Erase"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String p = et.getText().toString().trim();
                        runBg(new Runnable() { public void run() { fb.erase(p); } });
                    }
                }).setNegativeButton(T("取消", "Cancel"), null).show();
    }

    private void otgReboot(final String target) {
        runBg(new Runnable() {
            public void run() {
                if (!fb.isOpen()) { append(T("请先连接设备", "Connect first"), cErr); return; }
                boolean ok = fb.reboot(target);
                append(ok ? T("已发送重启指令", "Reboot sent") : T("重启失败", "Reboot failed"), ok ? cOk : cErr);
            }
        });
    }

    // ---------------- 本机 root ----------------

    private void checkRoot() {
        runBg(new Runnable() {
            public void run() {
                boolean ok = RootShell.hasRoot();
                append(ok ? T("root 可用", "root available") : T("没有 root，本机模式不可用", "No root, local mode unavailable"), ok ? cOk : cErr);
            }
        });
    }

    private void localPartitions() {
        runBg(new Runnable() {
            public void run() {
                List<String[]> parts = RootShell.partitions();
                lastParts.clear();
                lastParts.addAll(parts);
                showPartitions(parts, "/dev/block/by-name");
            }
        });
    }

    private void localReboot(final String target) {
        runBg(new Runnable() {
            public void run() {
                RootShell.Result r = RootShell.reboot(target);
                append(r.ok ? (T("已请求重启：", "Reboot requested: ") + (target.length() == 0 ? "system" : target))
                        : (T("重启失败：", "Reboot failed: ") + r.out), r.ok ? cOk : cErr);
            }
        });
    }

    private void backupDialog() {
        new AlertDialog.Builder(this)
                .setTitle(T("备份基带", "Backup modem"))
                .setMultiChoiceItems(BAK_PARTS, bakPick, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean checked) { bakPick[which] = checked; }
                })
                .setPositiveButton(T("开始", "Start"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doBackup(); }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .show();
    }

    private void doBackup() {
        final List<String> picked = new ArrayList<String>();
        for (int i = 0; i < BAK_PARTS.length; i++) { if (bakPick[i]) { picked.add(BAK_PARTS[i]); } }
        if (picked.isEmpty()) { append(T("至少选一个分区", "Pick at least one partition"), cErr); return; }

        runBg(new Runnable() {
            public void run() {
                File dir = new File(getExternalFilesDir(null), "backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
                dir.mkdirs();
                append(T("开始备份：", "Backing up: ") + dir.getAbsolutePath(), cDim);
                for (String p : picked) {
                    File out = new File(dir, p + ".img");
                    RootShell.Result r = RootShell.backup(p, out);
                    if (out.exists() && out.length() > 0) {
                        append(p + "  " + String.format(Locale.US, "%.1f MB  ", out.length() / 1048576.0) + sha256(out), cOk);
                    } else {
                        append(p + "  " + T("失败", "failed") + " " + r.out, cErr);
                    }
                }
                append(T("备份完成：", "Backup done: ") + dir.getAbsolutePath(), cOk);
            }
        });
    }

    private String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); }
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) { sb.append(String.format("%02x", b)); }
            return sb.substring(0, 16) + "…";
        } catch (Exception e) { return ""; }
    }

    // ---------------- 分区表 ----------------

    private void showPartitions(final List<String[]> parts, final String title) {
        ui.post(new Runnable() {
            public void run() {
                if (parts.isEmpty()) { append(T("没有读到分区", "No partitions found"), cErr); return; }
                StringBuilder sb = new StringBuilder();
                for (String[] p : parts) { sb.append(p[0]).append("  ").append(human(p[1])).append('\n'); }
                final String text = sb.toString();

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title + "  (" + parts.size() + ")")
                        .setMessage(text)
                        .setPositiveButton(T("复制", "Copy"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("partitions", text));
                                append(T("已复制分区表", "Partitions copied"), cDim);
                            }
                        })
                        .setNeutralButton(T("导出 CSV", "Export CSV"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { exportCsv(parts); }
                        })
                        .setNegativeButton(T("关闭", "Close"), null)
                        .show();
            }
        });
    }

    private void exportCsv(List<String[]> parts) {
        try {
            File dir = getExternalFilesDir("exports");
            if (dir == null) { dir = getFilesDir(); }
            dir.mkdirs();
            File f = new File(dir, "partitions-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv");
            StringBuilder sb = new StringBuilder("partition,size_bytes,size_human,type\n");
            for (String[] p : parts) {
                sb.append(p[0]).append(',').append(p[1]).append(',').append(human(p[1])).append(',').append(p[2]).append('\n');
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            append(T("已导出：", "Exported: ") + f.getAbsolutePath(), cOk);
        } catch (Exception e) {
            append(T("导出失败：", "Export failed: ") + e.getMessage(), cErr);
        }
    }

    private String human(String hex) {
        try {
            long v = hex.startsWith("0x") ? Long.parseLong(hex.substring(2), 16) : Long.parseLong(hex);
            if (v >= 1073741824L) { return String.format(Locale.US, "%.2f GB", v / 1073741824.0); }
            if (v >= 1048576L) { return String.format(Locale.US, "%.1f MB", v / 1048576.0); }
            if (v >= 1024L) { return String.format(Locale.US, "%.0f KB", v / 1024.0); }
            return v + " B";
        } catch (Exception e) { return "-"; }
    }

    // ---------------- 刷入镜像 ----------------

    private void pickImage() {
        final EditText et = new EditText(this);
        et.setText(pendingPart);
        new AlertDialog.Builder(this).setTitle(T("刷入到哪个分区", "Which partition"))
                .setView(et)
                .setPositiveButton(T("选镜像", "Pick image"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pendingPart = et.getText().toString().trim();
                        store.edit().putString("part", pendingPart).apply();
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_IMAGE);
                    }
                })
                .setNegativeButton(T("取消", "Cancel"), null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) { return; }
        Uri uri = data.getData();

        if (req == REQ_IMAGE) {
            File f;
            try { f = copyToCache(uri, "flash.img"); }
            catch (Exception e) { append(T("读取失败：", "Read failed: ") + e.getMessage(), cErr); return; }

            final File img = f;
            append(T("已选择：", "Selected: ") + String.format(Locale.US, "%.1f MB", f.length() / 1048576.0) + " → " + pendingPart, cDim);
            new AlertDialog.Builder(this).setTitle(T("确认刷入", "Confirm flash"))
                    .setMessage(T("将写入分区 ", "Write to partition ") + pendingPart + "\n"
                            + String.format(Locale.US, "%.1f MB", f.length() / 1048576.0) + "\n\n"
                            + T("写错分区可能无法开机", "A wrong partition can brick the device"))
                    .setPositiveButton(T("刷入", "Flash"), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { doFlash(pendingPart, img); }
                    })
                    .setNegativeButton(T("取消", "Cancel"), null).show();
        } else if (req == REQ_ROM) {
            try { startRom(extractRom(copyToCache(uri, "rom.zip"))); }
            catch (Exception e) { append(T("解压失败：", "Extract failed: ") + e.getMessage(), cErr); }
        } else if (req == REQ_ROM_DIR) {
            try { startRom(copyTree(uri)); }
            catch (Exception e) { append(T("读取目录失败：", "Read folder failed: ") + e.getMessage(), cErr); }
        }
    }

    private File copyToCache(Uri uri, String name) throws Exception {
        File f = new File(getCacheDir(), name);
        InputStream in = getContentResolver().openInputStream(uri);
        OutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
        out.close();
        in.close();
        return f;
    }

    private void doFlash(final String part, final File img) {
        runBg(new Runnable() {
            public void run() {
                if (mode == MODE_OTG) {
                    if (!fb.isOpen()) { append(T("请先连接设备", "Connect first"), cErr); return; }
                    fb.flashFile(part, img, null);
                } else if (mode == MODE_LOCAL) {
                    RootShell.Result r = RootShell.flash(part, img);
                    append(r.ok ? (T("刷入完成：", "Flashed: ") + part) : (T("刷入失败：", "Flash failed: ") + r.out), r.ok ? cOk : cErr);
                } else {
                    try { append(remote().flashFile(part, img), cText); }
                    catch (Exception e) { append(T("失败：", "Failed: ") + e.getMessage(), cErr); }
                }
            }
        });
    }

    // ---------------- 线刷 ROM ----------------

    private void pickRom() {
        String[] items = {
                T("选择 .zip 线刷包", "Pick a .zip ROM"),
                T("选择 .tgz 线刷包", "Pick a .tgz ROM"),
                T("选择已解压的目录", "Pick an unpacked folder")
        };
        new AlertDialog.Builder(this).setTitle(T("线刷 ROM", "Flash ROM"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 2) {
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_ROM_DIR);
                        } else {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            startActivityForResult(i, REQ_ROM);
                        }
                    }
                }).show();
    }

    private File extractRom(File archive) throws Exception {
        File root = new File(getCacheDir(), "rom");
        deleteRec(root);
        root.mkdirs();

        String name = archive.getName().toLowerCase(Locale.US);
        if (name.endsWith(".zip")) {
            ZipInputStream zin = new ZipInputStream(new FileInputStream(archive));
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(root, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) { parent.mkdirs(); }
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int n;
                while ((n = zin.read(buf)) > 0) { fos.write(buf, 0, n); }
                fos.close();
            }
            zin.close();
        } else {
            Process p = Runtime.getRuntime().exec(new String[]{ "tar", "-xzf", archive.getAbsolutePath(), "-C", root.getAbsolutePath() });
            p.waitFor();
        }

        File[] subs = root.listFiles();
        if (subs != null && subs.length == 1 && subs[0].isDirectory() && !new File(root, "images").exists()) {
            return subs[0];
        }
        return root;
    }

    private File copyTree(Uri tree) throws Exception {
        File root = new File(getCacheDir(), "rom");
        deleteRec(root);
        root.mkdirs();
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        copyChildren(children, root);
        return root;
    }

    private void copyChildren(Uri children, File dest) throws Exception {
        Cursor c = getContentResolver().query(children,
                new String[]{ DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
        if (c == null) { return; }
        while (c.moveToNext()) {
            String docId = c.getString(0);
            String name = c.getString(1);
            String mime = c.getString(2);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                File d = new File(dest, name);
                d.mkdirs();
                Uri sub = DocumentsContract.buildChildDocumentsUriUsingTree(children, docId);
                copyChildren(sub, d);
            } else {
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(children, docId);
                InputStream in = getContentResolver().openInputStream(doc);
                FileOutputStream out = new FileOutputStream(new File(dest, name));
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
                out.close();
                in.close();
            }
        }
        c.close();
    }

    private void deleteRec(File f) {
        if (f == null || !f.exists()) { return; }
        File[] kids = f.listFiles();
        if (kids != null) { for (File k : kids) { deleteRec(k); } }
        f.delete();
    }

    /** 模拟一个线刷步骤（不碰 USB） */
    private boolean simulateStep(RomFlasher.Step s, File base) {
        if ("flash".equals(s.kind)) {
            File img = romFile(base, s.file);
            long size = img != null ? img.length() : 0;
            append("  模拟上传 " + s.file + " · " + String.format(Locale.US, "%.1f", size / 1048576.0) + " MB", cDim);
            for (int p = 10; p <= 100; p += 10) {
                if (stopRequested) { append("  已停止", cErr); return false; }
                append("  " + p + "%", cDim);
                try { Thread.sleep(60); } catch (InterruptedException ignored) { }
            }
            return true;
        }
        append("  模拟 " + s.kind + (s.arg.isEmpty() ? "" : " " + s.arg), cDim);
        try { Thread.sleep(150); } catch (InterruptedException ignored) { }
        return true;
    }

    /** 造一个假的线刷包（flash_all + images），用来跑通流程 */
    private File makeFakeRom() throws Exception {
        File dir = new File(getCacheDir(), "fakerom");
        deleteRec(dir);
        File images = new File(dir, "images");
        images.mkdirs();
        writeFakeImg(new File(images, "boot.img"), 96L * 1024 * 1024);
        writeFakeImg(new File(images, "system.img"), 1400L * 1024 * 1024);
        writeFakeImg(new File(images, "vendor.img"), 620L * 1024 * 1024);
        String script = "fastboot getvar product\n"
                + "fastboot flash boot boot.img\n"
                + "fastboot flash system system.img\n"
                + "fastboot flash vendor vendor.img\n"
                + "fastboot erase userdata\n"
                + "fastboot reboot\n";
        java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, "flash_all.bat"));
        out.write(script.getBytes("UTF-8"));
        out.close();
        return dir;
    }

    /**
     * 造一个「指定大小」的假镜像：用 setLength 创建稀疏文件，
     * 1.4 GB 的文件在磁盘上几乎不占空间，读出来是 0。
     */
    private void writeFakeImg(File f, long size) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
        try { raf.setLength(size); } finally { raf.close(); }
    }

    /** 设置里点「模拟线刷」：造包 → 走和真刷一样的解析/确认/循环，只是不碰 USB */
    private void startSimulatedRom() {
        try {
            File dir = makeFakeRom();
            dryRun = true;
            stopRequested = false;
            append(T("模拟线刷：已生成假包", "Dry run: fake package created"), cDim);
            startRom(dir);
        } catch (Exception e) {
            dryRun = false;
            append(T("生成假包失败：", "Failed to build fake package: ") + e.getMessage(), cErr);
        }
    }
    private void startRom(File dir) {
        // 照 Compose 版：扫出所有 flash_all* 脚本，多于一个就让用户选（原来是「取第一个命中的」，
        // 很可能正好选中清数据那个）
        File imgDir = new File(dir, "images");
        if (!imgDir.exists()) { imgDir = dir; }
        final File base = imgDir;
        List<File> scripts = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.startsWith("flash_all") && f.isFile()) { scripts.add(f); }
            }
        }
        if (scripts.isEmpty()) {
            append(T("没有 flash_all 脚本，按镜像名推断", "No flash_all script, inferring from names"), cDim);
            List<String> names = new ArrayList<String>();
            File[] imgs = imgDir.listFiles();
            if (imgs != null) {
                for (File f : imgs) {
                    if (f.getName().toLowerCase(Locale.US).endsWith(".img")) { names.add(f.getName()); }
                }
            }
            loadRomSteps(null, RomFlasher.fromImages(names), "", base);
        } else if (scripts.size() == 1) {
            loadRomScript(scripts.get(0), base);
        } else {
            final List<File> finalScripts = scripts;
            String[] labels = new String[scripts.size()];
            for (int i = 0; i < scripts.size(); i++) {
                String n = scripts.get(i).getName();
                // 文件名带 except_storage 的就是「保数据」那版
                if (n.toLowerCase(Locale.US).contains("except_storage")) {
                    n = n + T("（保数据）", " (keep data)");
                }
                labels[i] = n;
            }
            append(T("发现 " + scripts.size() + " 个刷机脚本，请选择用哪个",
                    scripts.size() + " flash scripts found, pick one"), cDim);
            new AlertDialog.Builder(this)
                    .setTitle(T("选择刷机脚本", "Pick a script"))
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            loadRomScript(finalScripts.get(which), base);
                        }
                    })
                    .setNegativeButton(T("取消", "Cancel"), null)
                    .show();
        }
    }

    /** 读脚本 → 解析成步骤 → 弹确认框（带「保数据 / 会清空数据」） */
    private void loadRomScript(File script, File base) {
        String text = "";
        try { text = readText(script); } catch (Exception e) { text = ""; }
        append(T("使用脚本 ", "Using script ") + script.getName(), cAccent);
        String want = RomFlasher.expectedProduct(text);
        loadRomSteps(script.getName(), RomFlasher.fromScript(text), want, base);
    }

    private void loadRomSteps(String scriptName, List<RomFlasher.Step> steps, String want, File base) {
        if (steps.isEmpty()) { append(T("没有可执行的步骤", "No executable steps"), cErr); return; }
        int missing = 0;
        for (RomFlasher.Step s : steps) {
            if ("flash".equals(s.kind) && romFile(base, s.file) == null) { s.missing = true; }
            if (s.missing) { missing++; }
        }
        // 和 Compose 版一样：wipe 步骤或 erase userdata 就算「会清空数据」
        int wipes = 0;
        for (RomFlasher.Step s : steps) {
            if ("wipe".equals(s.kind) || ("erase".equals(s.kind) && "userdata".equals(s.part))) { wipes++; }
        }
        StringBuilder sb = new StringBuilder();
        if (scriptName != null) { sb.append(T("脚本：", "Script: ")).append(scriptName).append('\n'); }
        if (!want.isEmpty()) { sb.append(T("机型：", "Product: ")).append(want).append('\n'); }
        sb.append(T("共 ", "Steps: ")).append(steps.size()).append(T(" 步", ""));
        if (missing > 0) { sb.append(T("，", ", ")).append(missing).append(T(" 步缺文件", " missing")); }
        sb.append(wipes > 0 ? T("，会清空数据", ", will wipe data") : T("，保数据", ", keeps data"));
        sb.append("\n\n");
        int shown = 0;
        for (RomFlasher.Step s : steps) {
            if (shown++ >= 12) { sb.append("…\n"); break; }
            sb.append(s.label()).append('\n');
        }
        final List<RomFlasher.Step> finalSteps = steps;
        final String finalWant = want;
        new AlertDialog.Builder(this)
                .setTitle(wipes > 0 ? T("确认线刷（会清空数据）", "Confirm (wipes data)")
                        : T("确认线刷（保数据）", "Confirm (keeps data)"))
                .setMessage(sb.toString())
                .setPositiveButton(T("开始", "Start"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { runRom(finalSteps, base, finalWant); }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .show();
    }
    private String readText(File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) { sb.append(line).append('\n'); }
        br.close();
        return sb.toString();
    }

    /** 在所选目录 / images 子目录里找脚本写的那个文件名 */
    private File romFile(File base, String name) {
        File f = new File(base, name);
        if (f.exists()) { return f; }
        if (base.getParentFile() != null) {
            f = new File(new File(base.getParentFile(), "images"), name);
            if (f.exists()) { return f; }
        }
        File[] kids = base.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.getName().equalsIgnoreCase(name)) { return k; }
            }
        }
        return null;
    }

    /** 跑一个线刷步骤（照 Compose 版 runStep） */
    private boolean runStep(RomFlasher.Step s, File base) {
        if (dryRun) { return simulateStep(s, base); }
        try {
            if ("flash".equals(s.kind)) {
                File img = romFile(base, s.file);
                if (s.missing || img == null) {
                    append(T("  缺少文件：", "  missing: ") + s.file, cErr);
                    return false;
                }
                append("  " + img.getName() + " · " + (img.length() / 1048576.0) + " MB", cDim);
                if (mode == MODE_OTG) {
                    java.io.FileInputStream in = new java.io.FileInputStream(img);
                    boolean ok = fb.flashStream(s.part, in, img.length(), new FastbootUsb.Progress() {
                        public void onProgress(long done, long total) {
                            if (total > 0 && done % Math.max(total / 10, 1) < 65536) {
                                append("  " + (done * 100 / total) + "%", cDim);
                            }
                        }
                    });
                    try { in.close(); } catch (Exception ignored) { }
                    return ok;
                }
                if (mode == MODE_LOCAL) { return RootShell.flash(s.part, img).ok; }
                remote().flashFile(s.part, img);
                return true;
            }
            if ("erase".equals(s.kind)) {
                if (mode == MODE_OTG) { return fb.erase(s.part); }
                append(T("  跳过 erase（仅 OTG）", "  erase skipped (OTG only)"), cDim);
                return true;
            }
            if ("wipe".equals(s.kind)) {
                return mode == MODE_OTG && fb.erase("userdata") && fb.erase("metadata");
            }
            if ("reboot".equals(s.kind)) {
                if (mode == MODE_OTG) {
                    boolean r = fb.reboot(s.arg);
                    if (r) { append(T("  已重启，后续步骤中止", "  rebooted, stopping"), cOk); }
                    return r;
                }
                return true;
            }
            if ("oem".equals(s.kind)) {
                return mode == MODE_OTG && fb.oem(s.arg);
            }
            if (mode == MODE_OTG) {
                return fb.command(s.raw.replaceFirst("(?i)^fastboot\\s+", "")).startsWith("OKAY");
            }
            return true;
        } catch (Exception e) {
            append("  " + e.getMessage(), cErr);
            return false;
        }
    }

    private void runRom(final List<RomFlasher.Step> steps, final File base, final String want) {
        runBg(new Runnable() {
            public void run() {
                if (!dryRun && mode == MODE_OTG && !fb.isOpen()) {
                    append(T("请先连接设备", "Connect a device first"), cErr);
                    return;
                }
                // 开刷前再核一次机型（照 Compose 版）
                if (!dryRun && mode == MODE_OTG && !want.isEmpty()) {
                    String real = fb.getvar("product");
                    if (real != null && real.length() > 0 && !real.startsWith("FAIL") && !real.equalsIgnoreCase(want)) {
                        append(T("机型不匹配：包是 ", "Wrong package: ") + want
                                + T("，设备是 ", ", device is ") + real
                                + T("，已中止（什么都没刷）", " — aborted, nothing flashed"), cErr);
                        return;
                    }
                }
                int ok = 0, fail = 0;
                for (int i = 0; i < steps.size(); i++) {
                    RomFlasher.Step s = steps.get(i);
                    append("[" + (i + 1) + "/" + steps.size() + "] " + s.raw, cAccent);
                    boolean r = runStep(s, base);
                    if (!r && !"reboot".equals(s.kind) && mode == MODE_OTG) {
                        append(T("  失败，重连后重试一次", "  failed, retry after reconnect"), cDim);
                        if (fb.reopen()) { r = runStep(s, base); }
                    }
                    if (r) { ok++; append("  OK", cOk); }
                    else { fail++; append("  FAIL", cErr); }
                    if ("reboot".equals(s.kind) && r) { break; }
                }
                append(T("线刷结束：成功 ", "Finished: ") + ok + T(" · 失败 ", " ok · ") + fail,
                        fail == 0 ? cOk : cErr);
                if (dryRun) { dryRun = false; append(T("（模拟结束，设备未被修改）", "(dry run finished, device untouched)"), cDim); }
            }
        });
    }

    // ---------------- 遥控 ----------------

    private void remoteStatus() {
        runBg(new Runnable() {
            public void run() {
                try { append(T("PC 在线：", "PC online: ") + remote().ping(), cOk); }
                catch (Exception e) { append(T("连接失败：", "Connect failed: ") + e.getMessage(), cErr); }
            }
        });
    }

    private void remotePartitions() {
        runBg(new Runnable() {
            public void run() {
                try { append(remote().partitions(), cText); }
                catch (Exception e) { append(T("读取失败：", "Failed: ") + e.getMessage(), cErr); }
            }
        });
    }

    private void remoteReboot(final String target) {
        runBg(new Runnable() {
            public void run() {
                try { append(remote().reboot(target), cText); }
                catch (Exception e) { append(T("失败：", "Failed: ") + e.getMessage(), cErr); }
            }
        });
    }

    // ---------------- 命令输入 ----------------

    private void runTyped() {
        final String cmd = input.getText().toString().trim();
        if (cmd.length() == 0) { return; }
        input.setText("");
        if (!history.contains(cmd)) { history.add(cmd); }
        if (history.size() > 50) { history.remove(0); }
        append("> " + cmd, cAccent);

        runBg(new Runnable() {
            public void run() {
                if (mode == MODE_LOCAL) {
                    RootShell.Result r = RootShell.run(cmd);
                    append(r.out.length() > 0 ? r.out : ("exit " + (r.ok ? 0 : 1)), r.ok ? cText : cErr);
                } else if (mode == MODE_REMOTE) {
                    try { append(remote().run(cmd), cText); }
                    catch (Exception e) { append(T("失败：", "Failed: ") + e.getMessage(), cErr); }
                } else {
                    if (!fb.isOpen()) { append(T("请先连接设备", "Connect first"), cErr); return; }
                    String r = fb.command(cmd);
                    append(r, r.startsWith("OKAY") ? cOk : cErr);
                }
            }
        });
    }
}
