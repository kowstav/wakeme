package app.wakeme.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.wakeme.R

/** Decorative local art. Real times, state and actions are always native accessible controls. */
@Composable
fun MorningArtwork(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(32.dp)).background(Brush.verticalGradient(
        listOf(WM.palette.lime.fill.copy(alpha = 0.4f), WM.colors.surface)))) {
        Image(painterResource(R.drawable.wm_morning), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
fun ConnectionArtwork(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.wm_connection), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
}
