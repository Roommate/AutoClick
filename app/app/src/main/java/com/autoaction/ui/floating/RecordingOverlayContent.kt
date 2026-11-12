package com.autoaction.ui.floating

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoaction.data.model.TrailPoint
import com.autoaction.data.settings.RecordingBarPosition
import com.autoaction.ui.theme.AutoActionTheme

private const val MAX_TRAIL_POINTS = 400

private data class RecordedTrail(
    val points: List<Offset>,
    val number: Int,
    val isLongPress: Boolean
)

@Composable
fun RecordingOverlayContent(
    actionCount: Int,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onGesture: ((List<TrailPoint>, Long) -> Unit)? = null,
    barPosition: RecordingBarPosition = RecordingBarPosition.TOP_LEFT,
    customX: Int = 8,
    customY: Int = 48
) {
    // 已完成的轨迹（含序号），以及正在画的那一笔的采样点
    val trails = remember { mutableStateListOf<RecordedTrail>() }
    val activePoints = remember { mutableStateListOf<Offset>() }
    var nextNumber by remember { mutableStateOf(1) }

    AutoActionTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // 轨迹层：录制时可直观看到点过/滑过的路径与顺序
            Canvas(modifier = Modifier.fillMaxSize()) {
                trails.forEach { trail ->
                    val color = if (trail.isLongPress) Color(0xFFFFB300) else Color(0xFFFF5252)
                    drawTrailPath(trail.points, color.copy(alpha = 0.8f))
                    trail.points.firstOrNull()?.let {
                        drawCircle(color.copy(alpha = 0.85f), radius = 12.dp.toPx(), center = it)
                        drawNumberBadge(trail.number, it)
                    }
                    trail.points.lastOrNull()?.let {
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = it)
                    }
                }

                // 正在滑动的实时轨迹
                if (activePoints.isNotEmpty()) {
                    drawTrailPath(activePoints, Color(0xFF2196F3).copy(alpha = 0.9f))
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = activePoints.last())
                }
            }

            if (onGesture != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = down.position
                                val downTime = System.currentTimeMillis()

                                activePoints.clear()
                                activePoints.add(start)

                                var end = start
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    end = change.position
                                    // 逐帧记录轨迹点，滑动时才能画出真实曲线
                                    if (activePoints.isEmpty() ||
                                        (activePoints.last() - end).getDistance() > 2f
                                    ) {
                                        activePoints.add(end)
                                        if (activePoints.size > MAX_TRAIL_POINTS) {
                                            activePoints.removeAt(0)
                                        }
                                    }
                                    if (!change.pressed) break
                                }

                                val duration = System.currentTimeMillis() - downTime
                                trails.add(
                                    RecordedTrail(
                                        points = activePoints.toList(),
                                        number = nextNumber,
                                        isLongPress = duration >= 500L &&
                                            (end - start).getDistance() < 40f
                                    )
                                )
                                if (trails.size > 200) {
                                    trails.removeAt(0)
                                }
                                val trailPoints = activePoints.map { TrailPoint(it.x, it.y) }
                                nextNumber++
                                activePoints.clear()

                                onGesture(trailPoints, duration)
                            }
                        }
                )
            }

            // Calculate alignment and padding based on position
            val (alignment, paddingModifier) = when (barPosition) {
                RecordingBarPosition.TOP_LEFT -> Alignment.TopStart to Modifier.padding(start = 8.dp, top = 48.dp)
                RecordingBarPosition.TOP_CENTER -> Alignment.TopCenter to Modifier.padding(top = 48.dp)
                RecordingBarPosition.TOP_RIGHT -> Alignment.TopEnd to Modifier.padding(end = 8.dp, top = 48.dp)
                RecordingBarPosition.CENTER_LEFT -> Alignment.CenterStart to Modifier.padding(start = 8.dp)
                RecordingBarPosition.CENTER -> Alignment.Center to Modifier
                RecordingBarPosition.CENTER_RIGHT -> Alignment.CenterEnd to Modifier.padding(end = 8.dp)
                RecordingBarPosition.BOTTOM_LEFT -> Alignment.BottomStart to Modifier.padding(start = 8.dp, bottom = 48.dp)
                RecordingBarPosition.BOTTOM_CENTER -> Alignment.BottomCenter to Modifier.padding(bottom = 48.dp)
                RecordingBarPosition.BOTTOM_RIGHT -> Alignment.BottomEnd to Modifier.padding(end = 8.dp, bottom = 48.dp)
                RecordingBarPosition.CUSTOM -> Alignment.TopStart to Modifier.padding(start = customX.dp, top = customY.dp)
            }

            // 精简的控制条 - 类似FloatingWindowService的控制条样式
            Row(
                modifier = Modifier
                    .align(alignment)
                    .then(paddingModifier)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 录制指示器
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = "录制中",
                    tint = Color.Red,
                    modifier = Modifier.size(20.dp)
                )
                // 动作计数
                Text(
                    text = "$actionCount",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // 取消按钮
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "取消",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onStop() }
                )
                // 保存按钮
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "保存",
                    tint = if (actionCount > 0) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(enabled = actionCount > 0) { onSave() }
                )
            }
        }
    }
}

/** 按采样点绘制轨迹，滑动时是真实曲线而不是起点到终点的直线 */
private fun DrawScope.drawTrailPath(points: List<Offset>, color: Color) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color.copy(alpha = 0.85f), radius = 10.dp.toPx(), center = points.first())
        return
    }
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 6.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawNumberBadge(number: Int, anchor: Offset) {
    val badgeRadius = 13.dp.toPx()
    val center = Offset(
        x = anchor.x,
        y = (anchor.y - 28.dp.toPx()).coerceAtLeast(badgeRadius)
    )
    drawCircle(Color(0xFFD32F2F), radius = badgeRadius, center = center)

    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = 14.sp.toPx()
        isAntiAlias = true
        isFakeBoldText = true
    }
    val baseline = center.y - (paint.descent() + paint.ascent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(number.toString(), center.x, baseline, paint)
}
