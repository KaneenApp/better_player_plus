package uz.shs.better_player_plus

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import uz.shs.better_player_plus.DataSourceUtils.getDataSourceFactory
import uz.shs.better_player_plus.DataSourceUtils.getUserAgent
import uz.shs.better_player_plus.DataSourceUtils.isHTTP
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
object SparseCacheWorker {
    private const val TAG = "SparseCacheWorker"
    private val executor = Executors.newFixedThreadPool(2)
    private val activeJobs = ConcurrentHashMap<String, SparseJob>()

    private class SparseJob(val url: String) {
        val isCancelled = AtomicBoolean(false)
        var future: Future<*>? = null
        var activeWriter: CacheWriter? = null

        fun cancel() {
            isCancelled.set(true)
            try {
                activeWriter?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling CacheWriter: ${e.message}")
            }
            future?.cancel(true)
        }
    }

    fun startSparsePreCache(
        context: Context,
        url: String,
        cacheKey: String?,
        headers: Map<String, String>,
        maxCacheSize: Long,
        maxCacheFileSize: Long,
        anchorCount: Int = 18,
        anchorSizeBytes: Long = 1572864L, // 1.5 MB
        headSizeBytes: Long = 4194304L,    // 4.0 MB
        tailSizeBytes: Long = 2097152L     // 2.0 MB
    ) {
        val jobKey = cacheKey ?: url
        stopSparsePreCache(jobKey)

        val uri = url.toUri()
        if (!isHTTP(uri)) {
            Log.w(TAG, "Sparse pre-cache only supported for HTTP/HTTPS: $url")
            return
        }

        val job = SparseJob(url)
        activeJobs[jobKey] = job

        job.future = executor.submit {
            try {
                Log.i(TAG, "Starting sparse pre-cache for: $url (key=$cacheKey, anchors=$anchorCount)")
                val userAgent = getUserAgent(headers)
                val dataSourceFactory = getDataSourceFactory(userAgent, headers)
                val cacheDataSourceFactory = CacheDataSourceFactory(
                    context, maxCacheSize, maxCacheFileSize, dataSourceFactory
                )
                val cacheDataSource = cacheDataSourceFactory.createDataSource()

                // Step 1: Probe content length
                var fileLength: Long = C.LENGTH_UNSET.toLong()
                try {
                    val probeSpec = DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(0)
                        .setLength(1)
                        .apply { if (!cacheKey.isNullOrEmpty()) setKey(cacheKey) }
                        .build()
                    val opened = cacheDataSource.open(probeSpec)
                    val contentLenHeader = cacheDataSource.responseHeaders["Content-Length"]?.firstOrNull()?.toLongOrNull()
                    fileLength = contentLenHeader ?: (if (opened > 0) opened else C.LENGTH_UNSET.toLong())
                    cacheDataSource.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Content-Length probe error: ${e.message}")
                }

                if (job.isCancelled.get()) return@submit

                // Step 2: Build target anchor ranges (offset, length)
                val anchors = mutableListOf<Pair<Long, Long>>()

                // Always cache head (0 .. headSizeBytes)
                anchors.add(Pair(0L, headSizeBytes))

                if (fileLength > 20L * 1024 * 1024) {
                    // Cache tail for container indexes (moov/cues)
                    val tailPos = (fileLength - tailSizeBytes).coerceAtLeast(0L)
                    anchors.add(Pair(tailPos, tailSizeBytes))

                    // Evenly distribute anchors across the file
                    val count = anchorCount.coerceIn(5, 40)
                    for (i in 1..count) {
                        val fraction = i.toDouble() / (count + 1)
                        val offset = (fileLength * fraction).toLong()
                        if (offset > headSizeBytes && offset + anchorSizeBytes < tailPos) {
                            anchors.add(Pair(offset, anchorSizeBytes))
                        }
                    }
                }

                Log.d(TAG, "Computed ${anchors.size} sparse anchors for pre-caching (fileLength=$fileLength)")

                // Step 3: Sequentially download and commit anchors into SimpleCache
                for ((offset, length) in anchors) {
                    if (job.isCancelled.get()) {
                        Log.i(TAG, "Sparse pre-cache cancelled for: $url")
                        break
                    }
                    try {
                        val spec = DataSpec.Builder()
                            .setUri(uri)
                            .setPosition(offset)
                            .setLength(length)
                            .apply { if (!cacheKey.isNullOrEmpty()) setKey(cacheKey) }
                            .build()

                        val writer = CacheWriter(cacheDataSource, spec, null) { _, _, _ -> }
                        job.activeWriter = writer
                        writer.cache()
                        Log.d(TAG, "Cached sparse anchor: offset=$offset, len=$length")
                    } catch (e: Exception) {
                        if (job.isCancelled.get()) break
                        Log.w(TAG, "Failed anchor caching at $offset: ${e.message}")
                    }
                }
                Log.i(TAG, "Sparse pre-cache completed for: $url")
            } catch (e: Exception) {
                Log.e(TAG, "Sparse pre-cache execution failure: ${e.message}", e)
            } finally {
                activeJobs.remove(jobKey, job)
            }
        }
    }

    fun stopSparsePreCache(key: String) {
        val job = activeJobs.remove(key)
        job?.cancel()
    }

    fun stopAll() {
        val jobs = activeJobs.values.toList()
        activeJobs.clear()
        jobs.forEach { it.cancel() }
    }
}
