package app.wakeme.alarm

import android.content.Context
import android.util.Log
import app.wakeme.data.local.LocalDb
import app.wakeme.readiness.Readiness
import app.wakeme.receipts.ReceiptQueue
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmDeps {
    fun scheduler(): AlarmScheduler
    fun db(): LocalDb
    fun receipts(): ReceiptQueue
    fun readiness(): Readiness
    fun sessions(): WakeSessions
}

/** Access to singletons from broadcast receivers and services that Hilt doesn't construct. */
object AlarmEntryPoints {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun deps(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, AlarmDeps::class.java)
    fun scheduler(context: Context) = deps(context).scheduler()
    fun db(context: Context) = deps(context).db()
    fun receipts(context: Context) = deps(context).receipts()
    fun readiness(context: Context) = deps(context).readiness()
    fun sessions(context: Context) = deps(context).sessions()
}

fun CoroutineScope.launchCatching(block: suspend () -> Unit) = launch {
    try {
        block()
    } catch (e: Exception) {
        Log.w("WakeMe", "background task failed: ${e.javaClass.simpleName}")
    }
}
