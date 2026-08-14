package com.minis.ax3000t;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends AppCompatActivity {
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int RED = Color.rgb(185, 28, 28);
    private static final int TEXT = Color.rgb(15, 23, 42);
    private static final int MUTED = Color.rgb(71, 85, 105);
    private static final int BG = Color.rgb(248, 250, 252);

    private EditText hostInput;
    private EditText passwordInput;
    private CheckBox ethernetCheck;
    private CheckBox modelCheck;
    private Button installButton;
    private Button rollbackButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private TextView stageText;
    private TextView logText;
    private ExecutorService executor;
    private Future<?> task;
    private FlashWorkflow workflow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildContent());
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column(this);
        root.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("AX3000T 刷机助手", 28, TEXT, true);
        root.addView(title);
        root.addView(text("原生 Android · OpenWrt 25.12.0 · 不改写 U-Boot", 14, MUTED, false));

        TextView danger = text(
                "重要：本工具只支持 Xiaomi AX3000T RD03 / RD23（MT7981B）。\n"
                        + "RD03v2 是完全不同的 Qualcomm 硬件，已在程序中硬阻止。刷机存在变砖风险，任何程序都不能保证断电时安全。",
                14, RED, true);
        danger.setPadding(dp(14), dp(14), dp(14), dp(14));
        danger.setBackground(round(Color.rgb(254, 242, 242), dp(12)));
        root.addView(danger, marginTop(14));

        LinearLayout connection = card();
        connection.addView(text("连接准备", 19, TEXT, true));
        connection.addView(text(
                "你没有电脑也可以操作，但必须准备：USB-C 转千兆网卡 + 网线。\n"
                        + "网线插路由器 LAN 中间口（不要插 WAN），手机 USB 网卡等待自动获取地址。\n"
                        + "刷机重启后原厂 Wi-Fi 会消失，整个过程不要拔网线、不要锁屏断开网卡、不要断电。",
                14, MUTED, false), marginTop(6));
        root.addView(connection, marginTop(14));

        LinearLayout fields = card();
        fields.addView(text("原厂管理页", 19, TEXT, true));
        fields.addView(text("默认地址一般是 192.168.31.1；如果你改过 LAN 地址，请填实际地址。", 13, MUTED, false), marginTop(5));
        hostInput = input("路由器地址", "192.168.31.1", false);
        fields.addView(hostInput, marginTop(8));
        passwordInput = input("小米路由器管理密码", "输入管理页密码", true);
        fields.addView(passwordInput, marginTop(8));
        root.addView(fields, marginTop(14));

        ethernetCheck = new CheckBox(this);
        ethernetCheck.setText("我已连接 USB-C 有线网卡，并插在 LAN 中间口");
        ethernetCheck.setTextColor(TEXT);
        ethernetCheck.setTextSize(14);
        ethernetCheck.setMinHeight(dp(52));
        root.addView(ethernetCheck, marginTop(10));

        modelCheck = new CheckBox(this);
        modelCheck.setText("我确认标签不是 RD03v2，并接受刷机风险");
        modelCheck.setTextColor(TEXT);
        modelCheck.setTextSize(14);
        modelCheck.setMinHeight(dp(52));
        root.addView(modelCheck);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        installButton = button("开始：备份 → 校验 → 刷入", BLUE, Color.WHITE);
        rollbackButton = button("回退到自动备份的官方固件", Color.rgb(226, 232, 240), TEXT);
        cancelButton = button("取消当前流程", Color.rgb(254, 226, 226), RED);
        actions.addView(installButton, fullHeight(56));
        actions.addView(rollbackButton, marginTop(8, fullHeight(56)));
        actions.addView(cancelButton, marginTop(8, fullHeight(50)));
        root.addView(actions, marginTop(10));

        LinearLayout status = card();
        stageText = text("未开始", 16, BLUE, true);
        status.addView(stageText);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        status.addView(progressBar, marginTop(8, fullWidth(8)));
        root.addView(status, marginTop(14));

        LinearLayout logCard = card();
        logCard.addView(text("操作日志", 18, TEXT, true));
        logText = text("请先连接有线网卡，再勾选上方确认项。\n", 13, MUTED, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setGravity(Gravity.TOP | Gravity.START);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setBackground(round(Color.rgb(241, 245, 249), dp(10)));
        logCard.addView(logText, marginTop(8, fullWidth(dp(220))));
        root.addView(logCard, marginTop(14));

        installButton.setOnClickListener(v -> startInstall());
        rollbackButton.setOnClickListener(v -> confirmRollback());
        cancelButton.setOnClickListener(v -> cancelWorkflow());
        return scroll;
    }

    private void startInstall() {
        if (!ethernetCheck.isChecked() || !modelCheck.isChecked()) {
            showMessage("请先确认有线连接和设备型号", "为避免手机在重启后失联，必须使用 USB-C 有线网卡；RD03v2 绝对不能刷。 ");
            return;
        }
        String host = hostInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (host.isEmpty() || password.isEmpty()) {
            showMessage("信息不完整", "请输入路由器地址和原厂管理页密码。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("最后确认")
                .setMessage("程序会先自动备份 BL2/NVRAM/Bdata/Factory/FIP 和两个原厂系统分区，并导出到 Download/AX3000T-Backups。\n\n"
                        + "只有备份完整、哈希校验通过，才会开始刷写。刷写期间严禁断电。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> runInstall(host, password))
                .show();
    }

    private void runInstall(String host, String password) {
        if (task != null && !task.isDone()) return;
        setRunning(true);
        appendLog("开始安全流程；任何异常请先保留日志，不要连续重复刷机。\n");
        workflow = new FlashWorkflow(this, listener());
        task = executor.submit(() -> workflow.install(host, password));
    }

    private void confirmRollback() {
        new AlertDialog.Builder(this)
                .setTitle("回退官方固件")
                .setMessage("仅使用本机自动生成的最近一次备份。\n"
                        + "如果设备当前还能进入临时 OpenWrt，程序优先切回未改写的原厂启动槽位；如果已经进入正式 OpenWrt，则只在分区标签和尺寸完全匹配时恢复 UBI。\n\n"
                        + "回退时同样不能断电。确认继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始回退", (dialog, which) -> runRollback())
                .show();
    }

    private void runRollback() {
        if (task != null && !task.isDone()) return;
        String host = hostInput.getText().toString().trim();
        setRunning(true);
        appendLog("开始回退流程；不要断电。\n");
        workflow = new FlashWorkflow(this, listener());
        task = executor.submit(() -> workflow.rollbackLatest(host));
    }

    private FlashWorkflow.Listener listener() {
        return new FlashWorkflow.Listener() {
            @Override
            public void log(String message) {
                runOnUiThread(() -> appendLog(message + "\n"));
            }

            @Override
            public void progress(int percent, String stage) {
                runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    stageText.setText(percent + "%  " + stage);
                });
            }

            @Override
            public void finished(String message) {
                runOnUiThread(() -> {
                    setRunning(false);
                    progressBar.setProgress(100);
                    stageText.setText("100%  完成");
                    appendLog("完成：" + message + "\n");
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void failed(String message, Throwable cause) {
                runOnUiThread(() -> {
                    setRunning(false);
                    stageText.setText("失败：请勿断电");
                    appendLog("失败：" + message + "\n"
                            + "如果设备已写入临时系统，请先点“回退到自动备份的官方固件”，不要重新开始刷机。\n");
                    Toast.makeText(MainActivity.this, "流程失败，请查看日志", Toast.LENGTH_LONG).show();
                });
            }
        };
    }

    private void cancelWorkflow() {
        if (workflow != null) workflow.cancel();
        appendLog("已请求取消；正在等待当前远程命令安全结束。不要立即断电。\n");
    }

    private void setRunning(boolean running) {
        installButton.setEnabled(!running);
        rollbackButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        hostInput.setEnabled(!running);
        passwordInput.setEnabled(!running);
        ethernetCheck.setEnabled(!running);
        modelCheck.setEnabled(!running);
        if (!running && stageText.getText().toString().startsWith("未")) progressBar.setProgress(0);
    }

    private void appendLog(String message) {
        if (logText == null) return;
        logText.append(message);
        if (logText.getLayout() != null) {
            int scrollAmount = logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
            if (scrollAmount > 0) logText.scrollTo(0, scrollAmount);
        }
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
    }

    private LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = column(this);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(round(Color.WHITE, dp(14)));
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText input(String hint, String value, boolean password) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value.equals("192.168.31.1") ? value : "");
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setSingleLine(true);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(round(Color.rgb(248, 250, 252), dp(10)));
        if (password) view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return view;
    }

    private Button button(String label, int background, int color) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(15);
        view.setTextColor(color);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(round(background, dp(12)));
        return view;
    }

    private android.graphics.drawable.GradientDrawable round(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams fullHeight(int heightDp) {
        return new LinearLayout.LayoutParams(-1, dp(heightDp));
    }

    private LinearLayout.LayoutParams fullWidth(int heightPx) {
        return new LinearLayout.LayoutParams(-1, heightPx);
    }

    private LinearLayout.LayoutParams marginTop(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams marginTop(int topDp, LinearLayout.LayoutParams params) {
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (workflow != null) workflow.cancel();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
