package app.wakeme.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun freshSnapshot(location: Location?, now: Long = System.currentTimeMillis()): Location? =
    location?.takeIf { it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy >= 0 && now - it.time in 0..120_000 }

/** A bounded, foreground, single sample. It never installs a continuous listener or starts a service. */
suspend fun locationSnapshot(context: Context): Location? = withTimeoutOrNull(10_000) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return@withTimeoutOrNull null
    val manager = context.getSystemService(LocationManager::class.java)
    val providers = if (fine) listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
    val provider = providers.firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) } ?: return@withTimeoutOrNull null
    suspendCancellableCoroutine<Location?> { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(context)) { location ->
                val fresh = freshSnapshot(location)
                if (continuation.isActive) continuation.resume(fresh)
            }
        } catch (_: SecurityException) { if (continuation.isActive) continuation.resume(null) }
        catch (_: IllegalArgumentException) { if (continuation.isActive) continuation.resume(null) }
    }
}
