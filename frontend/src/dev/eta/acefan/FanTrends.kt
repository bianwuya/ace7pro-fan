package dev.eta.acefan

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FanTrends(snapshot: FrontModel.Snapshot?, minutes: Int, onMinutes: (Int) -> Unit) {
    val intervals = listOf(15, 30, 60)
    val rows = remember(snapshot, minutes) { snapshot?.trend(minutes).orEmpty() }
    val end = snapshot?.wallTimeMillis ?: 0L
    val colors = MiuixTheme.colorScheme
    TabRow(tabs = intervals.map { "$it 分钟" }, selectedTabIndex = intervals.indexOf(minutes).coerceAtLeast(0),
        onTabSelected = { onMinutes(intervals[it]) })
    val missing = when {
        snapshot == null -> "正在读取历史…"
        !snapshot.historyAvailable -> "历史数据暂不可读"
        else -> "此时间范围还没有数据"
    }
    val fusion = remember(rows, colors.primary, colors.onSurfaceVariantSummary) {
        listOf(Line("融合温度", colors.primary, rows.map {
            if (it.mode == FrontPresentation.Mode.DAILY) it.dailyFusedTemp else it.chargeFusedTemp
        }), Line("日用介入温度", colors.onSurfaceVariantSummary, rows.map { it.dailyStartTemp }, dashed = true))
    }
    val levels = remember(rows, colors.primary) { listOf(Line("驱动反馈", colors.primary, rows.map { it.effectiveLevel }, step = true)) }
    TrendCard("融合温度", "°C", rows, fusion, end, minutes, missing, levels = false)
    val raw = remember(rows, colors.primary) {
        val readings = rows.map { it.temperatures() }
        val palette = listOf(colors.primary, Color(0xFF27A394), Color(0xFFA180E3), Color(0xFFC67F3D))
        sensorNames.mapIndexed { i, label -> Line(label, palette[i], readings.map { it[i] }) }
    }
    TrendCard("传感器曲线", "°C", rows, raw, end, minutes, missing, levels = false)
    TrendCard("有效档位", "档", rows, levels, end, minutes, missing, levels = true)
    val rpm = remember(rows, colors.primary) {
        listOf(Line("本档位实测保持值", colors.primary, rows.map { it.fanRpm }, step = true, heldRpm = true))
    }
    TrendCard("风扇实测转速 · 档位保持", "RPM", rows, rpm, end, minutes,
        if (rows.isEmpty()) missing else "此范围尚无有效驱动测速；换档后等待新的实测。", levels = false, rpm = true)
    SecondaryText("测速曲线保持本档位首次有效实测，不是连续实时速度；停转、故障或档位未知时断开。")
}

private data class Line(val label: String, val color: Color, val data: List<Double>,
                        val dashed: Boolean = false, val step: Boolean = false, val heldRpm: Boolean = false)

@Composable
private fun TrendCard(title: String, unit: String, rows: List<FrontPresentation.TrendPoint>, lines: List<Line>, end: Long,
                      minutes: Int, missing: String, levels: Boolean, rpm: Boolean = false) {
    val hasData = lines.any { l -> l.data.any { it.isFinite() } }
    val shownLines = lines.filter { l -> l.data.any { it.isFinite() } }
    val axisColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val gridColor = axisColor.copy(alpha = 0.20f)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                SecondaryText(unit)
            }
            if (!hasData || end <= 0L) {
                SecondaryText(missing, Modifier.padding(vertical = 22.dp))
            } else {
                val finite = shownLines.flatMap { it.data.filter(Double::isFinite) }
                val lo = if (levels || rpm) 0.0 else floor((finite.min() - 0.5) / 2.0) * 2.0
                val hi = if (levels) 20.0 else if (rpm) maxOf(1_000.0, ceil(finite.max() / 1_000.0) * 1_000.0)
                    else maxOf(lo + 6.0, ceil((finite.max() + 0.5) / 2.0) * 2.0)
                val start = end - minutes * 60_000L
                val dates = remember(start, end) {
                    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                    listOf(start, (start + end) / 2L, end).map { format.format(Date(it)) }
                }
                Canvas(Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = "$title · 最近 $minutes 分钟" }) {
                    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisColor.toArgb(); textSize = 10.sp.toPx() }
                    val left = maxOf(26.dp.toPx(), label.measureText(if (rpm) hi.toInt().toString() else "−00") + 6.dp.toPx())
                    val right = size.width - 5.dp.toPx()
                    val top = 9.dp.toPx()
                    val bottom = size.height - (label.textSize * 1.75f)
                    if (right <= left || bottom <= top) return@Canvas
                    fun y(v: Double): Float = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
                    fun x(v: Long): Float = (left + (right - left) * ((v - start).toDouble() / (end - start))).toFloat()
                    val ticks = if (levels) listOf(0.0, 5.0, 10.0, 15.0, 20.0)
                        else listOf(lo, lo + (hi - lo) / 3, lo + (hi - lo) * 2 / 3, hi).map { kotlin.math.round(it) }
                    for (v in ticks) {
                        drawLine(gridColor, Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1.dp.toPx())
                        label.textAlign = Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(v.toInt().toString(), left - 5.dp.toPx(), y(v) + label.textSize * 0.33f, label)
                    }
                    for (i in dates.indices) {
                        label.textAlign = when (i) { 0 -> Paint.Align.LEFT; 1 -> Paint.Align.CENTER; else -> Paint.Align.RIGHT }
                        drawContext.canvas.nativeCanvas.drawText(dates[i], left + (right - left) * i / 2f, size.height - 2.dp.toPx(), label)
                    }
                    for (line in shownLines) {
                        val path = Path()
                        var prev: Offset? = null
                        val singles = ArrayList<Offset>()
                        for (i in rows.indices) {
                            val v = line.data[i]
                            if (!v.isFinite() || rows[i].wallTimeMillis !in start..end) { prev = null; continue }
                            val next = Offset(x(rows[i].wallTimeMillis), y(v))
                            val p = prev
                            if (p == null || rows[i].breakBefore || (line.heldRpm && i > 0 &&
                                    rows[i].fanRpmGeneration != rows[i - 1].fanRpmGeneration)) {
                                path.moveTo(next.x, next.y)
                                if (!line.dashed) singles.add(next)
                            } else {
                                if (line.step || line.dashed) path.lineTo(next.x, p.y)
                                path.lineTo(next.x, next.y)
                            }
                            prev = next
                        }
                        drawPath(path, line.color, style = Stroke(width = if (line.dashed) 1.25.dp.toPx() else 2.dp.toPx(),
                            cap = StrokeCap.Round, pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
                        for (dot in singles) drawCircle(line.color, 1.8.dp.toPx(), dot)
                    }
                }
                shownLines.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { line ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Canvas(Modifier.height(9.dp).fillMaxWidth(.11f)) {
                                    drawLine(line.color, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx(),
                                        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null)
                                }
                                SecondaryText(line.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
