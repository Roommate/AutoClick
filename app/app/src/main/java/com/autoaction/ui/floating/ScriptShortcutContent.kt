package com.autoaction.ui.floating

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoaction.data.model.Script
import com.autoaction.ui.theme.AutoActionTheme

@Composable
fun ScriptShortcutContent(
    script: Script,
    globalAlpha: Float = 0.8f,
    isRunning: Boolean = false
) {
    val config = script.shortcutConfig

    // We removed internal click handling because the parent WindowManager handles touch for drag & click.
    // Visual feedback for "pressed" state would ideally require passing state down,
    // but for now we keep it simple to fix the drag issue.

    val transition = rememberInfiniteTransition(label = "script_running")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    AutoActionTheme {
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(config.scale)
                .alpha(config.alpha * globalAlpha)
                .then(if (isRunning) Modifier.rotate(angle) else Modifier)
                .clip(CircleShape)
                .background(
                    if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = config.textLabel.ifEmpty { script.name.firstOrNull()?.uppercase() ?: "S" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
