package com.livealerts.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Normal mode: WorkManager every 15 minutes (Android's minimum).
 * Fast mode: FastService checks every few minutes while showing an ongoing notification.
 */
object Scheduler {
    private const val WORK = "live-check"

    fun apply(ctx: Context) {
        val work = WorkManager.getInstance(ctx)
        val service = Intent(ctx, FastService::class.java)
        if (Store.channels(ctx).isEmpty()) {
            work.cancelUniqueWork(WORK)
            ctx.stopService(service)
        } else if (Store.isFast(ctx)) {
            work.cancelUniqueWork(WORK)
            try {
                ContextCompat.startForegroundService(ctx, service)
            } catch (e: IllegalStateException) {
                // Android refused to start it from the background; fall back to normal checks.
                enqueue(ctx, work)
            }
        } else {
            ctx.stopService(service)
            enqueue(ctx, work)
        }
    }

    private fun enqueue(ctx: Context, work: WorkManager) {
        val network = if (Store.isWifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        // UPDATE keeps the schedule but applies a changed Wi-Fi only setting.
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Checker.checkAll(applicationContext)
        return Result.success()
    }
}
