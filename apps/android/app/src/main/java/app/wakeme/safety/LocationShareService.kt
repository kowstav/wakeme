package app.wakeme.safety

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.wakeme.MainActivity
import app.wakeme.R
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.push.Channels
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SafetyDeps {
    fun api(): WakeMeApi
}

/** What the sharing indicator shows. Process-local: the server session is the source of truth. */
data class ActiveShare(val sessionId: String, val viewerNames: List<String>, val endsAt: Instant, val lastSentAt: Instant? = null, val lastAccuracyMeters: Int? = null)

/**
 * Shares this phone's location for one owner-started, time-boxed safety
 * session. Never stealth: it runs only as a visible foreground service with
 * a Stop action, stops itself when the session ends, and uploads only fresh
 * fixes that carry an accuracy. Uses the platform fused provider where present.
 */
class LocationShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSharing() }
    private var listener: LocationListener? = null
    private var lastUpload = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSharing(endOnServer = !intent.getBooleanExtra(EXTRA_LOCAL_ONLY, false))
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
        val viewers = intent.getStringArrayListExtra(EXTRA_VIEWERS).orEmpty()
        if (sessionId == null || endsAt <= System.currentTimeMillis()) {
            stopSelf()
            return
        }
        val share = ActiveShare(sessionId, viewers, Instant.ofEpochMilli(endsAt))
        // startForeground must be called promptly; without location permission the
        // location-typed foreground service is refused, so post it and stop.
        try {
            startForeground(NOTIFICATION_ID, notification(share), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: SecurityException) {
            Log.w("WakeMe", "location share refused: no permission")
            stopSelf()
            return
        } catch (e: IllegalStateException) {
            Log.w("WakeMe", "location share refused: not allowed to start from background")
            stopSelf()
            return
        }
        _active.value = share
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, endsAt - System.currentTimeMillis())
        requestUpdates(sessionId)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestUpdates(sessionId: String) {
        if (!hasPermission()) {
            stopSharing()
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { lm.hasProvider(it) && lm.isProviderEnabled(it) }
        if (provider == null) {
            // Location services are off. The session stays visible to viewers as "No location available".
            Log.w("WakeMe", "no enabled location provider")
            return
        }
        listener?.let { lm.removeUpdates(it) }
        val l = LocationListener { loc -> onFix(sessionId, loc) }
        listener = l
        try {
            lm.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, l, Looper.getMainLooper())
            lm.getLastKnownLocation(provider)?.let { onFix(sessionId, it) }
        } catch (e: SecurityException) {
            stopSharing()
        }
    }

    private fun onFix(sessionId: String, loc: Location) {
        // A fix without accuracy can't be labelled honestly, and an old fix is not a live position.
        if (!loc.hasAccuracy()) return
        val capturedAt = Instant.ofEpochMilli(loc.time)
        if (capturedAt.isBefore(Instant.now().minusSeconds(MAX_FIX_AGE_SECONDS))) return
        val now = System.currentTimeMillis()
        if (now - lastUpload < MIN_UPLOAD_GAP_MS) return
        lastUpload = now
        val accuracy = loc.accuracy.toInt().coerceIn(0, 100_000)
        scope.launch {
            try {
                api().postLocation(sessionId, capturedAt, loc.latitude, loc.longitude, accuracy)
                _active.value = _active.value?.copy(lastSentAt = Instant.now(), lastAccuracyMeters = accuracy)
                _active.value?.let { notificationManager().notify(NOTIFICATION_ID, notification(it)) }
            } catch (e: ApiException) {
                if (e.code in setOf("session_ended", "location_not_shared", "not_found")) handler.post { stopSharing() }
            } catch (e: Exception) {
                // Offline: the next fix retries. Viewers see the capture time of the last sample that arrived.
            }
        }
    }

    private fun stopSharing(endOnServer: Boolean = false) {
        val share = _active.value
        handler.removeCallbacks(autoStop)
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        _active.value = null
        if (endOnServer && share != null) {
            scope.launch { runCatching { api().endSafety(share.sessionId) } }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        _active.value = null
        // Let an in-flight end request finish; the scope is only cancelled once the work is short.
        handler.postDelayed({ scope.cancel() }, 5_000)
        super.onDestroy()
    }

    private fun api() = EntryPointAccessors.fromApplication(applicationContext, SafetyDeps::class.java).api()

    private fun notificationManager() = getSystemService(android.app.NotificationManager::class.java)

    private fun notification(share: ActiveShare): Notification {
        val until = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(share.endsAt)
        val who = share.viewerNames.joinToString(", ").ifBlank { getString(R.string.sharing_viewers_fallback) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "safety"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val detail = share.lastAccuracyMeters?.let { getString(R.string.sharing_last_sent, it) } ?: getString(R.string.sharing_waiting_for_fix)
        return NotificationCompat.Builder(this, Channels.SHARING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.sharing_title, who))
            .setContentText(getString(R.string.sharing_until, until) + " · " + detail)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .addAction(0, getString(R.string.sharing_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "app.wakeme.safety.START"
        const val ACTION_STOP = "app.wakeme.safety.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_ENDS_AT = "ends_at"
        const val EXTRA_VIEWERS = "viewers"
        private const val EXTRA_LOCAL_ONLY = "local_only"
        private const val NOTIFICATION_ID = 4101
        private const val UPDATE_INTERVAL_MS = 60_000L
        private const val UPDATE_DISTANCE_M = 25f
        private const val MIN_UPLOAD_GAP_MS = 45_000L
        // The server rejects samples older than 15 minutes; stay well inside that.
        private const val MAX_FIX_AGE_SECONDS = 10 * 60L

        private val _active = MutableStateFlow<ActiveShare?>(null)
        val active: StateFlow<ActiveShare?> = _active

        /** Must be called while the app is in the foreground (Android's rule for location services). */
        fun start(context: Context, sessionId: String, endsAt: Instant, viewerNames: List<String>) {
            val i = Intent(context, LocationShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_ENDS_AT, endsAt.toEpochMilli())
                .putStringArrayListExtra(EXTRA_VIEWERS, ArrayList(viewerNames))
            ContextCompat.startForegroundService(context, i)
        }

        /** Stops local sharing at once; the screen ends the server session itself. */
        fun stopLocal(context: Context) {
            if (_active.value == null) return
            context.startService(Intent(context, LocationShareService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_LOCAL_ONLY, true))
        }
    }
}
