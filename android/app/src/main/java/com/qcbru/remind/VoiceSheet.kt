package com.qcbru.remind

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSheet(ready: Boolean, amplitude: () -> Int, onSend: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val animate = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ready, animate) {
        if (!ready && animate) while (true) {
            level = sqrt((amplitude() / 32768f).coerceIn(0f, 1f))
            delay(65)
        } else level = 0f
    }
    val pulse by animateFloatAsState(level, tween(100), label = "Microphone level")
    val color = MaterialTheme.colorScheme.primary
    val sendLabel = if (ready) "Send recording" else "Tap to send"
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ready) "Recording ready" else "Listening…", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color.copy(alpha = .08f), (66.dp.toPx() + pulse * 13.dp.toPx()))
                    drawCircle(color.copy(alpha = .14f), (53.dp.toPx() + pulse * 9.dp.toPx()))
                    for (direction in listOf(-1, 1)) for (bar in 0..5) {
                        val x = center.x + direction * (size.width * (.26f + .038f * bar))
                        val envelope = 1f - bar / 7f
                        val halfHeight = (2 + envelope * (4 + pulse * (12 + bar % 3 * 5))).dp.toPx()
                        drawLine(color.copy(alpha = .5f), Offset(x, center.y - halfHeight),
                            Offset(x, center.y + halfHeight), 4.dp.toPx(), StrokeCap.Round)
                    }
                }
                FilledIconButton(onClick = onSend, modifier = Modifier.size(88.dp), shape = CircleShape) {
                    Icon(Icons.Default.Mic, contentDescription = sendLabel, modifier = Modifier.size(38.dp))
                }
            }
            TextButton(onClick = onSend) { Text(sendLabel, style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
