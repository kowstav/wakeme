package app.wakeme.billing

import android.app.Activity
import android.content.Context
import app.wakeme.config.AppConfig
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.BillingStatusDto
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.models.Period
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A plan shown on the paywall, built only from live store product data.
 * If any field needed for honest copy is missing, the offer is not shown.
 */
data class Offer(
    val packageId: String,
    val title: String,
    /** e.g. "$34.99/year" from the store's localized price. */
    val priceText: String,
    /** e.g. "7 days free, then $34.99/year". Null when there is no trial. */
    val trialText: String?,
    val renewalTerms: String,
    val isAnnual: Boolean,
    internal val pkg: Package,
)

sealed class PurchaseOutcome {
    data class Completed(val status: BillingStatusDto) : PurchaseOutcome()
    data object Cancelled : PurchaseOutcome()
    data object Pending : PurchaseOutcome()
    data class Failed(val message: String) : PurchaseOutcome()
}

/**
 * RevenueCat (Google Play Billing) client. The app never decides entitlement
 * itself: after a purchase or restore it asks the server to re-verify with the
 * store. Billing problems never affect free features, alarms or escape.
 */
@Singleton
class Billing @Inject constructor(@ApplicationContext private val context: Context, private val api: ApiClient) {
    private var configuredFor: String? = null

    /** appUserId is the Wake Me user id, so the server can map store events to the account. */
    suspend fun configure(appUserId: String) {
        if (!AppConfig.billingConfigured || configuredFor == appUserId) return
        if (!Purchases.isConfigured) {
            Purchases.configure(PurchasesConfiguration.Builder(context, AppConfig.revenueCatPublicKey).appUserID(appUserId).build())
        } else {
            runCatching { Purchases.sharedInstance.awaitLogIn(appUserId) }
        }
        configuredFor = appUserId
    }

    suspend fun offers(): List<Offer> {
        if (!AppConfig.billingConfigured || !Purchases.isConfigured) return emptyList()
        val offering = runCatching { Purchases.sharedInstance.awaitOfferings().current }.getOrNull() ?: return emptyList()
        return offering.availablePackages.mapNotNull { toOffer(it) }.sortedByDescending { it.isAnnual }
    }

    private fun periodText(p: Period?): String? = when (p?.unit) {
        Period.Unit.YEAR -> if (p.value == 1) "year" else "${p.value} years"
        Period.Unit.MONTH -> if (p.value == 1) "month" else "${p.value} months"
        Period.Unit.WEEK -> if (p.value == 1) "week" else "${p.value} weeks"
        Period.Unit.DAY -> if (p.value == 1) "day" else "${p.value} days"
        else -> null
    }

    private fun toOffer(pkg: Package): Offer? {
        val product = pkg.product
        val period = periodText(product.period) ?: return null
        val price = product.price.formatted.takeIf { it.isNotBlank() } ?: return null
        val option = product.defaultOption
        val free = option?.freePhase?.billingPeriod?.let { periodText(it) }
        val intro = option?.introPhase
        val trialText = when {
            free != null -> "${free.replaceFirstChar { it.uppercase() }} free, then $price/$period"
            intro != null -> "${intro.price.formatted} for ${periodText(intro.billingPeriod) ?: return null}, then $price/$period"
            else -> null
        }
        return Offer(
            packageId = pkg.identifier,
            title = if (product.period?.unit == Period.Unit.YEAR) "Plus · yearly" else "Plus · monthly",
            priceText = "$price/$period",
            trialText = trialText,
            renewalTerms = "Renews automatically at $price/$period until cancelled. Manage or cancel in Google Play subscriptions.",
            isAnnual = product.period?.unit == Period.Unit.YEAR,
            pkg = pkg,
        )
    }

    suspend fun purchase(activity: Activity, offer: Offer): PurchaseOutcome = try {
        Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(activity, offer.pkg).build())
        PurchaseOutcome.Completed(verify())
    } catch (e: PurchasesException) {
        when {
            e.error.code.name == "PurchaseCancelledError" -> PurchaseOutcome.Cancelled
            e.error.code.name == "PaymentPendingError" -> PurchaseOutcome.Pending
            else -> PurchaseOutcome.Failed("The purchase didn't go through. You haven't been charged by Wake Me.")
        }
    }

    suspend fun restore(): PurchaseOutcome = try {
        Purchases.sharedInstance.awaitRestore()
        PurchaseOutcome.Completed(verify())
    } catch (e: PurchasesException) {
        PurchaseOutcome.Failed("Couldn't restore purchases right now. Free features keep working.")
    }

    /** Server-side verification with the store; entitlements come from here, not from the device. */
    suspend fun verify(): BillingStatusDto = api.post("/v1/billing/sync", null, BillingStatusDto.serializer())

    suspend fun status(): BillingStatusDto = api.get("/v1/billing/status", BillingStatusDto.serializer())
}
