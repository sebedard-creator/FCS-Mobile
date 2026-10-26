package com.fcsmobile.app

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only the visible window and a few nearby cells may hold thumbnail requests. */
class ThumbnailLoader(
    private val scope: CoroutineScope,
    private val cache: LruCache<String, Bitmap>,
    private val fetch: suspend (String) -> Bitmap,
    private val ready: (String, Bitmap) -> Unit,
    private val failed: (String) -> Unit
) {
    private val jobs = mutableMapOf<String, Job>()
    private val failures = mutableSetOf<String>()
    private val slots = Semaphore(4)

    fun reset() {
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        failures.clear()
    }

    fun update(visible: List<String>, nearby: List<String>, flinging: Boolean) {
        val wanted = (visible + if (flinging) emptyList() else nearby).toSet()
        jobs.keys.filter { it !in wanted }.forEach { jobs.remove(it)?.cancel() }
        if (!scope.isActive) return
        val onScreen = visible.toSet()
        for (id in wanted) {
            if (cache.get(id) != null || id in jobs || id in failures) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (id !in onScreen) delay(150)
                    else if (flinging) delay(40)
                    val bitmap = slots.withPermit { withContext(Dispatchers.IO) { fetch(id) } }
                    ensureActive()
                    cache.put(id, bitmap)
                    ready(id, bitmap)
                } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                    failures.add(id)
                    failed(id)
                } finally {
                    if (jobs[id] === coroutineContext[Job]) jobs.remove(id)
                }
            }
            jobs[id] = job
            job.start()
        }
    }
}
