package app.wakeme.receipts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.OkDto
import app.wakeme.data.net.WakeMeApiJson
import app.wakeme.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Evidence this phone reports. Each is device-signed and idempotent on the server. */
@Serializable
sealed class Receipt {
    abstract val key: String

    @Serializable
    data class Command(val commandId: String, val kind: String, val reason: String? = null) : Receipt() {
        override val key get() = "cmd:$commandId:$kind"
    }

    @Serializable
    data class Occurrence(val planId: String, val occurrenceKey: String, val version: Int, val outcome: String) : Receipt() {
        override val key get() = "occ:$planId:$occurrenceKey:$outcome"
    }

    @Serializable
    data class Installation(val planId: String, val version: Int, val result: String, val nextFireAt: String?, val scheduledInstants: List<String>, val errorCode: String?) : Receipt() {
        override val key get() = "inst:$planId:$version:$result:${nextFireAt.orEmpty()}"
    }
}

/**
 * Durable outbox for receipts: WorkManager persists them across process death
 * and reboots and sends when connected. Reporting never blocks ringing,
 * stopping or the escape.
 */
@Singleton
class ReceiptQueue @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue(receipt: Receipt) {
        val req = OneTimeWorkRequestBuilder<ReceiptWorker>()
            .setInputData(workDataOf("receipt" to WakeMeApiJson.encodeToString(Receipt.serializer(), receipt)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("receipt:${receipt.key}", ExistingWorkPolicy.KEEP, req)
    }
}

@HiltWorker
class ReceiptWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: ApiClient,
    private val sync: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val raw = inputData.getString("receipt") ?: return Result.failure()
        val receipt = WakeMeApiJson.decodeFromString(Receipt.serializer(), raw)
        return try {
            when (receipt) {
                is Receipt.Command -> api.post(
                    "/v1/commands/${receipt.commandId}/receipts",
                    buildJsonObject { put("kind", receipt.kind); put("clientTime", java.time.Instant.now().toString()); receipt.reason?.let { put("reason", it) } },
                    JsonObject.serializer(),
                    signed = true,
                )
                is Receipt.Occurrence -> api.post(
                    "/v1/alarm-plans/${receipt.planId}/occurrences",
                    buildJsonObject { put("occurrenceKey", receipt.occurrenceKey); put("version", receipt.version); put("outcome", receipt.outcome) },
                    OkDto.serializer(),
                    signed = true,
                )
                is Receipt.Installation -> api.post(
                    "/v1/alarm-plans/${receipt.planId}/installations",
                    buildJsonObject {
                        put("version", receipt.version)
                        put("result", receipt.result)
                        receipt.nextFireAt?.let { put("nextFireAt", it) }
                        put("scheduledInstants", buildJsonArray { receipt.scheduledInstants.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        receipt.errorCode?.let { put("errorCode", it) }
                    },
                    JsonElement.serializer(),
                    signed = true,
                ).also {
                    // The server's readiness line changed: screens showing routines reload it.
                    sync.notifyChanged("alarm_plan")
                }
            }
            Result.success()
        } catch (e: ApiException) {
            // 4xx other than rate limiting means the receipt is no longer applicable (e.g. account changed): drop it.
            if (e.status in 400..499 && e.status != 429 && e.status != 401) Result.success() else Result.retry()
        } catch (e: Exception) {
            if (runAttemptCount > 20) Result.failure() else Result.retry()
        }
    }
}
