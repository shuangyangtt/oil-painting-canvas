package app.youtubu.tv

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF1B1640)
    val bgLight = Color(0xFF2E2670)
    val card = Color(0xFF332B7A)
    val yellow = Color(0xFFFFC93C)
    val pink = Color(0xFFFF6B9A)
    val text = Color.White
    val dim = Color(0xB3FFFFFF)
    val faint = Color(0x66FFFFFF)
}

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 18.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align, lineHeight = size * 1.25f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

fun formatTime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
