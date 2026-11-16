package com.autoaction.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.autoaction.data.local.AppDatabase
import com.autoaction.data.model.TrailPoint
import com.autoaction.data.repository.ScriptRepository
import com.autoaction.data.settings.GlobalSettings
import com.autoaction.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutoActionService : AccessibilityService() {

    private lateinit var repository: ScriptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var scriptExecutor: ScriptExecutor
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // 执行序号，用于忽略上一次执行取消时延迟触发的结束回调
    private var executionToken = 0

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        // 当前正在运行的脚本 id，供悬浮快捷键显示旋转动画
        private val _runningScriptId = MutableStateFlow<String?>(null)
        val runningScriptId = _runningScriptId.asStateFlow()

        private var instance: AutoActionService? = null

        fun getInstance(): AutoActionService? = instance

        fun executeScript(scriptId: String) {
            instance?.executeScriptById(scriptId)
        }

        fun stopExecution() {
            instance?.scriptExecutor?.stopExecution()
            _runningScriptId.value = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        _isRunning.value = true

        val database = AppDatabase.getDatabase(this)
        repository = ScriptRepository(database.scriptDao())
        settingsRepository = SettingsRepository(this)
        scriptExecutor = ScriptExecutor(this) {
            settingsRepository.settings.first()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        instance = null
        scriptExecutor.cleanup()
    }

    private fun executeScriptById(scriptId: String) {
        scope.launch {
            val script = repository.getScriptById(scriptId)
            script?.let {
                // 每次执行分配一个序号，只有"当前这次"结束时才允许清除运行状态
                val token = ++executionToken
                _runningScriptId.value = script.id
                scriptExecutor.executeScript(script) {
                    if (token == executionToken && _runningScriptId.value == script.id) {
                        _runningScriptId.value = null
                    }
                }
            }
        }
    }

    /**
     * 把录制到的真实手势回放到屏幕，从而“穿透”录制层，让下层应用真正收到这次点击/滑动。
     */
    fun dispatchGestureToScreen(
        points: List<TrailPoint>,
        duration: Long,
        onFinished: () -> Unit
    ): Boolean {
        if (points.isEmpty()) {
            onFinished()
            return false
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(20L, 30_000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var finished = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!finished) {
                    finished = true
                    onFinished()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (!finished) {
                    finished = true
                    onFinished()
                }
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        android.util.Log.i(
            "AutoAction",
            "dispatchGesture points=${points.size} duration=$duration returned=$dispatched"
        )
        if (!dispatched) {
            finished = true
            onFinished()
        }
        return dispatched
    }

    // 音量减键停止正在运行的脚本
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (::scriptExecutor.isInitialized && scriptExecutor.isRunning()) {
                stopExecution()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
