package app.familytv.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.tv.material3.Text
import app.familytv.discovery.PreparationProgress

/** Percent is shown only for validated provider data; other stages remain indeterminate. */
@Composable
internal fun PreparationProgressView(progress: PreparationProgress) {
    val percent = progress.percent
    Text(progress.stage, color = Tv.Muted)
    percent?.let { Text("${it.toInt()}%", fontSize = 40.sp, fontWeight = FontWeight.Bold) }
    if (percent != null) {
        Box(Modifier.fillMaxWidth().height(8.dp).progressSemantics((percent / 100).toFloat()).background(Tv.Panel, Tv.Shape)) {
            Box(Modifier.fillMaxWidth((percent / 100).toFloat()).fillMaxHeight().background(Tv.Accent, Tv.Shape))
        }
    } else {
        val transition = rememberInfiniteTransition(label = "Preparing")
        val offset by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "Preparation stage")
        BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp).progressSemantics().background(Tv.Panel, Tv.Shape)) {
            Box(Modifier.offset { IntOffset((maxWidth.toPx() * offset * 0.7f).roundToInt(), 0) }.width(maxWidth * 0.3f).fillMaxHeight().background(Tv.Accent, Tv.Shape))
        }
    }
}
