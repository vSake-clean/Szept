package pl.szept.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import pl.szept.App
import java.util.concurrent.TimeUnit

/** Zapasowy mechanizm: co 15 min sprawdza wiadomości, nawet gdy system zabił usługę. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!App.core.hasIdentity) return Result.success()
        App.core.start()
        MessengerService.start(applicationContext)
        App.core.keepalive()
        delay(25_000)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (e: Exception) {
                android.util.Log.w("Szept", "WorkManager niedostępny", e)
            }
        }
    }
}
