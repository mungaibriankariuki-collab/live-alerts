package com.livealerts.app

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object Checker {
    private val mutex = Mutex()
    private val parallel = Semaphore(4)

    /** True when Wi-Fi only is on and the phone is on mobile data (or another metered network). */
    fun pausedForData(ctx: Context) =
        Store.isWifiOnly(ctx) &&
            ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    /**
     * Checks every channel and notifies once per new live stream.
     * [force] is for an explicit "Check now", which runs even when paused for data.
     */
    suspend fun checkAll(ctx: Context, force: Boolean = false) = mutex.withLock {
        if (!force && pausedForData(ctx)) return@withLock
        withContext(Dispatchers.IO) {
            // Network calls happen outside the store lock; results are merged in afterwards
            // so a channel added or removed meanwhile isn't lost or resurrected.
            val results = coroutineScope {
                Store.channels(ctx).map { c ->
                    async { parallel.withPermit { c.path to check(c) } }
                }.awaitAll().toMap()
            }
            val now = System.currentTimeMillis()
            val toNotify = mutableListOf<Pair<Channel, YouTube.Live>>()
            Store.update(ctx) { list ->
                list.map { c ->
                    val outcome = results[c.path] ?: return@map c
                    outcome.live.fold(
                        onSuccess = { live ->
                            var notified = c.lastNotifiedVideoId
                            if (live != null && live.videoId != notified) {
                                toNotify += c to live
                                notified = live.videoId
                            }
                            c.copy(
                                live = live != null,
                                liveVideoId = live?.videoId,
                                liveTitle = live?.title,
                                lastNotifiedVideoId = notified,
                                lastChecked = now,
                                error = false,
                                lastLiveAt = when {
                                    live != null -> now
                                    c.lastLiveAt == 0L -> outcome.lastStreamed ?: 0L
                                    else -> c.lastLiveAt
                                },
                            )
                        },
                        onFailure = { c.copy(error = true, lastChecked = now) },
                    )
                }
            }
            toNotify.forEach { (c, live) -> Notifier.wentLive(ctx, c, live) }
        }
    }

    private class Outcome(val live: Result<YouTube.Live?>, val lastStreamed: Long?)

    private fun check(c: Channel): Outcome {
        val live = runCatching { YouTube.liveNow(c.path) }
        // Fill in "last live" once from the Live tab for channels we haven't seen live yet.
        val lastStreamed = if (c.lastLiveAt == 0L && live.isSuccess && live.getOrNull() == null) {
            runCatching { YouTube.lastStreamed(c.path) ?: -1L }.getOrNull()
        } else null
        return Outcome(live, lastStreamed)
    }
}
