package dev.eta.acefan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone UI. Provider and document I/O stay off the main thread; this class never
 * touches fan nodes, thermal nodes or the control policy.
 */
public class MainActivity extends Activity {
    static final String AUTHORITY = "dev.eta.acefan.data";
    static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    static final int CREATE_CSV = 71;
    static final int CREATE_REPORT = 72;

    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler handler = new Handler(Looper.getMainLooper());
    final ArrayList<ChartView.Sample> samples = new ArrayList<>();
    final AtomicBoolean loading = new AtomicBoolean(false);

    Ui ui;
    NavBar nav;
    FrameLayout content;
    final ScrollView[] pages = new ScrollView[3];

    // 概览
    TextView ovStatus, ovPhase, ovPhaseDesc, ovBattery, ovBatterySub,
            ovLevel, ovBoard, ovFan, ovMos, ovFresh;
    View ovDot;
    RoundSwitch ovSwitch;
    TempChartView miniChart;

    // 趋势
    SegmentedControl trendRange;
    TempChartView tempChart;
    LevelChartView levelChart;
    TextView trendSubtitle, diagChip;

    // 设置
    RoundSwitch setSwitch;
    TextView themeValue, appVersionLine, compatibilityValue;
    volatile JSONObject runtimeRecord;
    String currentBootId;


    boolean alive, configReady, syncing;
    long lastLocalPush, configVersion;
    boolean diagnostics;
    int rangeMinutes = 30;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            char[] b = new char[64]; int n = r.read(b); if (n > 0) currentBootId = new String(b, 0, n).trim();
        } catch (Exception ignored) { }

        getWindow().setBackgroundDrawable(new ColorDrawable(ui.bg));
        buildUi();
        applySystemBars();
    }

    void applySystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                View decor = getWindow().getDecorView();
                WindowInsetsController ctl = decor == null ? null : decor.getWindowInsetsController();
                if (ctl != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ctl.setSystemBarsAppearance(ui.dark ? 0 : mask, mask);
                }
            } else {
                getWindow().setStatusBarColor(ui.bg);
                getWindow().setNavigationBarColor(ui.bg);
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (!ui.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
            // System bar tinting is cosmetic; never let it break the activity.
        }
    }

    // ---------------------------------------------------------------- layout

    void buildUi() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.bg);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pages[0] = page();
        pages[1] = page();
        pages[2] = page();
        buildOverview((LinearLayout) pages[0].getChildAt(0));
        buildTrends((LinearLayout) pages[1].getChildAt(0));
        buildSettings((LinearLayout) pages[2].getChildAt(0));

        for (int i = 0; i < pages.length; i++) {
            content.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }

        nav = new NavBar(this, ui);
        nav.setListener(this::showPage);
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));

        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, 0);
            int bottomPad = Math.max(bottom, ui.dp(6));
            ViewGroup.LayoutParams lp = nav.getLayoutParams();
            int height = ui.dp(64) + bottomPad;
            if (lp.height != height) {
                lp.height = height;
                nav.setLayoutParams(lp);
            }
            nav.setPadding(0, 0, 0, bottomPad);
            return insets;
        });
        root.requestApplyInsets();
        showPage(0);
    }

    ScrollView page() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = ui.column();
        col.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(32));
        s.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    TextView header(LinearLayout col, String title, String subtitle, boolean withStatus) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.text(title, 28, ui.text, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (withStatus) {
            ovStatus = ui.chip("等待遥测", ui.muted, ui.surface2);
            row.addView(ovStatus, ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 8, 0));
        }

        TextView more = ui.text("⋯", 20, ui.muted, true);
        more.setGravity(Gravity.CENTER);
        ui.ripple(more, 18, ui.surface2);
        more.setOnClickListener(v -> moreMenu());
        row.addView(more, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        col.addView(row);

        if (subtitle == null) return null;
        TextView sub = ui.text(subtitle, 13, ui.muted, false);
        sub.setPadding(0, ui.dp(6), 0, 0);
        col.addView(sub);
        return sub;
    }

    void addCard(LinearLayout col, View card) {
        col.addView(card, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
    }

    void section(LinearLayout col, String label) {
        TextView t = ui.text(label, 12, ui.muted, true);
        t.setPadding(ui.dp(4), ui.dp(18), 0, ui.dp(2));
        col.addView(t);
    }

    LinearLayout settingRow(String title, String subtitle, boolean danger, View right) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14));

        LinearLayout texts = ui.column();
        texts.addView(ui.text(title, 15, danger ? ui.warn : ui.text, false));
        if (subtitle != null) {
            texts.addView(ui.text(subtitle, 12, ui.muted, false), ui.params(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (right != null) {
            row.addView(right, ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        }
        row.addView(ui.text("›", 18, ui.muted, false), ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        ui.ripple(row, 18, Color.TRANSPARENT);
        return row;
    }

    void buildOverview(LinearLayout col) {
        header(col, "风扇助手", "ACE 7 系列 · 息屏充电增强", true);

        LinearLayout hero = ui.card();
        hero.addView(ui.text("当前阶段", 12, ui.muted, false));

        LinearLayout phaseRow = ui.row();
        phaseRow.setGravity(Gravity.CENTER_VERTICAL);
        ovDot = new View(this);
        ovDot.setBackground(ui.rounded(ui.muted, 4));
        phaseRow.addView(ovDot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        ovPhase = ui.text("等待遥测", 22, ui.text, true);
        phaseRow.addView(ovPhase, ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        hero.addView(phaseRow, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        ovPhaseDesc = ui.text("正在读取遥测数据…", 13, ui.muted, false);
        ovPhaseDesc.setLineSpacing(ui.dp(3), 1f);
        hero.addView(ovPhaseDesc, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        hero.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 0, 16, 0, 14));

        LinearLayout switchRow = ui.row();
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout switchTexts = ui.column();
        switchTexts.addView(ui.text("息屏充电增强", 15, ui.text, true));
        switchTexts.addView(ui.text("仅息屏有线充电时增强，唤醒锁屏延续", 12, ui.muted, false),
                ui.params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        switchRow.addView(switchTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ovSwitch = new RoundSwitch(this, ui);
        ovSwitch.setContentDescription("息屏充电增强");
        ovSwitch.setEnabled(false);
        ovSwitch.setListener(this::setConfig);
        switchRow.addView(ovSwitch, ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        hero.addView(switchRow);
        addCard(col, hero);

        LinearLayout metrics = ui.row();
        metrics.addView(metricCard("电池温度", "thermal:battery", true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(new View(this), new LinearLayout.LayoutParams(ui.dp(12), 1));
        metrics.addView(metricCard("驱动有效档位", "level_eff", false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addCard(col, metrics);

        LinearLayout aux = ui.card();
        aux.addView(ui.text("辅助温度", 12, ui.muted, false));
        LinearLayout auxRow = ui.row();
        ovBoard = auxColumn(auxRow, "板级");
        ovFan = auxColumn(auxRow, "风扇周边");
        ovMos = auxColumn(auxRow, "充电相关");
        aux.addView(auxRow, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        addCard(col, aux);

        LinearLayout mini = ui.card();
        LinearLayout miniHead = ui.row();
        miniHead.setGravity(Gravity.CENTER_VERTICAL);
        miniHead.addView(ui.text("电池温度趋势", 15, ui.text, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView all = ui.text("查看全部 ›", 13, ui.accent, true);
        all.setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(4));
        all.setOnClickListener(v -> showPage(1));
        miniHead.addView(all);
        mini.addView(miniHead);
        miniChart = new TempChartView(this);
        miniChart.setMini(true);
        miniChart.setRange(30);
        mini.addView(miniChart, ui.params(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(92), 0, 10, 0, 0));
        addCard(col, mini);

        ovFresh = ui.text("遥测 · 等待首条样本", 11, ui.muted, false);
        ovFresh.setLineSpacing(ui.dp(3), 1f);
        col.addView(ovFresh, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 16, 4, 0));

        TextView note = ui.text("档位是控制请求，不是实测转速；本工具不绕过原厂保护。", 11, ui.muted, false);
        note.setLineSpacing(ui.dp(3), 1f);
        col.addView(note, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 8, 4, 0));
    }

    LinearLayout metricCard(String label, String sub, boolean battery) {
        LinearLayout card = ui.card();
        card.addView(ui.text(label, 12, ui.muted, false));
        LinearLayout valueRow = ui.row();
        valueRow.setGravity(Gravity.BOTTOM);
        TextView value = ui.number("—", 30, ui.text, true);
        TextView unit = ui.text(battery ? "°C" : "/ 20", 13, ui.muted, false);
        valueRow.addView(value);
        valueRow.addView(unit, ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 5, 0, 0, 4));
        card.addView(valueRow, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        TextView subView = ui.text(sub, 11, ui.muted, false);
        card.addView(subView);
        if (battery) {
            ovBattery = value;
            ovBatterySub = subView;
        } else {
            ovLevel = value;
        }
        return card;
    }

    TextView auxColumn(LinearLayout row, String label) {
        LinearLayout col = ui.column();
        col.addView(ui.text(label, 12, ui.muted, false));
        LinearLayout valueRow = ui.row();
        valueRow.setGravity(Gravity.BOTTOM);
        TextView value = ui.number("—", 17, ui.text, true);
        valueRow.addView(value);
        valueRow.addView(ui.text("°C", 11, ui.muted, false), ui.params(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 3, 0, 0, 2));
        col.addView(valueRow, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return value;
    }

    void buildTrends(LinearLayout col) {
        header(col, "趋势", null, false);
        trendSubtitle = ui.text("温度与档位 · 最近 30 分钟", 13, ui.muted, false);
        trendSubtitle.setPadding(0, ui.dp(6), 0, 0);
        col.addView(trendSubtitle);

        trendRange = new SegmentedControl(this, ui, new String[]{"15 分钟", "30 分钟", "本次充电"});
        trendRange.setSelected(1, false);
        trendRange.setListener(i -> {
            rangeMinutes = i == 0 ? 15 : i == 1 ? 30 : 0;
            applyRange();
        });
        col.addView(trendRange, ui.params(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(42), 0, 16, 0, 0));

        LinearLayout tempCard = ui.card();
        LinearLayout tempHead = ui.row();
        tempHead.setGravity(Gravity.CENTER_VERTICAL);
        tempHead.addView(ui.text("温度曲线", 16, ui.text, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tempHead.addView(ui.text("单位 °C", 12, ui.muted, false));
        tempCard.addView(tempHead);
        tempChart = new TempChartView(this);
        tempChart.setRange(30);
        tempCard.addView(tempChart, ui.params(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(228), 0, 12, 0, 0));
        addCard(col, tempCard);

        LinearLayout levelCard = ui.card();
        LinearLayout levelHead = ui.row();
        levelHead.setGravity(Gravity.CENTER_VERTICAL);
        levelHead.addView(ui.text("控制档位", 16, ui.text, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        diagChip = ui.chip("展开诊断", ui.muted, ui.surface2);
        diagChip.setOnClickListener(v -> toggleDiagnostics());
        levelHead.addView(diagChip);
        levelCard.addView(levelHead);
        levelChart = new LevelChartView(this);
        levelChart.setRange(30);
        levelCard.addView(levelChart, ui.params(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(196), 0, 12, 0, 0));
        addCard(col, levelCard);

        TextView note = ui.text("两条默认线为模块需求与驱动有效；展开诊断后显示控制器请求与驱动设定。断点表示缺测，不做插值。",
                11, ui.muted, false);
        note.setLineSpacing(ui.dp(4), 1f);
        col.addView(note, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 14, 4, 0));
    }

    void buildSettings(LinearLayout col) {
        header(col, "设置", "控制与外观", false);

        section(col, "充电控制");
        LinearLayout control = ui.card();
        control.setPadding(0, 0, 0, 0);

        LinearLayout switchRow = ui.row();
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14));
        LinearLayout switchTexts = ui.column();
        switchTexts.addView(ui.text("息屏充电增强", 15, ui.text, false));
        switchTexts.addView(ui.text("有线充电且息屏时按温度曲线增强", 12, ui.muted, false),
                ui.params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        switchRow.addView(switchTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        setSwitch = new RoundSwitch(this, ui);
        setSwitch.setContentDescription("息屏充电增强");
        setSwitch.setEnabled(false);
        setSwitch.setListener(this::setConfig);
        switchRow.addView(setSwitch, ui.params(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        control.addView(switchRow);

        control.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 18, 0, 18, 0));

        LinearLayout wake = settingRow("唤醒锁屏衔接", "真正解锁后 2 秒交还原厂", false,
                ui.text("已启用", 13, ui.good, false));
        wake.setOnClickListener(v -> showWakeInfo());
        control.addView(wake);

        control.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 18, 0, 18, 0));

        LinearLayout curve = settingRow("温度—档位曲线", "当前工程初值，仅供查看", false, null);
        curve.setOnClickListener(v -> showCurve());
        control.addView(curve);
        addCard(col, control);

        section(col, "外观");
        LinearLayout appearance = ui.card();
        appearance.setPadding(0, 0, 0, 0);
        themeValue = ui.text(ThemePref.label(ThemePref.mode(this)), 13, ui.muted, false);
        LinearLayout themeRow = settingRow("主题模式", "跟随系统或强制深色 / 浅色", false, themeValue);
        themeRow.setOnClickListener(v -> pickTheme());
        appearance.addView(themeRow);
        addCard(col, appearance);

        section(col, "数据与诊断");
        LinearLayout data = ui.card();
        data.setPadding(0, 0, 0, 0);

        LinearLayout export = settingRow("导出历史数据", "CSV 新格式已移除转速列", false,
                ui.text("CSV", 13, ui.muted, false));
        export.setOnClickListener(v -> exportCsv());
        data.addView(export);

        data.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 18, 0, 18, 0));

        compatibilityValue = ui.text("待诊断", 12, ui.muted, false);
        LinearLayout compat = settingRow("版本兼容性", "查看系统版本与 LSPosed 支持范围", false, compatibilityValue);
        compat.setOnClickListener(v -> showCompatibility());
        data.addView(compat);
        data.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 18, 0, 18, 0));

        LinearLayout diag = settingRow("模块诊断", "查看运行框架、Hook 与固件接口", false,
                ui.text("查看", 13, ui.muted, false));
        diag.setOnClickListener(v -> showDiagnostics());
        data.addView(diag);

        data.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 18, 0, 18, 0));

        LinearLayout report = settingRow("设备兼容报告", "导出这台机器的适配证据，供他人适配", false,
                ui.text("导出", 13, ui.muted, false));
        report.setOnClickListener(v -> showDeviceReport());
        data.addView(report);

        data.addView(ui.divider(), ui.params(ViewGroup.LayoutParams.MATCH_PARENT, 1, 18, 0, 18, 0));

        LinearLayout clear = settingRow("清空历史", "删除全部历史遥测，无法撤销", true, null);
        clear.setOnClickListener(v -> confirmClear());
        data.addView(clear);
        addCard(col, data);

        appVersionLine = ui.text("App " + BuildInfo.APP_VERSION + " · 运行 Hook 未知", 11, ui.muted, false);
        appVersionLine.setLineSpacing(ui.dp(4), 1f);
        col.addView(appVersionLine, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 16, 4, 0));

        TextView policy = ui.text("仅在息屏有线充电且原厂自动模式时增强；点亮但仍在锁屏可延续，真正解锁后交还原厂。"
                        + "口袋、通话、手柄、故障等原厂保护仍然优先。",
                11, ui.muted, false);
        policy.setLineSpacing(ui.dp(4), 1f);
        col.addView(policy, ui.params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 8, 4, 0));
    }

    void showPage(int index) {
        if (index < 0 || index >= pages.length) return;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        nav.setSelected(index);
    }

    void applyRange() {
        if (tempChart != null) tempChart.setRange(rangeMinutes);
        if (levelChart != null) levelChart.setRange(rangeMinutes);
        if (trendSubtitle != null) {
            trendSubtitle.setText(rangeMinutes == 0
                    ? "温度与档位 · 本次数据可用范围"
                    : "温度与档位 · 最近 " + rangeMinutes + " 分钟");
        }
    }

    void toggleDiagnostics() {
        diagnostics = !diagnostics;
        levelChart.setDiagnostics(diagnostics);
        diagChip.setText(diagnostics ? "收起诊断" : "展开诊断");
        diagChip.setTextColor(diagnostics ? ui.accent : ui.muted);
        diagChip.setBackground(ui.rounded(diagnostics ? ui.accentSoft : ui.surface2, 20));
    }

    // ---------------------------------------------------------------- actions

    void moreMenu() {
        String[] items = {"刷新数据", "打开 LSPosed", "模块诊断", "版本兼容性", "导出设备兼容报告"};
        Sheet.choices(this, ui, "更多", items, -1, index -> {
            if (index == 0) load(true);
            else if (index == 1) openLsposed();
            else if (index == 2) showDiagnostics();
            else if (index == 3) showCompatibility();
            else showDeviceReport();
        });
    }

    void openLsposed() {
        if (FrameworkCompat.openIndependent(this)) {
            Toast.makeText(this, "仅启用目标包 com.oplus.exsystemservice；模块更新后需正常重启", Toast.LENGTH_LONG).show();
            return;
        }
        Sheet.choices(this, ui, "无法直接打开独立管理器", new String[]{
                "尝试寄生管理器入口（需框架已激活）", "查看手动进入与激活说明"}, -1, index -> {
            if (index == 0) {
                boolean requested = FrameworkCompat.openParasitic(this);
                Toast.makeText(this, requested ? "已请求寄生入口；若未进入，请从 LSPosed 通知或快捷方式打开"
                        : "系统未允许此入口；请从 LSPosed 通知或快捷方式打开", Toast.LENGTH_LONG).show();
            } else {
                Sheet.info(this, ui, "管理器与框架是两回事",
                        "没有可启动的独立 Manager，不代表没有安装或激活 LSPosed。寄生、隐藏或改包名版本请从其通知、桌面快捷方式或 Root 管理器的模块操作入口进入。\n\n"
                        + "启用本模块，只勾选 com.oplus.exsystemservice，然后正常重启。仅安装 Manager APK 不会使框架生效。\n\n"
                        + "框架版本首先要支持你的 Android；请在“版本兼容性”查看参考范围。");
            }
        });
    }

    void showCompatibility() {
        JSONObject j = runtimeRecord;
        StringBuilder b = new StringBuilder("当前设备\n").append(FrameworkCompat.systemLine(this)).append("\n\n");
        appendCompatibility(b, j);
        b.append("\n").append(FrameworkCompat.managers(this)).append("\n\n").append(CompatibilityCatalog.describe());
        Sheet.info(this, ui, "系统与 LSPosed 版本兼容性", b.toString());
    }

    void appendCompatibility(StringBuilder b, JSONObject j) {
        if (j == null) {
            b.append("运行状态：暂无目标服务的注入回报；无法仅凭 Manager 判断是否激活。\n");
            return;
        }
        b.append(runtimeFresh(j) ? "本次有效回报\n" : "历史回报（已过期，不能证明当前激活）\n");
        b.append("Hook：").append(j.optString("hookVersion", "未知/旧版")).append('\n');
        if (!BuildInfo.HOOK_VERSION.equals(j.optString("hookVersion", ""))) {
            b.append(runtimeFresh(j) ? "新兼容层尚未加载，请正常重启后重新诊断。\n"
                    : "历史 Hook 版本不同；当前加载状态未知，请重新诊断。\n");
        }
        b.append("接口诊断：").append(DiagnosticReport.statusLabel(j.optString("compatibilityStatus", ""))).append('\n');
        String stage = j.optString("compatibilityStage", "");
        if (!stage.isEmpty()) b.append("上报阶段：").append(stage).append('\n');
        String detail = j.optString("compatibilityDetail", "");
        if (!detail.isEmpty()) b.append("上报详情：").append(detail).append('\n');
        if (j.has("targetVersion")) b.append("原厂服务：").append(j.optString("targetVersion")).append('\n');
        if (j.has("firmwareProfile")) b.append("接口档案：").append(j.optString("firmwareProfile")).append('\n');
        String progress = reportField(j, "runtimeProgress");
        String counters = reportField(j, "runtimeCounters");
        String evidence = reportField(j, "runtimeEvidence");
        String failureStage = reportField(j, "lastFailureStage");
        String failureDetail = reportField(j, "lastFailureDetail");
        b.append("运行进度：").append(progress.isEmpty() ? "未上报" : progress).append('\n');
        b.append("运行计数：").append(counters.isEmpty() ? "未上报" : counters).append('\n');
        b.append("逐环节证据（上报原文，上游可能截断）：\n").append(evidence.isEmpty() ? "未上报" : evidence);
        if (!evidence.endsWith("\n")) b.append('\n');
        b.append("最近失败阶段：").append(failureStage.isEmpty() ? "未上报 / 未观察到" : failureStage).append('\n');
        b.append("最近失败详情：").append(failureDetail.isEmpty() ? "未上报 / 未观察到" : failureDetail).append('\n');
        b.append("最近失败记录在恢复后仍保留，是否仍异常需结合最新证据；未上报或未触发不代表失败。\n");
        b.append("Xposed Bridge API：").append(j.isNull("frameworkApi") ? "旧版未回报/未知" : j.optString("frameworkApi", "未知"))
                .append("（不是 LSPosed 版本号）\n");
        String version = j.isNull("frameworkVersion") ? "" : j.optString("frameworkVersion", "");
        b.append("运行框架版本：").append(version.isEmpty() ? "框架未公开，不使用 Manager 版本冒充" : version).append('\n');
        if (!version.isEmpty()) b.append("来源：").append(j.optString("frameworkVersionSource", "注入框架")).append('\n');
        b.append("注入、接口接入与实际增强效果是独立结论；控制请求或原厂状态确认不等于实测物理转速。\n");
    }

    boolean runtimeFresh(JSONObject j) {
        return j != null && ReportFreshness.fresh(j.optLong("wall", 0), j.optLong("elapsed", -1),
                System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), j.optString("boot", ""),
                currentBootId, Sampling.staleMs(Sampling.IDLE_MS));
    }

    ChartView.Sample latestForRender(List<ChartView.Sample> list) {
        ChartView.Sample last = list.isEmpty() ? null : list.get(list.size() - 1);
        for (int i = list.size() - 1; i >= 0; i--) {
            ChartView.Sample sample = list.get(i);
            if (!"已注入".equals(sample.raw.optString("hook", ""))) continue;
            runtimeRecord = sample.raw;
            if (runtimeFresh(sample.raw)) return sample;
            break;
        }
        return last;
    }

    void pickTheme() {
        String[] labels = {"跟随系统", "浅色", "深色"};
        int current = ThemePref.mode(this);
        Sheet.choices(this, ui, "主题模式", labels, current, index -> {
            if (index == current) return;
            ThemePref.set(this, index);
            recreate();
        });
    }

    void showWakeInfo() {
        Sheet.info(this, ui, "唤醒锁屏衔接",
                "只有先前在息屏充电中真正生效的增强会话，才允许在点亮但仍停留在锁屏时延续。\n\n"
                        + "真正解锁后最多 2 秒短暂衔接，然后交还原厂完整重算。\n\n"
                        + "拔线、停止充电、手动模式、数据失效、故障以及原厂噪声/手柄保护仍会立刻取消增强。"
                        + "没有先前的息屏会话时，仅点亮锁屏不会新起增强。");
    }

    void showCurve() {
        Sheet.info(this, ui, "温度—档位曲线",
                ChargeCurve.describe() + "\n\n这是工程初值，不是安全温度边界。本页只做展示，"
                        + "不能在界面里修改控制策略。");
    }

    void showDiagnostics() {
        ChartView.Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        JSONObject j = runtimeRecord != null ? runtimeRecord : (last == null ? null : last.raw);
        StringBuilder b = new StringBuilder();
        b.append("App 版本：").append(BuildInfo.APP_VERSION).append('\n');
        appendCompatibility(b, runtimeRecord);
        String hookVersion = j == null ? "" : j.optString("hookVersion", "");
        b.append("运行 Hook：").append(hookVersion.isEmpty()
                ? (j == null ? "未知" : "未知（旧版 Hook，无版本字段）") : hookVersion).append('\n');
        String reportedHook = j == null ? "等待遥测" : nonEmpty(j.optString("hook", "未知"));
        if (j != null && "已注入".equals(reportedHook) && !runtimeFresh(j))
            reportedHook = "历史回报：已注入（已过期）";
        b.append("注入状态：").append(reportedHook).append('\n');
        if (j != null) {
            b.append("固件：").append(nonEmpty(j.optString("build", "--"))).append('\n');
            b.append("阶段：").append(nonEmpty(j.optString("phase", "--")))
                    .append(" · 锁屏 ").append(bool(j, "keyguardLocked"))
                    .append(" · 亮屏 ").append(bool(j, "interactive")).append('\n');
            b.append("开关：").append(j.optBoolean("enabled", false) ? "开" : "关")
                    .append(" · 配置版本 ").append(j.optLong("configVersion", 0)).append('\n');
            b.append("原厂 / 候选 / 请求：").append(level(j, "oem")).append(" / ")
                    .append(level(j, "candidate")).append(" / ").append(level(j, "request")).append('\n');
            b.append("驱动 / 有效：").append(level(j, "driver")).append(" / ").append(level(j, "effective")).append('\n');
            b.append("驱动状态：").append(nonEmpty(j.optString("fanStatus", "--")))
                    .append(" · state ").append(level(j, "state")).append('\n');
            b.append("原因：").append(nonEmpty(j.optString("reason", "--"))).append('\n');
        }
        b.append("\n本页不含转速：转速读取已移除，也不以档位换算冒充转速。");
        Sheet.info(this, ui, "模块诊断", b.toString());
    }

    // ---------------------------------------------------------------- device report

    /** Fills the pure formatter from the latest runtime record plus this app's own context. */
    DiagnosticReport.Input reportInput() {
        DiagnosticReport.Input in = new DiagnosticReport.Input();
        JSONObject j = runtimeRecord;
        in.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        in.appVersion = BuildInfo.APP_VERSION;
        in.hookVersion = j == null ? "" : j.optString("hookVersion", "");
        // Same rule the UI uses for "待重载": an injected hook with the wrong version is stale.
        boolean injected = j != null && "已注入".equals(j.optString("hook", "")) && runtimeFresh(j);
        in.hookStale = injected && !BuildInfo.HOOK_VERSION.equals(in.hookVersion);
        in.model = Build.MODEL;
        in.device = Build.DEVICE;
        in.brand = Build.BRAND + " / " + Build.MANUFACTURER;
        in.release = Build.VERSION.RELEASE;
        in.sdk = String.valueOf(Build.VERSION.SDK_INT);
        in.display = Build.DISPLAY;
        in.fingerprint = Build.FINGERPRINT;
        in.incremental = Build.VERSION.INCREMENTAL;
        in.hardwareGate = j == null ? "" : j.optString("hardwareGate", "");
        in.hookState = j == null ? "" : j.optString("hook", "");
        if ("已注入".equals(in.hookState) && !runtimeFresh(j)) {
            in.hookState = "历史注入回报（已过期）";
            in.extra = "第 [2]–[6] 节的 Hook 状态和运行证据来自过期历史回报，不能证明当前状态。";
        }
        in.frameworkApi = j == null || j.isNull("frameworkApi") ? "" : j.optString("frameworkApi", "");
        in.frameworkVersion = j == null || j.isNull("frameworkVersion") ? "" : j.optString("frameworkVersion", "");
        in.manager = FrameworkCompat.managers(this);
        in.scope = j == null ? "com.oplus.exsystemservice" : j.optString("targetScope", "com.oplus.exsystemservice");
        in.status = j == null ? "" : j.optString("compatibilityStatus", "");
        in.stage = j == null ? "" : j.optString("compatibilityStage", "");
        in.detail = j == null ? "" : j.optString("compatibilityDetail", "");
        in.profile = j == null ? "" : j.optString("firmwareProfile", "");
        in.trace = j == null ? "" : j.optString("compatibilityTrace", "");
        in.runtimeEvidence = reportField(j, "runtimeEvidence");
        in.runtimeProgress = reportField(j, "runtimeProgress");
        in.runtimeCounters = reportField(j, "runtimeCounters");
        in.lastFailureStage = reportField(j, "lastFailureStage");
        in.lastFailureDetail = reportField(j, "lastFailureDetail");
        in.knownApk = j == null ? "" : j.optString("knownApk", "");
        // Fall back to what this app can read itself when the hook never reported (not injected).
        in.targetPackage = j == null || j.optString("targetPackage", "").isEmpty()
                ? "com.oplus.exsystemservice" : j.optString("targetPackage");
        in.targetVersion = targetVersionOf(in.targetPackage);
        if (j != null) {
            in.targetPath = j.optString("targetPath", "");
            in.targetSha256 = j.optString("targetSha256", "");
            in.enabled = bool(j, "enabled");
            in.active = bool(j, "active");
            in.phase = j.optString("phase", "");
            in.reason = j.optString("reason", "");
            in.oem = level(j, "oem");
            in.candidate = level(j, "candidate");
            in.request = level(j, "request");
            in.driver = level(j, "driver");
            in.effective = level(j, "effective");
            in.battery = temp(j, "battery");
            in.board = temp(j, "board");
            in.fan = temp(j, "fan");
            in.mos = temp(j, "mos");
            in.plugged = pluggedText(j);
            in.keyguard = bool(j, "keyguardLocked");
            in.interactive = bool(j, "interactive");
        }
        if (in.targetPath == null || in.targetPath.isEmpty()) in.targetPath = "未上报";
        if (in.targetSha256 == null || in.targetSha256.isEmpty()) in.targetSha256 = "未上报（不作为接入门槛）";
        return in;
    }

    static String reportField(JSONObject j, String key) {
        return j == null || j.isNull(key) ? "" : j.optString(key, "");
    }

    String targetVersionOf(String pkg) {
        try {
            String v = getPackageManager().getPackageInfo(pkg, 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "未安装或不可见";
        }
    }

    static String pluggedText(JSONObject j) {
        Object v = j.opt("plugged");
        if (!(v instanceof Number)) return "—";
        int p = ((Number) v).intValue();
        if (p == 0) return "未接电";
        StringBuilder b = new StringBuilder();
        if ((p & 2) != 0) b.append("USB ");
        if ((p & 1) != 0) b.append("AC ");
        if ((p & 4) != 0) b.append("无线 ");
        return b.length() == 0 ? String.valueOf(p) : b.toString().trim();
    }

    String buildReport() {
        return DiagnosticReport.build(reportInput());
    }

    void showDeviceReport() {
        String report = buildReport();
        String[] items = {"复制到剪贴板", "分享文本", "保存为 .txt 文件"};
        Sheet.choices(this, ui, "设备兼容报告", items, -1, index -> {
            if (index == 0) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) {
                    Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                    return;
                }
                cm.setPrimaryClip(ClipData.newPlainText("ACE 7 系列设备兼容报告", report));
                Toast.makeText(this, "已复制 " + report.length() + " 字，可直接粘贴给对方", Toast.LENGTH_LONG).show();
            } else if (index == 1) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "ACE 7 系列风扇助手 · 设备兼容报告");
                i.putExtra(Intent.EXTRA_TEXT, report);
                try {
                    startActivity(Intent.createChooser(i, "分享设备兼容报告"));
                } catch (Exception e) {
                    Toast.makeText(this, "没有可用的分享目标：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, "ace7pro-fan-report.txt");
                startActivityForResult(i, CREATE_REPORT);
            }
        });
    }

    void confirmClear() {
        Sheet.confirm(this, ui, "清空历史？",
                "将删除 Provider 中的全部历史遥测，无法撤销。当前增强开关和主题设置不受影响。",
                "清空", true, () -> io.execute(() -> {
                    String error = null;
                    try {
                        getContentResolver().call(BASE, "clear", null, null);
                    } catch (Exception e) {
                        error = e.toString();
                    }
                    final String er = error;
                    handler.post(() -> {
                        Toast.makeText(this, er == null ? "历史已清空" : "清空失败：" + er, Toast.LENGTH_LONG).show();
                        load(false);
                    });
                }));
    }

    void exportCsv() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "ace7pro-fan-telemetry.csv");
        startActivityForResult(i, CREATE_CSV);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (req == CREATE_REPORT) {
            saveReport(data.getData());
            return;
        }
        if (req != CREATE_CSV) return;
        Uri uri = data.getData();
        ArrayList<ChartView.Sample> copy = new ArrayList<>(samples);
        io.execute(() -> {
            String error = null;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("无法打开目标文件");
                out.write(csv(copy).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                error = e.toString();
            }
            final String er = error;
            handler.post(() -> Toast.makeText(this,
                    er == null ? "CSV 已导出（已移除转速列）" : "导出失败：" + er, Toast.LENGTH_LONG).show());
        });
    }

    void saveReport(Uri uri) {
        final String report = buildReport();
        io.execute(() -> {
            String error = null;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("无法打开目标文件");
                out.write(report.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                error = e.toString();
            }
            final String er = error;
            handler.post(() -> Toast.makeText(this,
                    er == null ? "报告已保存" : "保存失败：" + er, Toast.LENGTH_LONG).show());
        });
    }

    // ---------------------------------------------------------------- data

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        handler.removeCallbacks(poll);
        load(false);
        handler.postDelayed(poll, 2000);
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        handler.removeCallbacks(poll);
        io.shutdown();
        super.onDestroy();
    }

    final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            load(false);
            handler.postDelayed(this, 2000);
        }
    };

    void load(boolean announce) {
        if (!alive || !loading.compareAndSet(false, true)) return;
        io.execute(() -> {
            Bundle cfg = null;
            ArrayList<ChartView.Sample> got = new ArrayList<>();
            String error = null;
            try {
                cfg = getContentResolver().call(BASE, "config", null, null);
                Bundle latest = getContentResolver().call(BASE, "latest", null, null);
                JSONObject lj = null;
                if (latest != null) {
                    String raw = latest.getString("json");
                    if (raw != null) {
                        try {
                            lj = new JSONObject(raw);
                        } catch (Exception ignored) { }
                    }
                }
                long now = System.currentTimeMillis();
                boolean hooked = lj != null
                        && ("已注入".equals(lj.optString("hook")) || "Root只读预览".equals(lj.optString("hook")))
                        && runtimeFresh(lj);
                hooked = hooked || runtimeFresh(runtimeRecord);
                if (!hooked && alive && now - lastLocalPush >= 2000) pushLocalSample(now);

                Uri u = BASE.buildUpon().appendPath("samples").build();
                try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                    if (c != null) {
                        int j = c.getColumnIndex("json");
                        while (c.moveToNext() && got.size() < 4000) {
                            String s = c.getString(j);
                            if (s != null) {
                                try {
                                    got.add(ChartView.Sample.parse(new JSONObject(s)));
                                } catch (Exception ignored) { }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                error = e.toString();
            }

            final Bundle c = cfg;
            final ArrayList<ChartView.Sample> list = got;
            final String err = error;
            handler.post(() -> {
                loading.set(false);
                if (isFinishing() || isDestroyed()) return;
                if (c != null) {
                    configVersion = c.getLong("version", 0);
                    configReady = true;
                    syncSwitches(c.getBoolean("enabled", false));
                }
                samples.clear();
                samples.addAll(list);
                tempChart.setSamples(samples);
                levelChart.setSamples(samples);
                miniChart.setSamples(samples);
                render(list, err);
                if (announce && err == null) {
                    Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    void pushLocalSample(long now) {
        try {
            Class<?> tc = Class.forName("dev.eta.acefan.Telemetry");
            Object value = tc.getMethod("sample", android.content.Context.class).invoke(null, this);
            JSONObject j = value instanceof JSONObject ? (JSONObject) value : new JSONObject(String.valueOf(value));
            j.put("hook", "未注入·App观测");
            j.put("reason", "仅展示，尚未接管；在 LSPosed 启用并勾选目标包后需正常重启");
            j.put("build", Build.DISPLAY);
            j.put("configVersion", configVersion);
            if (!j.has("wall")) j.put("wall", now);
            if (!j.has("elapsed")) j.put("elapsed", android.os.SystemClock.elapsedRealtime());
            if (!j.has("boot")) j.put("boot", "app-observation");
            for (String k : new String[]{"board", "fan", "mos", "driver", "effective", "state",
                    "fanStatus", "candidate", "request", "oem", "phase", "hookVersion", "active"}) {
                if (!j.has(k)) j.put(k, JSONObject.NULL);
            }
            Bundle b = new Bundle();
            b.putString("json", j.toString());
            getContentResolver().call(BASE, "push", null, b);
            lastLocalPush = now;
        } catch (Exception ignored) {
            lastLocalPush = now;
        }
    }

    void syncSwitches(boolean enabled) {
        syncing = true;
        ovSwitch.setChecked(enabled, false);
        setSwitch.setChecked(enabled, false);
        ovSwitch.setEnabled(configReady);
        setSwitch.setEnabled(configReady);
        syncing = false;
    }

    void setConfig(boolean on) {
        if (syncing || !configReady) return;
        ovSwitch.setEnabled(false);
        setSwitch.setEnabled(false);
        io.execute(() -> {
            Bundle x = new Bundle();
            x.putBoolean("enabled", on);
            Bundle r = null;
            String e = null;
            try {
                r = getContentResolver().call(BASE, "setConfig", null, x);
            } catch (Exception ex) {
                e = ex.toString();
            }
            final Bundle rr = r;
            final String ee = e;
            handler.post(() -> {
                ovSwitch.setEnabled(true);
                setSwitch.setEnabled(true);
                if (rr == null) {
                    Toast.makeText(this, "设置失败：" + (ee == null ? "Provider 无响应" : ee),
                            Toast.LENGTH_LONG).show();
                    load(false);
                } else {
                    configVersion = rr.getLong("version", configVersion);
                    syncSwitches(rr.getBoolean("enabled", on));
                }
            });
        });
    }

    // ---------------------------------------------------------------- render

    void render(List<ChartView.Sample> list, String err) {
        if (err != null) {
            setStatus("读取失败", ui.warn);
            ovPhase.setText("无法读取遥测");
            ovPhaseDesc.setText(err);
            ovPhaseDesc.setTextColor(ui.warn);
            setDot(ui.warn);
            ovFresh.setText("Provider 不可用");
            appVersionLine.setText("App " + BuildInfo.APP_VERSION + " · 运行 Hook 未知");
            return;
        }

        ChartView.Sample last = latestForRender(list);
        JSONObject j = last == null ? null : last.raw;
        String hook = j == null ? "" : j.optString("hook", "");
        String hookVersion = j == null ? "" : j.optString("hookVersion", "");
        boolean injected = "已注入".equals(hook) && runtimeFresh(j);
        // A hook without a version field is older than this build; treat it as needing a reload too.
        boolean stale = injected && !BuildInfo.HOOK_VERSION.equals(hookVersion);

        if (j == null) setStatus("等待遥测", ui.muted);
        else if (injected) {
            String status = j.optString("compatibilityStatus", "");
            boolean waiting = "pending".equals(status);
            boolean notReady = "setup-failed".equals(status) || "outside-family".equals(status)
                    || "unsupported".equals(status) || "runtime-error".equals(status);
            boolean hasFailureRecord = "partial-error".equals(status)
                    || !reportField(j, "lastFailureStage").isEmpty()
                    || !reportField(j, "lastFailureDetail").isEmpty();
            setStatus(stale ? "待重载" : notReady || hasFailureRecord ? "已注入·查看诊断"
                            : waiting ? "已注入·待接入" : "已注入",
                    stale || notReady ? ui.warn : hasFailureRecord ? ui.accent : waiting ? ui.muted : ui.good);
        }
        else if ("Root只读预览".equals(hook)) setStatus("只读预览", ui.accent);
        else setStatus("未注入", ui.warn);

        String phase = j == null ? "" : j.optString("phase", "");
        boolean active = j != null && j.optBoolean("active", false);
        String reason = j == null ? "" : j.optString("reason", "");
        applyPhase(j, injected, active && injected && !stale, phase, reason);
        if (stale) {
            ovPhase.setText("待重载");
            ovPhaseDesc.setText("目标服务仍运行旧版 Hook；请正常重启以加载本版兼容层");
        }
        if (compatibilityValue != null) {
            String status = j == null ? "" : j.optString("compatibilityStatus", "");
            compatibilityValue.setText(stale ? "待重载" : !injected ? "待注入" : DiagnosticReport.statusLabel(status));
        }

        ovBattery.setText(temp(j, "battery"));
        ovBatterySub.setText(batterySource(j));
        ovLevel.setText(level(j, "effective"));
        ovBoard.setText(temp(j, "board"));
        ovFan.setText(temp(j, "fan"));
        ovMos.setText(temp(j, "mos"));

        if (last == null) {
            ovFresh.setText("遥测 · 等待首条样本");
        } else {
            long age = Math.max(0, System.currentTimeMillis() - last.wall);
            // Idle sampling stores one sample per 30 s, so freshness is judged against that cadence.
            String fresh = age < 45000 ? "新鲜" : age < 75000 ? "略有延迟" : "已过期";
            ovFresh.setText("遥测 " + Ui.time(last.wall) + " · " + fresh + " · " + (age / 1000) + " 秒前");
        }

        if (hookVersion.isEmpty()) {
            appVersionLine.setText("App " + BuildInfo.APP_VERSION + (injected ? " · 旧版 Hook 未报版本，需正常重启" : " · 暂无有效运行 Hook 回报"));
        } else {
            appVersionLine.setText("App " + BuildInfo.APP_VERSION + " · 运行 Hook " + hookVersion
                    + (stale ? "（尚未重载）" : ""));
        }
    }

    void setStatus(String text, int color) {
        ovStatus.setText(text);
        ovStatus.setTextColor(color);
        ovStatus.setBackground(ui.rounded((color & 0x00ffffff) | 0x22000000, 20));
    }

    void setDot(int color) {
        ovDot.setBackground(ui.rounded(color, 4));
    }

    void applyPhase(JSONObject j, boolean injected, boolean active, String phase, String reason) {
        String title;
        String desc;
        int color;
        if (j == null) {
            title = "等待遥测";
            desc = "尚无遥测样本，稍候或点右上角刷新";
            color = ui.muted;
        } else if (!injected) {
            title = "仅观测";
            desc = "暂无有效的目标服务注入回报；当前为本地观测或历史读数";
            color = ui.warn;
        } else if (active) {
            switch (phase) {
                case "screen_off":
                    title = "息屏增强";
                    desc = "有线充电中 · 屏幕已熄灭 · 原厂自动模式";
                    color = ui.good;
                    break;
                case "locked_handoff":
                    title = "锁屏衔接";
                    desc = "屏幕已亮但仍停留在锁屏，延续本次增强";
                    color = ui.good;
                    break;
                case "unlock_grace":
                    title = "解锁交还";
                    desc = "已解锁，2 秒内交还原厂完整重算";
                    color = ui.accent;
                    break;
                default:
                    title = "保留原厂";
                    desc = reason.isEmpty() ? "当前交还原厂自动调速" : reason;
                    color = ui.muted;
            }
        } else {
            title = "保留原厂";
            desc = reason.isEmpty() ? "当前未满足增强条件" : reason;
            color = severe(reason) ? ui.warn : ui.muted;
        }
        ovPhase.setText(title);
        ovPhaseDesc.setText(desc);
        ovPhaseDesc.setTextColor(color == ui.warn ? ui.warn : ui.muted);
        setDot(color);
    }

    static boolean severe(String reason) {
        if (reason == null) return false;
        return reason.contains("故障") || reason.contains("异常") || reason.contains("熔断")
                || reason.contains("不匹配") || reason.contains("失败") || reason.contains("不可用");
    }

    static String temp(JSONObject j, String key) {
        if (j == null) return "—";
        double v = j.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.1f", v);
    }

    static String level(JSONObject j, String key) {
        if (j == null) return "—";
        Object v = j.opt(key);
        if (!(v instanceof Number)) return "—";
        return String.valueOf(((Number) v).intValue());
    }

    static String bool(JSONObject j, String key) {
        Object v = j.opt(key);
        if (!(v instanceof Boolean)) return "—";
        return ((Boolean) v) ? "是" : "否";
    }

    static String batterySource(JSONObject j) {
        if (j == null) return "thermal:battery";
        String s = j.optString("batterySource", "");
        switch (s) {
            case "thermal:battery":
                return "热区 battery";
            case "sysfs:battery/temp":
                return "电源节点";
            case "battery_sticky":
                return "电池广播（回退）";
            default:
                return "不可用";
        }
    }

    static String nonEmpty(String s) {
        return s == null || s.length() == 0 ? "--" : s;
    }

    // ---------------------------------------------------------------- csv

    /** New format: no RPM column. Old rows keep rpm in JSON but it is never exported. */
    static String csv(List<ChartView.Sample> data) {
        StringBuilder b = new StringBuilder("wall_iso,wall_ms,elapsed_ms,boot,battery_C,board_C,fan_C,mos_C,"
                + "driverLevel,levelEff,state,oem,candidate,request,active,reason,interactive,plugged,charging,"
                + "quality,configVersion,hook,hookVersion,phase,keyguardLocked,handoffRemainingMs,build\n");
        for (ChartView.Sample s : data) {
            JSONObject j = s.raw;
            String[] keys = {"boot", "battery", "board", "fan", "mos", "driver", "effective", "state",
                    "oem", "candidate", "request", "active", "reason", "interactive", "plugged", "charging",
                    "quality", "configVersion", "hook", "hookVersion", "phase", "keyguardLocked",
                    "handoffRemainingMs", "build"};
            b.append('"').append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                    .format(new Date(s.wall))).append("\",")
                    .append(s.wall).append(',').append(s.elapsed);
            for (String k : keys) {
                Object o = j.opt(k);
                b.append(',').append(o == null || o == JSONObject.NULL ? "" : quote(String.valueOf(o)));
            }
            b.append('\n');
        }
        return b.toString();
    }

    static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
