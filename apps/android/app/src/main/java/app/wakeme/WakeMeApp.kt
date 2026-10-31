package app.wakeme

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.wakeme.alarm.AlarmEntryPoints
import app.wakeme.alarm.launchCatching
import app.wakeme.data.auth.AuthState
import app.wakeme.data.auth.SessionManager
import app.wakeme.push.Channels
import app.wakeme.readiness.Readiness
import app.wakeme.sync.DeviceRegistrar
import app.wakeme.sync.SyncWorker
import app.wakeme.widget.WidgetKeepAlive
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import dagger.Lazy
import app.wakeme.receipts.ReceiptQueue

@HiltAndroidApp
class WakeMeApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: Lazy<SessionManager>
    @Inject lateinit var registrar: Lazy<DeviceRegistrar>
    @Inject lateinit var readiness: Lazy<Readiness>
    @Inject lateinit var receipts: Lazy<ReceiptQueue>
    private var unlockedStarted = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        if (getSystemService(UserManager::class.java).isUserUnlocked) startUnlocked() else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                    unregisterReceiver(this)
                    startUnlocked()
                }
            }
            ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            // Unlock can race receiver registration.
            if (getSystemService(UserManager::class.java).isUserUnlocked) {
                unregisterReceiver(receiver)
                startUnlocked()
            }
        }
    }

    private fun startUnlocked() {
        if (unlockedStarted) return
        unlockedStarted = true
        WidgetKeepAlive.ensure(this)
        registrar.get().initFirebase()
        AlarmEntryPoints.scope.launchCatching {
            AlarmEntryPoints.scheduler(this@WakeMeApp).reconcileBootCancellations()
            receipts.get().flushAfterUnlock()
            session.get().restore()
            if (session.get().state.value is AuthState.SignedIn) SyncWorker.schedulePeriodic(this@WakeMeApp)
        }
        // Every foreground: refresh readiness and sync. Push is only a hint.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (session.get().state.value is AuthState.SignedIn) {
                    SyncWorker.runNow(this@WakeMeApp)
                    AlarmEntryPoints.scope.launchCatching { readiness.get().reportCapabilities() }
                }
            }
        })
    }
}
