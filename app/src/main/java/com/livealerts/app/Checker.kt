package com.livealerts.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object Checker {
    private val mutex = Mutex()

    /** Checks every channel and notifies once per new live stream. */
    suspend fun checkAll(ctx: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Network calls happen outside the store lock; results are merged in afterwards
            // so a channel added or removed meanwhile isn't lost or resurrected.
            val results = Store.channels(ctx).associate { c ->
                c.path to runCatching { YouTube.liveNow(c.path) }
            }
            val now = System.currentTimeMillis()
            val toNotify = mutableListOf<Pair<Channel, YouTube.Live>>()
            Store.update(ctx) { list ->
                list.map { c ->
                    val result = results[c.path] ?: return@map c
                    result.fold(
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
                            )
                        },
                        onFailure = { c.copy(error = true, lastChecked = now) },
                    )
                }
            }
            toNotify.forEach { (c, live) -> Notifier.wentLive(ctx, c, live) }
        }
    }
}
