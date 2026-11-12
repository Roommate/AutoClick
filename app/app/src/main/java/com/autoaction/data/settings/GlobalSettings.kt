package com.autoaction.data.settings

enum class RecordingBarPosition(val displayName: String) {
    TOP_LEFT("左上角"),
    TOP_CENTER("顶部居中"),
    TOP_RIGHT("右上角"),
    CENTER_LEFT("左侧居中"),
    CENTER("屏幕中央"),
    CENTER_RIGHT("右侧居中"),
    BOTTOM_LEFT("左下角"),
    BOTTOM_CENTER("底部居中"),
    BOTTOM_RIGHT("右下角"),
    CUSTOM("自定义");

    companion object {
        fun fromOrdinal(ordinal: Int): RecordingBarPosition {
            return entries.getOrElse(ordinal) { TOP_LEFT }
        }
    }
}

data class GlobalSettings(
    val randomizationEnabled: Boolean = false,
    val clickOffsetRadius: Int = 10,
    val clickDurationVariance: Long = 50,
    val delayVariance: Long = 100,
    val hapticFeedbackEnabled: Boolean = true,
    val controlBarAlpha: Float = 0.9f,
    val shortcutAlpha: Float = 0.8f,
    val recordingBarPosition: RecordingBarPosition = RecordingBarPosition.TOP_LEFT,
    val recordingBarCustomX: Int = 8,
    val recordingBarCustomY: Int = 48
)
