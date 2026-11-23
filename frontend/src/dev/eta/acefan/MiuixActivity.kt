package dev.eta.acefan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

class MiuixActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FanApp() }
    }

    @Composable
    private fun FanApp() {
        var appearance by remember { mutableIntStateOf(ThemePref.mode(this)) }
        val dark = when (appearance) {
            ThemePref.DARK -> true
            ThemePref.LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val colors = remember(dark) {
            if (dark) darkColorScheme(background = Color(0xFF131519), surfaceContainer = Color(0xFF202328),
                surfaceContainerHigh = Color(0xFF292D34), onSurfaceVariantSummary = Color(0xFFA4ABB6))
            else lightColorScheme(background = Color(0xFFF5F6F8), surfaceContainer = Color.White)
        }
        MiuixTheme(colors = colors) {
            FanContent(appearance) { selected ->
                ThemePref.set(this, selected)
                appearance = selected
            }
        }
    }

    @Composable
    private fun FanContent(appearance: Int, onAppearance: (Int) -> Unit) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var route by rememberSaveable { mutableStateOf("") }
        var period by rememberSaveable { mutableIntStateOf(15) }
        var trendsExpanded by rememberSaveable { mutableStateOf(false) }
        var chargeExpanded by rememberSaveable { mutableStateOf(false) }
        var snapshot by remember { mutableStateOf<FrontModel.Snapshot?>(null) }
        var editorBase by remember { mutableStateOf<FrontModel.Snapshot?>(null) }
        var busy by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        var clearDialog by rememberSaveable { mutableStateOf(false) }
        var report by rememberSaveable { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val gate = remember { Mutex() }
        val context = applicationContext
        val historyWanted = route.isEmpty() && tab == 1

        suspend fun reload(includeHistory: Boolean) {
            gate.withLock {
                snapshot = withContext(Dispatchers.IO) { FrontModel.load(context, includeHistory) }
            }
        }
        LaunchedEffect(historyWanted) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    reload(historyWanted)
                    delay(if (historyWanted) 5_000 else 3_000)
                }
            }
        }

        fun saveDaily(config: DailyFanConfig, version: Long, closeOnSave: Boolean) {
            if (busy) return
            busy = true
            notice = if (closeOnSave) "正在保存日用设置…" else if (config.enabled) "正在开启日用增强…" else "正在关闭日用增强…"
            scope.launch {
                try {
                    gate.withLock {
                        val (reply, fresh) = withContext(Dispatchers.IO) {
                            val result = FrontModel.saveDaily(context, config, version)
                            result to FrontModel.load(context, false)
                        }
                        snapshot = fresh
                        when (reply.status) {
                            FrontModel.SaveStatus.SAVED -> {
                                if (closeOnSave) { route = ""; editorBase = null }
                                notice = "设置已保存 v${reply.version} · ${if (config.enabled) "日用已开启" else "日用仍关闭"}"
                            }
                            FrontModel.SaveStatus.VERSION_CONFLICT -> notice = "未保存：设置已被更新，请依据最新设置重试。"
                            else -> notice = "未确认保存 · 请核对当前设置"
                        }
                    }
                } finally { busy = false }
            }
        }

        fun saveCharge(enabled: Boolean) {
            if (busy || snapshot?.charge?.valid != true) return
            busy = true
            scope.launch {
                try {
                    gate.withLock {
                        val (reply, fresh) = withContext(Dispatchers.IO) {
                            val result = FrontModel.saveCharge(context, enabled)
                            result to FrontModel.load(context, false)
                        }
                        snapshot = fresh
                        notice = if (reply.valid && reply.enabled == enabled) "充电设置已保存" else "未确认保存 · 已重新读取"
                    }
                } finally { busy = false }
            }
        }

        val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) scope.launch {
                val rows = withContext(Dispatchers.IO) { runCatching { FrontExtras.exportCsv(context, uri) }.getOrNull() }
                toast(if (rows == null) "导出未完成" else "已导出 $rows 条历史数据")
            }
        }
        val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val body = report
            if (uri != null && body.isNotEmpty()) scope.launch {
                val written = withContext(Dispatchers.IO) { runCatching { FrontExtras.writeReport(context, uri, body) }.isSuccess }
                toast(if (written) "报告已保存" else "保存未完成")
            }
        }
        fun openTools() { route = "tools"; notice = "" }
        fun openReport() {
            route = "report"
            report = ""
        }
        if (route == "report" && report.isEmpty()) {
            LaunchedEffect(route) { report = withContext(Dispatchers.IO) { FrontExtras.report(context) } }
        }
        fun closeRoute() {
            if (busy) return
            route = if (route == "report") "tools" else ""
            editorBase = null
            report = ""
        }
        BackHandler(enabled = route.isNotEmpty() || tab != 0) {
            if (route.isNotEmpty()) closeRoute() else tab = 0
        }

        val titles = listOf("风扇助手", "趋势", "设置")
        val tabs = listOf("概览", "趋势", "设置")
        val icons = listOf(R.drawable.nav_overview, R.drawable.nav_trend, R.drawable.nav_settings)
        val page = when (route) {
                "temperature" -> "日用启停温度"
                "level" -> "起转最低档位"
            "weights" -> "传感器权重"
            "tools" -> "数据与诊断"
            "report" -> "设备兼容报告"
            else -> titles[tab]
        }
        val current = snapshot
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            topBar = {
                if (route.isEmpty()) {
                    TopAppBar(title = page, actions = {
                        if (tab == 0) IconButton(onClick = ::openTools) {
                            Icon(ImageVector.vectorResource(R.drawable.nav_more), contentDescription = "数据与诊断")
                        }
                    })
                } else {
                    SmallTopAppBar(title = page, navigationIcon = {
                        IconButton(onClick = ::closeRoute) {
                            Icon(ImageVector.vectorResource(R.drawable.nav_back), contentDescription = "返回")
                        }
                    })
                }
            },
            bottomBar = {
                if (route.isEmpty()) NavigationBar {
                    tabs.forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; notice = "" },
                            icon = ImageVector.vectorResource(icons[i]), label = label)
                    }
                }
            }
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
                key(route, tab) {
                    Column(
                        Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (notice.isNotEmpty() && tab == 2 && route.isEmpty()) SecondaryText(notice)
                        when (route) {
                            "temperature", "level", "weights" -> {
                                val base = editorBase ?: current?.takeIf { it.dailyValid }
                                if (base != null) DailyEditor(route, base, current, busy, notice) { config, version ->
                                    saveDaily(config, version, true)
                                } else SecondaryText("日用设置暂不可读，未修改原有配置。")
                            }
                            "tools" -> ToolsPage(current, onRefresh = { scope.launch { reload(false); toast("已刷新") } },
                                onManager = {
                                    if (!FrameworkCompat.openIndependent(this@MiuixActivity) && !FrameworkCompat.openParasitic(this@MiuixActivity))
                                        toast("未找到可打开的 LSPosed 入口")
                                }, onReport = ::openReport, onCsv = { csvPicker.launch("ace7pro-fan-telemetry.csv") },
                                onClear = { clearDialog = true })
                            "report" -> {
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton("复制", enabled = report.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                                            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.let {
                                                it.setPrimaryClip(ClipData.newPlainText("设备兼容报告", report)); toast("报告已复制")
                                            }
                                        })
                                        TextButton("分享", enabled = report.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "ACE 7 系列设备兼容报告")
                                                putExtra(Intent.EXTRA_TEXT, report)
                                            }
                                            runCatching { startActivity(Intent.createChooser(intent, "分享设备兼容报告")) }
                                                .onFailure { toast("没有可用的分享目标") }
                                        })
                                        TextButton("保存", enabled = report.isNotEmpty(), modifier = Modifier.weight(1f),
                                            onClick = { reportPicker.launch("ace7pro-fan-report.txt") })
                                    }
                                }
                                Text(if (report.isEmpty()) "正在读取报告…" else report,
                                    fontSize = 13.sp, lineHeight = 20.sp)
                            }
                            else -> when (tab) {
                                0 -> Overview(current)
                                1 -> FanTrends(current, period, { period = it }, trendsExpanded, { trendsExpanded = !trendsExpanded })
                                else -> FanSettings(current, busy, appearance, onAppearance, chargeExpanded, { chargeExpanded = !chargeExpanded },
                                    onDailyEnabled = { enabled ->
                                        if (current?.dailyValid == true) {
                                            val c = current.dailyConfig
                                            saveDaily(c.withEnabled(enabled), current.dailyVersion, false)
                                        }
                                    }, onDailyEdit = { field ->
                                        if (current?.dailyValid == true) { editorBase = current; route = field; notice = "" }
                                    }, onChargeEnabled = ::saveCharge, onTools = ::openTools)
                            }
                        }
                    }
                }
            }
            WindowDialog(show = clearDialog, title = "清空历史？", onDismissRequest = { clearDialog = false }) {
                SecondaryText("将删除全部已保存的温度和档位记录。模式设置不会改变。")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton("取消", modifier = Modifier.weight(1f), onClick = { clearDialog = false })
                    TextButton("清空", modifier = Modifier.weight(1f), onClick = {
                        clearDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { FrontExtras.clearHistory(context) }
                            reload(false)
                            toast(if (ok) "历史已清空" else "未确认清空")
                        }
                    })
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

internal val sensorNames = listOf("电池", "主板", "风扇周边", "充电 MOS")
internal val NumericStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun SecondaryText(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier = modifier, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun Overview(snapshot: FrontModel.Snapshot?) {
    val live = snapshot?.live
    val chargePower = live?.power == FrontPresentation.Power.WIRED_CHARGING
    val dailyReading = live?.mode == FrontPresentation.Mode.DAILY && live.dailyConfigAcknowledged
    val reportedFusion = when {
        dailyReading -> live?.dailyFusedTemp ?: Double.NaN
        live?.mode == FrontPresentation.Mode.CHARGE -> live.chargeFusedTemp
        else -> Double.NaN
    }
    val preview = remember(snapshot, reportedFusion, chargePower) {
        if (snapshot?.dailyValid == true && !reportedFusion.isFinite() && !chargePower)
            FrontModel.preview(snapshot.dailyConfig, snapshot) else null
    }
    val fusion = if (reportedFusion.isFinite()) reportedFusion else preview?.fusedTemp ?: Double.NaN
    val fusionLabel = when {
        chargePower -> "充电融合温度"
        preview != null && fusion.isFinite() -> "日用融合温度 · 预览"
        else -> "日用融合温度"
    }
    val mask = preview?.contributorMask ?: if (dailyReading) live?.dailyContributorMask ?: -1 else -1
    val participants = if (mask > 0) sensorNames.filterIndexed { i, _ -> mask and (1 shl i) != 0 } else emptyList()
    val readings = live?.temperatures()
    SecondaryText(statusLine(snapshot), Modifier.padding(start = 2.dp, bottom = 2.dp))
    Card(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (LocalDensity.current.fontScale < 1.4f && maxWidth >= 280.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Metric(fusionLabel, FrontPresentation.temperatureText(fusion), "°C", 36)
                    if (participants.isNotEmpty()) SecondaryText("参与：${participants.joinToString("、")}")
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Metric("有效档位", FrontPresentation.levelText(live?.effectiveLevel ?: Double.NaN), "档", 24)
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(fusionLabel, FrontPresentation.temperatureText(fusion), "°C", 30)
                if (participants.isNotEmpty()) SecondaryText("参与：${participants.joinToString("、")}")
                Metric("有效档位", FrontPresentation.levelText(live?.effectiveLevel ?: Double.NaN), "档", 22)
            }
        }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 19.dp, vertical = 15.dp)) {
            Text("传感器温度", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            for (row in 0..1) {
                if (row == 1) HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in row * 2..row * 2 + 1) {
                        Column(Modifier.weight(1f)) {
                            Metric(sensorNames[i], FrontPresentation.temperatureText(readings?.get(i) ?: Double.NaN), "°C", 22)
                        }
                    }
                }
            }
        }
    }
    if (live != null && live.sampleWallTimeMillis > 0L) {
        val time = remember(live.sampleWallTimeMillis) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(live.sampleWallTimeMillis))
        }
        SecondaryText(if (live.fallback) "只读采样 · $time" else "采样 $time", Modifier.padding(start = 2.dp))
    }
}

@Composable
internal fun Metric(label: String, value: String, unit: String, points: Int) {
    SecondaryText(label)
    val unitColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val amount = buildAnnotatedString {
        append(value)
        if (value != FrontPresentation.MISSING) withStyle(SpanStyle(color = unitColor,
                fontSize = (if (points >= 30) 16 else 13).sp, fontWeight = FontWeight.Normal)) { append(" $unit") }
    }
    Text(amount, style = NumericStyle, fontSize = points.sp, fontWeight = FontWeight.Medium, lineHeight = (points + 6).sp)
}

private fun statusLine(s: FrontModel.Snapshot?): String {
    val l = s?.live ?: return "等待读取"
    if (l.needsReload) return "模块待重载"
    if (l.power == FrontPresentation.Power.EXTERNAL) return "外部供电 · 保留原厂"
    if (l.power == FrontPresentation.Power.WIRED_CHARGING) {
        if (l.chargeActive) return "充电模式 · ${when (l.chargePhase) {
            "locked_handoff" -> "锁屏衔接"; "unlock_grace" -> "解锁交还"; else -> "息屏增强"
        }}"
        return "充电模式 · ${if (l.mode == FrontPresentation.Mode.CHARGE) "保留原厂" else "等待模块回报"}"
    }
    if (l.power != FrontPresentation.Power.BATTERY) return "原厂模式 · 供电状态未知"
    val status = l.dailyStatus(s.dailyValid, s.dailyConfig.enabled)
    val detail = if (status == FrontPresentation.DailyStatus.TOO_COOL)
        "未超过 ${FrontPresentation.temperatureText(s.dailyConfig.startC)} °C" else status.text
    return "日用增强 · $detail"
}

@Composable
private fun FanSettings(s: FrontModel.Snapshot?, busy: Boolean, appearance: Int, onAppearance: (Int) -> Unit,
                        chargeExpanded: Boolean, onChargeExpand: () -> Unit, onDailyEnabled: (Boolean) -> Unit,
                        onDailyEdit: (String) -> Unit, onChargeEnabled: (Boolean) -> Unit, onTools: () -> Unit) {
    val c = s?.dailyConfig?.takeIf { s.dailyValid }
    val canEdit = c != null && !busy
    SecondaryText("日用风扇", Modifier.padding(start = 7.dp))
    Card(Modifier.fillMaxWidth()) {
        SwitchPreference(title = "启用日用增强", checked = c?.enabled == true, enabled = canEdit,
            onCheckedChange = onDailyEnabled)
        ValuePreference("日用启停温度", c?.let { "${FrontPresentation.temperatureText(it.startC)} °C" } ?: "—", canEdit) { onDailyEdit("temperature") }
        ValuePreference("起转最低档位", c?.let { "${it.floor} 档" } ?: "—", canEdit) { onDailyEdit("level") }
        ValuePreference("传感器权重", "编辑", canEdit) { onDailyEdit("weights") }
    }
    if (c == null) SecondaryText(if (s == null) "正在读取配置…" else "日用配置不可读，尚未修改。")
    else if (!c.enabled) SecondaryText("日用未启用 · 保存温度、档位或权重不会自动开启。")
    else {
        val l = s.live
        val ack = if (l.dailyConfigAcknowledged) "模块已读 v${s.dailyVersion}" else "设置已保存 v${s.dailyVersion}"
        SecondaryText("$ack · ${l.dailyStatus(true, true).text}")
    }
    Card(Modifier.fillMaxWidth()) {
        ValuePreference("充电模式", if (chargeExpanded) "收起" else "原规则不变", true, onChargeExpand)
        if (chargeExpanded) {
            HorizontalDivider()
            SwitchPreference(title = "息屏充电增强", checked = s?.charge?.enabled == true,
                enabled = s?.charge?.valid == true && !busy, onCheckedChange = onChargeEnabled)
            SecondaryText("有线充电、息屏和原厂自动模式下增强，解锁后交还。", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
    Card(Modifier.fillMaxWidth()) {
        WindowDropdownPreference(items = listOf("跟随系统", "浅色", "深色"), selectedIndex = appearance,
            title = "主题模式", onSelectedIndexChange = onAppearance)
        ArrowPreference(title = "数据与诊断", onClick = onTools)
    }
}

@Composable
internal fun ValuePreference(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    ArrowPreference(title = title, enabled = enabled, endActions = {
        SecondaryText(value, Modifier.padding(start = 8.dp))
    }, onClick = onClick)
}

@Composable
private fun ToolsPage(s: FrontModel.Snapshot?, onRefresh: () -> Unit, onManager: () -> Unit, onReport: () -> Unit,
                      onCsv: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        ArrowPreference(title = "刷新数据", onClick = onRefresh)
        ArrowPreference(title = "打开 LSPosed", onClick = onManager)
        ArrowPreference(title = "模块与设备兼容报告", onClick = onReport)
    }
    Card(Modifier.fillMaxWidth()) {
        ArrowPreference(title = "导出历史 CSV", onClick = onCsv)
        ArrowPreference(title = "清空历史", onClick = onClear)
    }
    SecondaryText("App ${BuildInfo.APP_VERSION} · Hook ${s?.live?.hookVersion?.takeIf { it.isNotBlank() } ?: "未取得回报"}")
}
