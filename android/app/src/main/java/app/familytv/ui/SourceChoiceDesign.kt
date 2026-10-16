package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.discovery.PreparationProgress
import coil.compose.AsyncImage

private val SourceShape = RoundedCornerShape(10.dp)

/** Depth is static: the existing palette and focus contrast do not depend on motion. */
internal fun Modifier.sourcePanel() = shadow(12.dp, SourceShape)
    .background(Brush.verticalGradient(listOf(Tv.Raised, Tv.Panel)), SourceShape)
    .border(1.dp, Tv.Line, SourceShape)

@Composable
internal fun SourceChoiceCard(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick, modifier.heightIn(min = 64.dp).shadow(8.dp, SourceShape), color = Color.Transparent, shape = SourceShape) { focused ->
        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(if (focused) listOf(Tv.Accent, Tv.Accent.copy(alpha = 0.72f)) else listOf(Tv.Raised, Tv.Panel)), SourceShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White.copy(alpha = 0.85f) else Tv.Line, SourceShape)
            .padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text("›", Modifier.padding(start = 12.dp), fontSize = 28.sp, color = if (focused) Tv.Text else Tv.Muted)
        }
    }
}

@Composable
internal fun SourceChoiceLayout(label: String, title: String, year: Int?, poster: String?, overview: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.sourcePanel().padding(20.dp)) {
        val compact = maxWidth < 720.dp
        val art: @Composable (Modifier) -> Unit = { mod ->
            Box(mod.aspectRatio(2f / 3f).clip(SourceShape).background(Tv.Bg), contentAlignment = Alignment.Center) {
                if (poster != null) AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(title, Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 4)
            }
        }
        if (compact) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    art(Modifier.width(76.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        year?.let { Text(it.toString(), color = Tv.Muted) }
                        overview?.let { Text(it, fontSize = 13.sp, color = Tv.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                content()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.width(200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    art(Modifier.fillMaxWidth())
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    year?.let { Text(it.toString(), color = Tv.Muted) }
                    overview?.let { Text(it, fontSize = 14.sp, color = Tv.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
internal fun SourcePreparationCard(title: String, release: String, progress: PreparationProgress, onIptv: (() -> Unit)?, onCancel: () -> Unit) {
    val first = remember(title, release) { FocusRequester() }
    Column(Modifier.fillMaxWidth().sourcePanel().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Preparing $title", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(release, fontSize = 14.sp, color = Tv.Muted)
        PreparationProgressView(progress)
        Text(if (progress.percent != null) "Provider-reported download progress" else "This stage has no measurable percentage yet.", color = Tv.Muted, fontSize = 13.sp)
        onIptv?.let { SourceChoiceCard("Use IPTV instead", it, Modifier.fillMaxWidth().focusRequester(first)) }
        SourceChoiceCard("Cancel preparation", onCancel, if (onIptv == null) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth())
        Text("Cancel stops waiting here. Preparation may continue in Real-Debrid; you can return later.", color = Tv.Muted, fontSize = 13.sp)
    }
    LaunchedEffect(title, release) { withFrameNanos { }; first.requestFocus() }
}
