package app.wakeme.ui.onboarding

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
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
fun playQuackPreview(context: Context) {
    val mp = MediaPlayer()
    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    mp.setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.quack_classic}"))
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
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)

@Composable
fun WelcomeScreen(onInvite: () -> Unit, onSelf: () -> Unit) {
    val context = LocalContext.current
    Page {
        Gap(WakeMeTokens.spaceXl)
        Text("🦆", style = MaterialTheme.typography.displayLarge)
        Heading(stringResource(R.string.headline))
        Body(stringResource(R.string.tagline))
        WMCard {
            Text("Hear what a quack sounds like", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
            Body("This preview plays only on your phone.")
            SecondaryButton("Play preview", onClick = { playQuackPreview(context) })
        }
        Body("You decide what each friend can do. Nobody can ring your phone unless you allow it.")
        PrimaryButton("I have an invite", onClick = onInvite)
        SecondaryButton("Set up my own wake-up", onClick = onSelf)
    }
}

@Composable
fun SignInScreen(app: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    Page {
        Heading("Save your connection")
        Body("Sign in to keep your friends and settings. We never ask for your phone number or contacts.")
        if (AppConfig.googleServerClientId.isNotBlank()) {
            PrimaryButton("Continue with Google", onClick = {
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
                        app.showError("There's no Google account on this phone. Use the email code below instead.")
                    } catch (e: GetCredentialException) {
                        app.showError("Google sign-in didn't work. Try again, or use the email code below.")
                    }
                }
            })
        }
        WMCard {
            Text("Email me a code", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (!sent) {
                SecondaryButton("Send code", onClick = { app.requestCode(email) { sent = true } }, enabled = email.contains("@"))
            } else {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("Code from the email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                PrimaryButton("Sign in", onClick = { app.verifyCode(email, code) }, enabled = code.length >= 6)
            }
        }
        if (app.devAuth) QuietButton("Use a local test account (debug build)", onClick = app::devSignIn)
    }
}

@Composable
fun ProfileScreen(onCreate: (name: String, adult: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    Page {
        Heading("What should friends call you?")
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = adult, onCheckedChange = { adult = it })
            Body("I'm 18 or older", color = WM.colors.textPrimary)
        }
        Body("Your time zone is used for quiet hours and wake windows. You can change it later.")
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
        Body("Connect so you can get each other's attention, with rules you choose.")
        WMCard {
            Text("Hear what a quack sounds like", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
            SecondaryButton("Play preview", onClick = { playQuackPreview(context) })
        }
        Body("Accepting doesn't give $who any permissions. Next you'll choose exactly what they can do, if anything.")
        if (preview.ownInvite) {
            Body("This is your own invite. Share it with a friend.")
            SecondaryButton("Back", onClick = onDecline)
        } else if (preview.alreadyConnected) {
            Body("You're already connected.")
            PrimaryButton("Continue", onClick = onDecline)
        } else {
            PrimaryButton("Accept invitation", onClick = onAccept)
            SecondaryButton("Not now", onClick = onDecline)
        }
    }
}
