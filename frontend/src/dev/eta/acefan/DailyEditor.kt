package dev.eta.acefan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun exactNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private val DecimalInput = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

private fun decimal(input: String): Double? {
    val s = input.trim().replace(',', '.')
    if (s.length !in 1..24 || !s.matches(DecimalInput)) return null
    return s.toDoubleOrNull()?.takeIf { it.isFinite() }
}

@Composable
internal fun DailyEditor(field: String, editingBase: FrontModel.Snapshot, snapshot: FrontModel.Snapshot?,
                         busy: Boolean, notice: String, onSave: (DailyFanConfig, Long) -> Unit) {
    // Freeze the editing base, not the polling snapshot. CAS will reject interleaved changes.
    val revision = rememberSaveable { editingBase.dailyVersion }
    val baseEnabled = rememberSaveable { editingBase.dailyConfig.enabled }
    val baseTemp = rememberSaveable { editingBase.dailyConfig.startC }
    val baseFloor = rememberSaveable { editingBase.dailyConfig.floor }
    val baseWeights = rememberSaveable { editingBase.dailyConfig.weights() }
    var temperature by rememberSaveable { mutableStateOf(exactNumber(baseTemp)) }
    var floor by rememberSaveable { mutableStateOf(baseFloor.toString()) }
    var weightText by rememberSaveable { mutableStateOf(ArrayList(baseWeights.map(::exactNumber))) }
    val weightValues = remember(weightText) {
        weightText.mapIndexed { i, s -> if (s == exactNumber(baseWeights[i])) baseWeights[i] else decimal(s) }
    }
    val t = if (field == "temperature") decimal(temperature) else baseTemp
    val g = if (field == "level") floor.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull() else baseFloor
    val w = if (field == "weights") weightValues else baseWeights.toList()
    val error = when {
        t == null || t !in DailyFanConfig.MIN_START_C..DailyFanConfig.MAX_START_C -> "请输入 20–50 °C 的温度。"
        g == null || g !in 1..DailyFanConfig.MAX_LEVEL -> "请输入 1–20 的整数档位。"
        w.any { it == null || it !in 0.0..100.0 } -> "每路权重须在 0–100 之间。"
        w.none { it != null && it > 0.0 } -> "至少一路权重大于 0。"
        else -> ""
    }
    val draft = if (error.isEmpty() && t != null && g != null) DailyFanConfig(baseEnabled, t, g,
        w.map { checkNotNull(it) }.toDoubleArray()) else null
    val changed = draft != null && (draft.startC != baseTemp || draft.floor != baseFloor || !draft.weights().contentEquals(baseWeights))
    val canSubmit = draft != null && !busy && snapshot?.dailyValid == true && snapshot.dailyVersion == revision
    val focus = LocalFocusManager.current

    if (!baseEnabled) SecondaryText("日用增强尚未启用；只保存参数不会开启。")
    when (field) {
        "temperature" -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(value = temperature, onValueChange = { if (it.length <= 24) temperature = it },
                        modifier = Modifier.fillMaxWidth(), label = "融合温度（°C）", singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    SecondaryText("达到此温度才可抬档；原厂给 0 档时不强制起转。")
                }
            }
        }
        "level" -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(value = floor, onValueChange = { if (it.length <= 2) floor = it },
                        modifier = Modifier.fillMaxWidth(), label = "档位（1–20）", singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    SecondaryText("受原厂档位上限与保护约束，不降低原厂档位。")
                }
            }
        }
        "weights" -> {
            val preview = remember(field, snapshot, weightText) { draft?.let { FrontModel.preview(it, snapshot) } }
            val shares = preview?.usedProportions()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in sensorNames.indices) {
                        TextField(value = weightText[i], onValueChange = { next ->
                            if (next.length <= 24) weightText = ArrayList(weightText).apply { this[i] = next }
                        }, modifier = Modifier.fillMaxWidth(), label = sensorNames[i], singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    SecondaryText("填写相对权重（0–100），不必凑满 100。权重为 0 或缺测的通道不参与。")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("融合温度预览", modifier = Modifier.weight(1f), fontSize = 15.sp)
                        val f = FrontPresentation.temperatureText(preview?.fusedTemp ?: Double.NaN)
                        Text(if (f == "—") f else "$f °C", style = NumericStyle, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                    if (preview?.hasTemperature == true && shares != null) {
                        val used = sensorNames.mapIndexedNotNull { i, name ->
                            if (preview.contributorMask and (1 shl i) == 0) null
                            else "$name ${String.format(Locale.US, "%.1f", shares[i] * 100)}%"
                        }
                        SecondaryText("本次占比：${used.joinToString(" · ")}")
                    } else if (draft != null) {
                        SecondaryText("暂无可用于计算的新鲜温度，权重仍可保存。")
                    }
                }
            }
        }
    }
    if (error.isNotEmpty()) Text(error, color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
    if (snapshot?.dailyValid != true) SecondaryText("暂时读不到已保存的设置，草稿保留，等待读取恢复。")
    else if (snapshot.dailyVersion != revision) SecondaryText("设置已由别处更新，返回后重新打开可读取新版。")
    if (notice.isNotEmpty()) SecondaryText(notice)
    TextButton(text = if (busy) "正在保存…" else if (baseEnabled) "保存" else "只保存参数",
        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(),
        enabled = canSubmit && changed,
        onClick = { if (canSubmit && draft != null) { focus.clearFocus(); onSave(draft, revision) } })
    if (!baseEnabled) TextButton(text = "保存并开启日用增强", modifier = Modifier.fillMaxWidth(),
        enabled = canSubmit, onClick = {
            if (canSubmit && draft != null) { focus.clearFocus(); onSave(draft.withEnabled(true), revision) }
        })
}
