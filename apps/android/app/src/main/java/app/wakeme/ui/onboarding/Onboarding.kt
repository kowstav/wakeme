package app.wakeme.ui.onboarding

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wakeme.designsystem.rememberEntrance
import app.wakeme.push.Channels
import app.wakeme.ui.AnimalSounds
import app.wakeme.ui.FriendHuddle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.GetCredentialRequest
import app.wakeme.R
import app.wakeme.config.AppConfig
import app.wakeme.data.net.InvitePreviewDto
import app.wakeme.designsystem.GoogleAuthButton
import app.wakeme.designsystem.MorningArtwork
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DisplayText
import app.wakeme.designsystem.IconChip
import app.wakeme.designsystem.PillButton
import app.wakeme.designsystem.WMIcon
import androidx.compose.ui.text.style.TextAlign
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.Supporting
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.ui.AppViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/** Preview plays only on this phone, at media volume. It is never a remote command. */
fun playSoundPreview(context: Context, soundId: String = AnimalSounds.DEFAULT_ID) {
    val res = Channels.quackSounds[soundId] ?: R.raw.quack_classic
    val mp = MediaPlayer()
    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    mp.setDataSource(context, Uri.parse("android.resource://${context.packageName}/$res"))
    mp.setOnCompletionListener { it.release() }
    mp.prepare()
    mp.start()
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
    ) { content() }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })

/** Welcome prioritizes account sign-in and an account-free local alarm. */
@Composable
fun WelcomeScreen(onInvite: () -> Unit, onSelf: () -> Unit, signInAction: (@Composable () -> Unit)? = null) {
    // The story scrolls; the two ways in stay at the bottom, where a thumb reaches them.
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val surface = WM.colors.surface
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Text scrolling under the pinned actions fades out instead of being cut mid-line.
                .drawWithContent {
                    drawContent()
                    val fade = WakeMeTokens.spaceLg.toPx()
                    drawRect(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface), startY = size.height - fade, endY = size.height), topLeft = Offset(0f, size.height - fade), size = Size(size.width, fade))
                },
        ) {
        val artworkHeight = (maxHeight - 220.dp).coerceIn(180.dp, 360.dp)
        // Centred in the space above the actions; at large text sizes it simply scrolls.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = WakeMeTokens.spaceLg, vertical = WakeMeTokens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceLg, Alignment.CenterVertically),
        ) {
            Text("WakeMe", style = MaterialTheme.typography.titleLarge, color = WM.colors.brandInk, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Entrance(0) { MorningArtwork(Modifier.fillMaxWidth().height(artworkHeight)) }
            Entrance(1) {
                DisplayText("Better mornings.\nTogether.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        }
        Entrance(4) {
            Column(Modifier.padding(horizontal = WakeMeTokens.spaceLg, vertical = WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.layoutControlGap)) {
                if (signInAction != null) signInAction() else PrimaryButton("Sign in", onClick = onInvite, icon = WMIcon.PersonPlus)
                SecondaryButton("Set up my own wake-up", onClick = onSelf, icon = WMIcon.Alarm)
                if (signInAction != null) QuietButton("More sign-in options", onClick = onInvite)
            }
        }
    }
}

@Composable
private fun Entrance(index: Int, content: @Composable () -> Unit) {
    val rise = with(LocalDensity.current) { 12.dp.toPx() }
    val p = rememberEntrance(index)
    Box(Modifier.graphicsLayer { val v = p.value; alpha = v; translationY = (1f - v) * rise }) { content() }
}

@Composable
fun GoogleSignInButton(app: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    GoogleAuthButton(onClick = {
        scope.launch {
            val rawNonce = UUID.randomUUID().toString()
            val hashed = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }
            val option = GetGoogleIdOption.Builder().setServerClientId(AppConfig.googleServerClientId).setFilterByAuthorizedAccounts(false).setNonce(hashed).build()
            try {
                val result = CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
                val cred = result.credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    app.googleSignIn(GoogleIdTokenCredential.createFrom(cred.data).idToken, rawNonce)
                } else {
                    app.showError("That sign-in option isn't supported. Use the email code instead.")
                }
            } catch (e: GetCredentialCancellationException) {
                // The person closed the sheet; nothing to say.
            } catch (e: NoCredentialException) {
                app.showError("No Google account is available. You can sign in with email.")
            } catch (e: GetCredentialException) {
                app.showError("Google sign-in didn't work. Try again or use email.")
            }
        }
    })
}

@Composable
fun SignInScreen(app: AppViewModel) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    Page {
        Heading("Save your connection")
        if (AppConfig.googleServerClientId.isNotBlank()) GoogleSignInButton(app)
        if (!AppConfig.supabaseConfigured) {
            Body("Email sign-in isn't configured for this build.")
        } else WMCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                IconChip(WMIcon.Lock, WM.palette.sky)
                Text("Email me a code", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (!sent) {
                SecondaryButton("Send code", onClick = { app.requestCode(email) { sent = true } }, enabled = email.contains("@"), icon = WMIcon.Send)
            } else {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("Code from the email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                PrimaryButton("Sign in", onClick = { app.verifyCode(email, code) }, enabled = code.length >= 6)
            }
        }
        if (app.devAuth) QuietButton("Use a local test account (debug build)", onClick = app::devSignIn)
        QuietButton("Back", onClick = { app.backToGuest() })
    }
}

@Composable
fun ProfileScreen(onCreate: (name: String, adult: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    Page {
        Heading("What should friends call you?")
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // The whole row is one 48 dp target and the box sits on the content edge (Material's
        // checkbox alone added a 14 dp inset and a 20 dp target).
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = WakeMeTokens.minTouchTarget)
                .toggleable(value = adult, role = Role.Checkbox, onValueChange = { adult = it }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm),
        ) {
            Checkbox(checked = adult, onCheckedChange = null)
            Body("I'm 18 or older", color = WM.colors.textPrimary)
        }
        Supporting("Time zone: ${java.util.TimeZone.getDefault().id}")
        PrimaryButton("Continue", onClick = { onCreate(name, adult) }, enabled = name.isNotBlank() && adult)
    }
}

/** Route A: identify the inviter clearly; accepting creates a friendship only, never permissions. */
@Composable
fun InviteScreen(preview: InvitePreviewDto?, onAccept: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    Page {
        if (preview == null) {
            Heading("This invite isn't valid any more")
            Body("Ask your friend for a new link, or set things up on your own.")
            PrimaryButton("Continue", onClick = onDecline)
            return@Page
        }
        val who = preview.inviter.displayName
        Heading(if (preview.kind == "circle") "$who invited you to ${preview.circle?.name ?: "a circle"}" else "$who wants to quack you.")
        if (preview.kind != "circle") Body("Next, you choose what they can do.")
        if (preview.ownInvite) {
            Body("This is your own invite. Share it with a friend.")
            SecondaryButton("Back", onClick = onDecline)
        } else if (preview.alreadyConnected) {
            Body("You're already connected.")
            PrimaryButton("Continue", onClick = onDecline)
        } else {
            PrimaryButton("Accept invitation", onClick = onAccept, icon = WMIcon.Check)
            SecondaryButton("Not now", onClick = onDecline)
        }
    }
}
