package com.autoaction.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.autoaction.data.model.Action
import com.autoaction.data.model.ActionType
import com.autoaction.data.settings.GlobalSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.random.Random

class GestureExecutor(
    private val service: AccessibilityService
) {

    suspend fun executeAction(
        action: Action,
        globalSettings: GlobalSettings,
        scriptGlobalRandomOffset: Int? = null,
        scriptGlobalRandomDelay: Long? = null
    ): Boolean {
        val randomOffset = if (globalSettings.randomizationEnabled) {
            action.overrideRandomOffset ?: scriptGlobalRandomOffset ?: globalSettings.clickOffsetRadius
        } else {
            0
        }

        val randomDelayVariance = if (globalSettings.randomizationEnabled) {
            action.overrideRandomDelay ?: scriptGlobalRandomDelay ?: globalSettings.delayVariance
        } else {
            0
        }

        val randomDurationVariance = if (globalSettings.randomizationEnabled) {
            globalSettings.clickDurationVariance
        } else {
            0
        }

        return when (action.type) {
            ActionType.CLICK -> executeClick(action, randomOffset, randomDurationVariance)
            ActionType.LONG_PRESS -> executeLongPress(action, randomOffset, randomDurationVariance)
            ActionType.SWIPE -> executeSwipe(action, randomOffset, randomDurationVariance)
            ActionType.MULTI_TOUCH -> executeMultiTouch(action, randomOffset)
            ActionType.DELAY -> {
                val finalDelay = action.duration + getRandomVariance(randomDelayVariance)
                delay(finalDelay.coerceAtLeast(0))
                true
            }
        }
    }

    /**
     * 派发手势并等待它真正回放结束。
     * 不等待的话，上一条手势（尤其是滑动）还在屏幕上执行时，下一条会被系统直接拒绝，表现为"偶尔没执行"。
     */
    private suspend fun dispatchAndAwait(gesture: GestureDescription, timeoutMillis: Long): Boolean {
        repeat(2) { attempt ->
            var rejected = false
            val completed = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val dispatched = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                        null
                    )
                    if (!dispatched) {
                        rejected = true
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false

            if (!rejected) return completed
            // 被拒绝通常是系统里还有手势没结束，稍等再重试一次
            if (attempt == 0) delay(150)
        }
        return false
    }

    private suspend fun executeClick(action: Action, randomOffset: Int, durationVariance: Long): Boolean {
        val (x, y) = applyRandomOffset(action.x, action.y, randomOffset)
        val duration = (action.duration + getRandomVariance(durationVariance)).coerceAtLeast(10)
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchAndAwait(gesture, duration + 1000)
    }

    private suspend fun executeLongPress(action: Action, randomOffset: Int, durationVariance: Long): Boolean {
        val (x, y) = applyRandomOffset(action.x, action.y, randomOffset)
        val duration = (action.duration + getRandomVariance(durationVariance)).coerceAtLeast(100)
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchAndAwait(gesture, duration + 1500)
    }

    private suspend fun executeSwipe(action: Action, randomOffset: Int, durationVariance: Long): Boolean {
        val duration = (action.duration + getRandomVariance(durationVariance)).coerceAtLeast(100)
        // 整个轨迹共用同一个随机偏移，保持形状不变
        val dx = Random.nextInt(-randomOffset, randomOffset + 1).toFloat()
        val dy = Random.nextInt(-randomOffset, randomOffset + 1).toFloat()

        val path = Path()
        val trail = action.path
        if (!trail.isNullOrEmpty()) {
            // 按录制到的轨迹点回放，可以还原画圆、折线等任意路径
            path.moveTo(trail.first().x + dx, trail.first().y + dy)
            for (i in 1 until trail.size) {
                path.lineTo(trail[i].x + dx, trail[i].y + dy)
            }
        } else {
            path.moveTo(action.startX + dx, action.startY + dy)
            path.lineTo(action.endX + dx, action.endY + dy)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchAndAwait(gesture, duration + 2000)
    }

    private fun executeMultiTouch(action: Action, randomOffset: Int): Boolean {
        return true
    }

    private fun applyRandomOffset(x: Int, y: Int, offset: Int): Pair<Float, Float> {
        val dx = Random.nextInt(-offset, offset + 1)
        val dy = Random.nextInt(-offset, offset + 1)
        return Pair((x + dx).toFloat(), (y + dy).toFloat())
    }

    private fun getRandomVariance(variance: Long): Long {
        if (variance <= 0) return 0
        return Random.nextLong(-variance, variance + 1)
    }
}
