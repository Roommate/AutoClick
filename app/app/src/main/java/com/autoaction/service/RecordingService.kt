package com.autoaction.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.autoaction.data.local.AppDatabase
import com.autoaction.data.model.Action
import com.autoaction.data.model.ActionType
import com.autoaction.data.model.Script
import com.autoaction.data.model.TrailPoint
import com.autoaction.data.repository.ScriptRepository
import com.autoaction.data.settings.GlobalSettings
import com.autoaction.data.settings.SettingsRepository
import com.autoaction.ui.floating.RecordingOverlayContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class RecordingService : OverlayService() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ScriptRepository
    private lateinit var settingsRepository: SettingsRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var inputOverlayView: View? = null
    private val recordedActions = mutableListOf<Action>()
    private var lastActionEndTime = 0L
    private var recordingStartTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var warnedNoAccessibility = false
    private var warnedDispatchFailure = false
    private var replayNoticeShown = false
    // 正在把录制的手势注入屏幕；这段时间收到的触摸是注入回弹，必须忽略
    private var isForwarding = false
    private var forwardToken = 0

    companion object {
        private const val LONG_PRESS_THRESHOLD_MS = 500L
        private const val MOVEMENT_THRESHOLD_PX = 40f
        private const val MIN_DELAY_MS = 50L

        private var _instance: RecordingService? = null
        private val _actionCount = mutableStateOf(0)
        private val _isRecording = mutableStateOf(false)

        val actionCountState: androidx.compose.runtime.State<Int> = _actionCount
        val isRecordingState: androidx.compose.runtime.State<Boolean> = _isRecording

        fun getInstance(): RecordingService? = _instance
        fun getActionCount(): Int = _actionCount.value
        fun isRecording(): Boolean = _isRecording.value
    }

    private val actionCountState = _actionCount

    override fun onCreate() {
        super.onCreate()
        _instance = this
        _isRecording.value = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val database = AppDatabase.getDatabase(this)
        repository = ScriptRepository(database.scriptDao())
        settingsRepository = SettingsRepository(this)

        createRecordingOverlay()
        recordingStartTime = System.currentTimeMillis()
        lastActionEndTime = recordingStartTime
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createRecordingOverlay() {
        val inputParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        inputOverlayView = ComposeView(this).apply {
            attachLifecycle(this)
            setContent {
                val settings by settingsRepository.settings.collectAsState(initial = GlobalSettings())
                RecordingOverlayContent(
                    actionCount = actionCountState.value,
                    onStop = { stopRecording() },
                    onSave = { saveRecording() },
                    onGesture = { points, duration ->
                        handleGesture(points, duration)
                    },
                    barPosition = settings.recordingBarPosition,
                    customX = settings.recordingBarCustomX,
                    customY = settings.recordingBarCustomY
                )
            }
        }

        windowManager.addView(inputOverlayView, inputParams)

        // 录制时隐藏原始控制条
        FloatingWindowService.getInstance()?.hideControlBar()
    }

    private fun handleGesture(points: List<TrailPoint>, duration: Long) {
        if (points.isEmpty()) return
        // 注入回弹的手势不记录，避免一次点击被记成两次
        if (isForwarding) {
            android.util.Log.i("AutoAction", "forward: 收到注入回弹（说明录制层当时仍可触摸）")
            return
        }

        // 录制层里的坐标是相对悬浮窗的，回放手势必须用绝对屏幕坐标
        val screenPoints = toScreenPoints(points)
        val currentTime = System.currentTimeMillis()
        val distance = calculateDistance(screenPoints.first(), screenPoints.last())

        val action = recognizeGesture(screenPoints, duration, distance)

        // 同一点上极短时间内的重复点击视为同一只手势
        if (isDuplicateTap(action, currentTime)) return

        addDelayIfNeeded(currentTime - duration)

        recordedActions.add(action)
        lastActionEndTime = currentTime

        updateActionCount()

        // 把这次手势回放给下层应用，否则录制层会独占触摸，下层界面收不到点击/滑动
        forwardGestureToApp(screenPoints, duration)
    }

    private fun isDuplicateTap(action: Action, now: Long): Boolean {
        if (action.type != ActionType.CLICK) return false
        val last = recordedActions.lastOrNull { it.type != ActionType.DELAY } ?: return false
        if (last.type != ActionType.CLICK) return false
        val samePosition = kotlin.math.abs(last.x - action.x) <= 12 &&
            kotlin.math.abs(last.y - action.y) <= 12
        return samePosition && now - lastActionEndTime <= 120
    }

    /**
     * 悬浮窗一般从状态栏下方开始，直接用视图内坐标注入会让点击整体偏上（约一个状态栏高度），
     * 这里统一加上窗口在屏幕上的偏移量。
     */
    private fun toScreenPoints(points: List<TrailPoint>): List<TrailPoint> {
        val overlay = inputOverlayView ?: return points
        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        if (location[0] == 0 && location[1] == 0) return points
        return points.map { TrailPoint(it.x + location[0], it.y + location[1]) }
    }

    private fun forwardGestureToApp(points: List<TrailPoint>, duration: Long) {
        val service = AutoActionService.getInstance()
        val overlay = inputOverlayView

        android.util.Log.i(
            "AutoAction",
            "forward: service=${service != null} overlay=${overlay != null} points=${points.size} duration=$duration"
        )

        if (service == null || overlay == null) {
            if (!warnedNoAccessibility) {
                warnedNoAccessibility = true
                Toast.makeText(this, "未开启无障碍服务，录制时触摸无法穿透到下层界面", Toast.LENGTH_LONG).show()
            }
            return
        }

        val params = overlay.layoutParams as? WindowManager.LayoutParams ?: return

        isForwarding = true
        val token = ++forwardToken
        // 先让录制层不接收触摸，回放的手势才会落到下层应用
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Android 12+ 会拦截"穿过不受信任悬浮窗"的触摸：窗口只要可见且 alpha 较高，
        // 即使标记为不可触摸，下层应用也收不到事件。注入期间把窗口设为全透明以绕开限制
        params.alpha = 0f
        try {
            windowManager.updateViewLayout(overlay, params)
        } catch (t: Throwable) {
            android.util.Log.e("AutoAction", "updateViewLayout(不可触摸) 失败", t)
            isForwarding = false
            return
        }

        // 窗口标志要等系统完成一次 relayout 才生效。按帧等待在部分机型上不够（relayout 与输入窗口
        // 区域更新不在同一帧），这里改用固定延时，保证注入时录制层确实已不可触摸
        mainHandler.postDelayed({
                if (inputOverlayView !== overlay) {
                    isForwarding = false
                    return@postDelayed
                }

                fun restoreTouch() {
                    if (forwardToken != token || !isForwarding) return
                    android.util.Log.i("AutoAction", "forward: restore touchable")
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    params.alpha = 1f
                    if (inputOverlayView === overlay) {
                        try {
                            windowManager.updateViewLayout(overlay, params)
                        } catch (t: Throwable) {
                            android.util.Log.e("AutoAction", "updateViewLayout(恢复触摸) 失败", t)
                        }
                    }
                    isForwarding = false
                }

                fun attemptInject(retriesLeft: Int) {
                    val dispatched = service.dispatchGestureToScreen(points, duration) {
                        android.util.Log.i("AutoAction", "forward: 注入回放完成")
                        // 回放结束后再等一小会儿，确保注入的抬手事件也已送达下层窗口，再恢复录制层触摸
                        mainHandler.postDelayed({ restoreTouch() }, 60L)
                    }
                    if (dispatched) {
                        if (!replayNoticeShown) {
                            replayNoticeShown = true
                            Toast.makeText(
                                this@RecordingService,
                                "手势已回放；若下层无响应，请检查系统是否限制手势注入",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return
                    }
                    // 部分机型第一次注入会被系统拒绝，稍等后重试一次
                    if (retriesLeft > 0) {
                        mainHandler.postDelayed({ attemptInject(retriesLeft - 1) }, 120L)
                    } else {
                        restoreTouch()
                        if (!warnedDispatchFailure) {
                            warnedDispatchFailure = true
                            Toast.makeText(
                                this@RecordingService,
                                "手势注入被系统拒绝，触摸无法穿透",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                // 极端情况下回放回调可能不触发，超时兜底，避免录制层一直无法接收触摸
                mainHandler.postDelayed({ restoreTouch() }, duration + 2000L)

                attemptInject(1)
        }, 150L)
    }

    private fun addDelayIfNeeded(currentActionStartTime: Long) {
        val delay = currentActionStartTime - lastActionEndTime
        if (recordedActions.isNotEmpty() && delay > MIN_DELAY_MS) {
            recordedActions.add(
                Action(
                    type = ActionType.DELAY,
                    duration = delay,
                    desc = "自动延迟 ${delay}ms"
                )
            )
        }
    }

    private fun recognizeGesture(
        points: List<TrailPoint>,
        duration: Long,
        distance: Float
    ): Action {
        val start = points.first()
        val end = points.last()

        return when {
            distance < MOVEMENT_THRESHOLD_PX && duration >= LONG_PRESS_THRESHOLD_MS -> {
                Action(
                    type = ActionType.LONG_PRESS,
                    x = start.x.toInt(),
                    y = start.y.toInt(),
                    duration = duration,
                    desc = "长按（${duration}ms）"
                )
            }
            distance < MOVEMENT_THRESHOLD_PX -> {
                Action(
                    type = ActionType.CLICK,
                    x = start.x.toInt(),
                    y = start.y.toInt(),
                    duration = 50,
                    desc = "点击"
                )
            }
            else -> {
                val trail = downsampleTrail(points)
                Action(
                    type = ActionType.SWIPE,
                    startX = start.x.toInt(),
                    startY = start.y.toInt(),
                    endX = end.x.toInt(),
                    endY = end.y.toInt(),
                    duration = duration.coerceAtLeast(100),
                    desc = "滑动（${distance.toInt()}px，${duration}ms，${trail.size} 点轨迹）",
                    path = trail
                )
            }
        }
    }

    /** 控制单个动作的轨迹点数，避免脚本体积过大；画圆之类的曲线也能保留形状 */
    private fun downsampleTrail(points: List<TrailPoint>, maxPoints: Int = 150): List<TrailPoint> {
        if (points.size <= maxPoints) return points
        val step = points.size.toFloat() / maxPoints
        return (0 until maxPoints).map { i ->
            points[(i * step).toInt().coerceAtMost(points.lastIndex)]
        }
    }

    private fun calculateDistance(from: TrailPoint, to: TrailPoint): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun updateActionCount() {
        actionCountState.value = recordedActions.count { it.type != ActionType.DELAY }
    }

    fun stopRecording() {
        stopSelf()
    }

    fun saveRecording() {
        if (recordedActions.isEmpty()) {
            stopSelf()
            return
        }

        val timeLabel = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val script = Script(
            id = UUID.randomUUID().toString(),
            name = "录制脚本 $timeLabel",
            isEnabled = false,
            loopCount = 1,
            actions = recordedActions.toList()
        )

        scope.launch {
            repository.insertScript(script)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _instance = null
        _isRecording.value = false
        _actionCount.value = 0
        inputOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (t: Throwable) {
                android.util.Log.e("AutoAction", "removeView 失败", t)
            }
        }

        // 录制结束时恢复显示原始控制条
        FloatingWindowService.getInstance()?.showControlBar()

        scope.cancel()
    }
}
