package app.wakeme

import android.app.Application
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
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WakeMeApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionManager
    @Inject lateinit var registrar: DeviceRegistrar
    @Inject lateinit var readiness: Readiness

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        registrar.initFirebase()
        AlarmEntryPoints.scope.launchCatching {
            session.restore()
            if (session.state.value is AuthState.SignedIn) SyncWorker.schedulePeriodic(this@WakeMeApp)
        }
        // Every foreground: refresh readiness and sync. Push is only a hint.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (session.state.value is AuthState.SignedIn) {
                    SyncWorker.runNow(this@WakeMeApp)
                    AlarmEntryPoints.scope.launchCatching { readiness.reportCapabilities() }
                }
            }
        })
    }
}
