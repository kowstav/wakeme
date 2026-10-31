package app.wakeme.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wakeme.R

/** Vendor brand exception: official Google G and Google Sans; never tint or synthesize the logo. */
@Composable
fun GoogleAuthButton(onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val fill = if (dark) Color(0xFF131314) else Color.White
    val ink = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    val stroke = if (dark) Color(0xFF8E918F) else Color(0xFF747775)
    OutlinedButton(onClick, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = WakeMeTokens.buttonHeight),
        shape = RoundedCornerShape(WakeMeTokens.radiusMd), border = BorderStroke(1.dp, stroke),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = fill, contentColor = ink),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Image(painterResource(R.drawable.google_g), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Continue with Google", fontFamily = FontFamily(Font(R.font.google_sans, FontWeight.Medium)),
            fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
