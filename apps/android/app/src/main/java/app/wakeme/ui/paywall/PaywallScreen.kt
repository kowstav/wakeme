package app.wakeme.ui.paywall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.billing.Billing
import app.wakeme.billing.Offer
import app.wakeme.billing.PurchaseOutcome
import app.wakeme.data.net.BillingStatusDto
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DetailScreen
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Mirrors PLAN_LIMITS / PLUS_MONTHLY_CREDITS in services/api/src/modules/billing/service.ts. */
private const val PLUS_ROUTINES = 30
private const val PLUS_SOUNDS = 30
private const val FALLBACK_MONTHLY_CREDITS = 10

/** What the subscription status means, in literal words (doc 07 §7D). */
fun billingStatusText(s: BillingStatusDto): Pair<String, Tone> = when (s.status) {
    "trial" -> "Plus · free trial" + (s.expiresAt?.let { " until ${it.take(10)}" } ?: "") to Tone.Info
    "active" -> "Plus · " + (if (s.willRenew == false) "doesn't renew" else "renews") + (s.expiresAt?.let { " ${it.take(10)}" } ?: "") to Tone.Success
    "cancelled_active" -> "Plus · cancelled, access until ${s.expiresAt?.take(10) ?: "the end of the period"}" to Tone.Info
    "grace" -> "Plus · payment problem, still active while the store retries" to Tone.Warning
    "billing_issue" -> "Plus · payment problem. Update payment in Google Play." to Tone.Warning
    "expired" -> "Free · Plus has ended" to Tone.Neutral
    "refunded" -> "Free · Plus was refunded" to Tone.Neutral
    else -> "Free" to Tone.Neutral
}

data class PaywallState(
    val loading: Boolean = true,
    val offers: List<Offer> = emptyList(),
    val selected: String? = null,
    val status: BillingStatusDto? = null,
    val busy: Boolean = false,
    val note: String? = null,
)

@HiltViewModel
class PaywallViewModel @Inject constructor(private val billing: Billing) : ViewModel() {
    private val _s = MutableStateFlow(PaywallState())
    val state: StateFlow<PaywallState> = _s

    init {
        viewModelScope.launch {
            val offers = runCatching { billing.offers() }.getOrDefault(emptyList())
            val status = runCatching { billing.status() }.getOrNull()
            _s.value = PaywallState(loading = false, offers = offers, selected = offers.firstOrNull()?.packageId, status = status)
        }
    }

    fun select(id: String) {
        _s.value = _s.value.copy(selected = id)
    }

    fun buy(activity: Activity) {
        val offer = _s.value.offers.firstOrNull { it.packageId == _s.value.selected } ?: return
        perform { billing.purchase(activity, offer) }
    }

    fun restore() = perform { billing.restore() }

    private fun perform(block: suspend () -> PurchaseOutcome) = viewModelScope.launch {
        _s.value = _s.value.copy(busy = true, note = null)
        val note = when (val o = block()) {
            is PurchaseOutcome.Completed -> {
                _s.value = _s.value.copy(status = o.status)
                if (o.status.plan == "plus") "You're on Plus. Thank you!" else "No active Plus subscription was found for this account."
            }
            PurchaseOutcome.Cancelled -> null
            PurchaseOutcome.Pending -> "Your payment is pending with Google Play. Plus starts when it completes."
            is PurchaseOutcome.Failed -> o.message
        }
        _s.value = _s.value.copy(busy = false, note = note)
    }
}

/**
 * Plus paywall (doc 07 §7A). Never shown during an alarm or as a condition for
 * stopping sound, revoking a grant or removing a restriction. Every price,
 * trial and renewal term comes from the store; an offer missing any of them
 * is not shown, and "Continue free" is always available.
 */
@Composable
fun PaywallScreen(onClose: () -> Unit, vm: PaywallViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val credits = s.status?.credits?.plusMonthlyAllowance ?: FALLBACK_MONTHLY_CREDITS
    val alreadyPlus = s.status?.plan == "plus"

    // A pushed screen like the others: Back and "Continue free" both close it.
    DetailScreen(onBack = onClose) {
        QuietButton("Continue free", onClick = onClose, modifier = Modifier.align(Alignment.End))
        Text("Make your routine a team effort.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        Body("Quacks, wake requests, safety check-ins and your alarms stay free.")
        Benefit("Create with $credits AI challenge credits each month. Unused monthly credits don't carry over.")
        Benefit("Keep up to $PLUS_ROUTINES active routines.")
        Benefit("Save up to $PLUS_SOUNDS custom sounds.")

        s.status?.let {
            val (text, tone) = billingStatusText(it)
            StatusPill(text, tone)
        }

        when {
            s.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            alreadyPlus -> Body("You already have Plus on this account.")
            s.offers.isEmpty() -> StatusPill("Google Play isn't available right now. Everything free keeps working.", Tone.Warning)
            else -> {
                s.offers.forEach { o -> OfferRow(o, selected = o.packageId == s.selected, onSelect = { vm.select(o.packageId) }) }
                val chosen = s.offers.firstOrNull { it.packageId == s.selected }
                chosen?.let { o ->
                    // Billing timeline: what is charged today, and what renews.
                    Body(o.trialText ?: "Charged today: ${o.priceText}", color = WM.colors.textPrimary)
                    PrimaryButton(
                        when {
                            s.busy -> "Working…"
                            // The timeline above states today's charge; the store sheet confirms it.
                            else -> "Continue to Google Play"
                        },
                        onClick = { activity?.let { vm.buy(it) } },
                        enabled = !s.busy && activity != null,
                    )
                    Body(o.renewalTerms)
                }
            }
        }
        s.note?.let { Body(it, color = WM.colors.textPrimary) }
        QuietButton("Restore purchases", onClick = vm::restore)
        QuietButton("Manage subscription in Google Play", onClick = {
            val url = s.status?.manageUrl ?: "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        })
        Gap(WakeMeTokens.spaceXl)
    }
}

@Composable
private fun Benefit(text: String) {
    Body("•  $text", color = WM.colors.textPrimary)
}

@Composable
private fun OfferRow(o: Offer, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        shape = RoundedCornerShape(WakeMeTokens.radiusLg),
        colors = CardDefaults.cardColors(containerColor = if (selected) WM.colors.accentContainer else WM.colors.surfaceNeutral),
        border = BorderStroke(2.dp, if (selected) WM.colors.accentText else Color.Transparent),
    ) {
        androidx.compose.foundation.layout.Row(Modifier.padding(WakeMeTokens.spaceMd), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = WakeMeTokens.spaceSm)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                Text(o.trialText ?: o.priceText, style = MaterialTheme.typography.bodyLarge, color = WM.colors.textSecondary)
            }
        }
    }
}
