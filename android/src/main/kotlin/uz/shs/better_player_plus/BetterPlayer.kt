package uz.shs.better_player_plus

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import uz.shs.better_player_plus.DataSourceUtils.getUserAgent
import uz.shs.better_player_plus.DataSourceUtils.isHTTP
import uz.shs.better_player_plus.DataSourceUtils.getDataSourceFactory
import io.flutter.plugin.common.EventChannel
import io.flutter.view.TextureRegistry.SurfaceTextureEntry
import io.flutter.plugin.common.MethodChannel
import androidx.media3.ui.PlayerNotificationManager
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.media3.ui.PlayerNotificationManager.MediaDescriptionAdapter
import androidx.media3.ui.PlayerNotificationManager.BitmapCallback
import androidx.work.OneTimeWorkRequest
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.lifecycle.Observer
import androidx.media3.extractor.DefaultExtractorsFactory
import io.flutter.plugin.common.EventChannel.EventSink
import androidx.work.Data
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import java.lang.Exception
import java.lang.IllegalStateException
import java.util.*
import kotlin.math.max
import kotlin.math.min
import androidx.core.net.toUri

@UnstableApi
internal class BetterPlayer(
    context: Context,
    private val eventChannel: EventChannel,
    private val textureEntry: SurfaceTextureEntry,
    customDefaultLoadControl: CustomDefaultLoadControl?,
    result: MethodChannel.Result
) {
    private val exoPlayer: ExoPlayer?
    private val eventSink = QueuingEventSink()
    private val trackSelector: DefaultTrackSelector = DefaultTrackSelector(context)
    private val loadControl: LoadControl
    private var isInitialized = false
    private var surface: Surface? = null
    private var key: String? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var refreshHandler: Handler? = null
    private var refreshRunnable: Runnable? = null
    private var exoPlayerEventListener: Player.Listener? = null
    private var bitmap: Bitmap? = null
    private var mediaSession: MediaSessionCompat? = null
    private var drmSessionManager: DrmSessionManager? = null
    private val workManager: WorkManager
    private val workerObserverMap: HashMap<UUID, Observer<WorkInfo?>>
    private val customDefaultLoadControl: CustomDefaultLoadControl =
        customDefaultLoadControl ?: CustomDefaultLoadControl()
    private var lastSendBufferedPosition = 0L

    init {
        val loadBuilder = DefaultLoadControl.Builder()
        loadBuilder.setBufferDurationsMs(
            this.customDefaultLoadControl.minBufferMs,
            this.customDefaultLoadControl.maxBufferMs,
            this.customDefaultLoadControl.bufferForPlaybackMs,
            this.customDefaultLoadControl.bufferForPlaybackAfterRebufferMs
        )
        loadControl = loadBuilder.build()
        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
        workManager = WorkManager.getInstance(context)
        workerObserverMap = HashMap()
        setupVideoPlayer(eventChannel, textureEntry, result)
    }

    @OptIn(UnstableApi::class)
    fun setDataSource(
        context: Context,
        key: String?,
        dataSource: String?,
        formatHint: String?,
        result: MethodChannel.Result,
        headers: Map<String, String>?,
        useCache: Boolean,
        maxCacheSize: Long,
        maxCacheFileSize: Long,
        overriddenDuration: Long,
        licenseUrl: String?,
        drmHeaders: Map<String, String>?,
        cacheKey: String?,
        clearKey: String?
    ) {
        this.key = key
        isInitialized = false
        val uri = dataSource?.toUri()
        var dataSourceFactory: DataSource.Factory?
        val userAgent = getUserAgent(headers)
        if (!licenseUrl.isNullOrEmpty()) {
            val httpMediaDrmCallback =
                HttpMediaDrmCallback(licenseUrl, DefaultHttpDataSource.Factory())
            if (drmHeaders != null) {
                for ((drmKey, drmValue) in drmHeaders) {
                    httpMediaDrmCallback.setKeyRequestProperty(drmKey, drmValue)
                }
            }
            val drmSchemeUuid = Util.getDrmUuid("widevine")
            if (drmSchemeUuid != null) {
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        drmSchemeUuid
                    ) { uuid: UUID? ->
                        try {
                            val mediaDrm = FrameworkMediaDrm.newInstance(uuid!!)
                            mediaDrm.setPropertyString("securityLevel", "L3")
                            return@setUuidAndExoMediaDrmProvider mediaDrm
                        } catch (_: UnsupportedDrmException) {
                            return@setUuidAndExoMediaDrmProvider DummyExoMediaDrm()
                        }
                    }
                    .setMultiSession(false)
                    .build(httpMediaDrmCallback)
            }
        } else if (!clearKey.isNullOrEmpty()) {
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    C.CLEARKEY_UUID,
                    FrameworkMediaDrm.DEFAULT_PROVIDER
                ).build(LocalMediaDrmCallback(clearKey.toByteArray()))
        } else {
            drmSessionManager = null
        }
        if (isHTTP(uri)) {
            dataSourceFactory = getDataSourceFactory(userAgent, headers)
            if (useCache && maxCacheSize > 0 && maxCacheFileSize > 0) {
                dataSourceFactory = CacheDataSourceFactory(
                    context, maxCacheSize, maxCacheFileSize, dataSourceFactory
                )
            }
        } else {
            dataSourceFactory = DefaultDataSource.Factory(context)
        }
        val mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, cacheKey, context)
        if (overriddenDuration != 0L) {
            val clippingMediaSource = ClippingMediaSource.Builder(mediaSource)
                .setStartPositionMs(0)
                .setEndPositionMs(overriddenDuration * 1000)
                .build()
            exoPlayer?.setMediaSource(clippingMediaSource)
        } else {
            exoPlayer?.setMediaSource(mediaSource)
        }
        exoPlayer?.prepare()
        result.success(null)
    }

    fun setupPlayerNotification(
        context: Context, title: String, author: String?,
        imageUrl: String?, notificationChannelName: String?,
        activityName: String
    ) {
        val mediaDescriptionAdapter: MediaDescriptionAdapter = object : MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): String = title

            @SuppressLint("UnspecifiedImmutableFlag")
            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                val packageName = context.applicationContext.packageName
                val notificationIntent = Intent()
                notificationIntent.setClassName(packageName, "$packageName.$activityName")
                notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                return PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

            override fun getCurrentContentText(player: Player): String? = author

            override fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap? {
                if (imageUrl == null) return null
                if (bitmap != null) return bitmap
                val imageWorkRequest = OneTimeWorkRequest.Builder(ImageWorker::class.java)
                    .addTag(imageUrl)
                    .setInputData(Data.Builder().putString(BetterPlayerPlugin.URL_PARAMETER, imageUrl).build())
                    .build()
                workManager.enqueue(imageWorkRequest)
                val workInfoObserver = Observer { workInfo: WorkInfo? ->
                    try {
                        if (workInfo != null) {
                            val state = workInfo.state
                            if (state == WorkInfo.State.SUCCEEDED) {
                                val filePath = workInfo.outputData.getString(BetterPlayerPlugin.FILE_PATH_PARAMETER)
                                bitmap = BitmapFactory.decodeFile(filePath)
                                bitmap?.let { callback.onBitmap(it) }
                            }
                            if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.CANCELLED || state == WorkInfo.State.FAILED) {
                                val uuid = imageWorkRequest.id
                                workerObserverMap.remove(uuid)?.let {
                                    workManager.getWorkInfoByIdLiveData(uuid).removeObserver(it)
                                }
                            }
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "Image select error: $exception")
                    }
                }
                val workerUuid = imageWorkRequest.id
                workManager.getWorkInfoByIdLiveData(workerUuid).observeForever(workInfoObserver)
                workerObserverMap[workerUuid] = workInfoObserver
                return null
            }
        }

        var playerNotificationChannelName = notificationChannelName
        if (notificationChannelName == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DEFAULT_NOTIFICATION_CHANNEL, DEFAULT_NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_LOW
            )
            channel.description = DEFAULT_NOTIFICATION_CHANNEL
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            playerNotificationChannelName = DEFAULT_NOTIFICATION_CHANNEL
        }

        playerNotificationManager = PlayerNotificationManager.Builder(
            context, NOTIFICATION_ID, playerNotificationChannelName!!
        ).setMediaDescriptionAdapter(mediaDescriptionAdapter).build()

        playerNotificationManager?.apply {
            exoPlayer?.let {
                setPlayer(ForwardingPlayer(it))
                setUseNextAction(false)
                setUsePreviousAction(false)
                setUseStopAction(false)
            }
        }

        refreshHandler = Handler(Looper.getMainLooper())
        refreshRunnable = Runnable {
            val playbackState: PlaybackStateCompat = if (exoPlayer?.isPlaying == true) {
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(PlaybackStateCompat.STATE_PLAYING, position, 1.0f)
                    .build()
            } else {
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(PlaybackStateCompat.STATE_PAUSED, position, 1.0f)
                    .build()
            }
            mediaSession?.setPlaybackState(playbackState)
            refreshHandler?.postDelayed(refreshRunnable!!, 1000)
        }
        refreshHandler?.postDelayed(refreshRunnable!!, 0)

        exoPlayerEventListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                mediaSession?.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration())
                        .build()
                )
            }
        }
        exoPlayerEventListener?.let { exoPlayer?.addListener(it) }
        exoPlayer?.seekTo(0)
    }

    fun disposeRemoteNotifications() {
        exoPlayerEventListener?.let { exoPlayer?.removeListener(it) }
        refreshHandler?.removeCallbacksAndMessages(null)
        refreshHandler = null
        refreshRunnable = null
        playerNotificationManager?.setPlayer(null)
        bitmap = null
    }

    private fun buildMediaSource(
        uri: Uri?,
        mediaDataSourceFactory: DataSource.Factory,
        formatHint: String?,
        cacheKey: String?,
        context: Context
    ): MediaSource {
        val type: Int = if (formatHint == null) {
            var lastPathSegment = uri?.lastPathSegment ?: ""
            Util.inferContentTypeForExtension(lastPathSegment.split(".").last())
        } else {
            when (formatHint) {
                FORMAT_SS    -> C.CONTENT_TYPE_SS
                FORMAT_DASH  -> C.CONTENT_TYPE_DASH
                FORMAT_HLS   -> C.CONTENT_TYPE_HLS
                FORMAT_OTHER -> C.CONTENT_TYPE_OTHER
                else         -> -1
            }
        }
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (!cacheKey.isNullOrEmpty()) mediaItemBuilder.setCustomCacheKey(cacheKey)
        val mediaItem = mediaItemBuilder.build()
        val drmSessionManagerProvider: DrmSessionManagerProvider? =
            drmSessionManager?.let { DrmSessionManagerProvider { it } }

        return when (type) {
            C.CONTENT_TYPE_SS -> SsMediaSource.Factory(
                DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            ).apply { drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) } }.createMediaSource(mediaItem)

            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            ).apply { drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) } }.createMediaSource(mediaItem)

            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(mediaDataSourceFactory)
                .apply { drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) } }.createMediaSource(mediaItem)

            C.CONTENT_TYPE_OTHER -> ProgressiveMediaSource.Factory(
                mediaDataSourceFactory, DefaultExtractorsFactory()
            ).apply { drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) } }.createMediaSource(mediaItem)

            else -> throw IllegalStateException("Unsupported type: $type")
        }
    }

    private fun setupVideoPlayer(
        eventChannel: EventChannel, textureEntry: SurfaceTextureEntry, result: MethodChannel.Result
    ) {
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(o: Any?, sink: EventSink) { eventSink.setDelegate(sink) }
            override fun onCancel(o: Any?) { eventSink.setDelegate(null) }
        })
        surface = Surface(textureEntry.surfaceTexture())
        exoPlayer?.setVideoSurface(surface)
        setAudioAttributes(exoPlayer, true)
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        sendBufferingUpdate(true)
                        eventSink.success(hashMapOf("event" to "bufferingStart"))
                    }
                    Player.STATE_READY -> {
                        if (!isInitialized) { isInitialized = true; sendInitialized() }
                        eventSink.success(hashMapOf("event" to "bufferingEnd"))
                    }
                    Player.STATE_ENDED -> {
                        eventSink.success(hashMapOf("event" to "completed", "key" to key))
                    }
                    Player.STATE_IDLE -> { /* no-op */ }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                eventSink.error("VideoError", "Video player had error $error", "")
            }
        })
        val reply: MutableMap<String, Any> = HashMap()
        reply["textureId"] = textureEntry.id()
        result.success(reply)
    }

    fun sendBufferingUpdate(isFromBufferingStart: Boolean) {
        val bufferedPosition = exoPlayer?.bufferedPosition ?: 0L
        if (isFromBufferingStart || bufferedPosition != lastSendBufferedPosition) {
            val event: MutableMap<String, Any> = HashMap()
            event["event"] = "bufferingUpdate"
            event["values"] = listOf(listOf(0, bufferedPosition))
            eventSink.success(event)
            lastSendBufferedPosition = bufferedPosition
        }
    }

    @Suppress("DEPRECATION")
    private fun setAudioAttributes(exoPlayer: ExoPlayer?, mixWithOthers: Boolean) {
        exoPlayer?.setAudioAttributes(
            AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            !mixWithOthers
        )
    }

    fun play()    { exoPlayer?.playWhenReady = true }
    fun pause()   { exoPlayer?.playWhenReady = false }

    fun setLooping(value: Boolean) {
        exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun setVolume(value: Double) {
        exoPlayer?.volume = max(0.0, min(1.0, value)).toFloat()
    }

    fun setSpeed(value: Double) {
        exoPlayer?.playbackParameters = PlaybackParameters(value.toFloat())
    }

    fun setTrackParameters(width: Int, height: Int, bitrate: Int) {
        val parametersBuilder = trackSelector.buildUponParameters()
        if (width != 0 && height != 0) parametersBuilder.setMaxVideoSize(width, height)
        if (bitrate != 0) parametersBuilder.setMaxVideoBitrate(bitrate)
        if (width == 0 && height == 0 && bitrate == 0) {
            parametersBuilder.clearVideoSizeConstraints()
            parametersBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
        }
        trackSelector.setParameters(parametersBuilder)
    }

    fun seekTo(location: Int) { exoPlayer?.seekTo(location.toLong()) }

    val position: Long get() = exoPlayer?.currentPosition ?: 0L

    val absolutePosition: Long get() {
        exoPlayer?.let { player ->
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val windowStartTimeMs = timeline.getWindow(0, Timeline.Window()).windowStartTimeMs
                return windowStartTimeMs + player.currentPosition
            }
        }
        return exoPlayer?.currentPosition ?: 0L
    }

    private fun sendInitialized() {
        if (!isInitialized) return
        val event: MutableMap<String, Any?> = HashMap()
        event["event"]    = "initialized"
        event["key"]      = key
        event["duration"] = getDuration()
        exoPlayer?.videoFormat?.let { fmt ->
            var w = fmt.width; var h = fmt.height
            if (fmt.rotationDegrees == 90 || fmt.rotationDegrees == 270) { w = fmt.height; h = fmt.width }
            event["width"] = w; event["height"] = h
        }
        eventSink.success(event)
    }

    private fun getDuration(): Long = exoPlayer?.duration ?: 0L

    @SuppressLint("InlinedApi")
    fun setupMediaSession(context: Context?): MediaSessionCompat? {
        mediaSession?.release()
        context?.let {
            val pendingIntent = PendingIntent.getBroadcast(
                it, 0, Intent(Intent.ACTION_MEDIA_BUTTON), PendingIntent.FLAG_IMMUTABLE
            )
            val ms = MediaSessionCompat(it, TAG, null, pendingIntent)
            ms.setCallback(object : MediaSessionCompat.Callback() {
                override fun onSeekTo(pos: Long) { sendSeekToEvent(pos); super.onSeekTo(pos) }
            })
            ms.isActive = true
            mediaSession = ms
            return ms
        }
        return null
    }

    fun onPictureInPictureStatusChanged(inPip: Boolean) {
        eventSink.success(hashMapOf("event" to if (inPip) "pipStart" else "pipStop"))
    }

    fun disposeMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    // =========================================================================
    // Audio track selection
    // =========================================================================
    //
    // Strategy (four passes, applied in order — first match wins):
    //
    //  Pass 1 — Exact language code match (format.language vs all ISO variants
    //            of the requested lang).  Catches tracks that have a proper
    //            BCP-47 / ISO 639 language tag but no human-readable label.
    //
    //  Pass 2 — Exact label match (case-insensitive) against the requested
    //            language name, 2-letter code, or 3-letter code.
    //            Catches tracks whose label is "Arabic", "ara", or "ar".
    //
    //  Pass 3 — Group-index fallback.  Used when ALL tracks in the renderer
    //            have null labels (common in unlabelled MKV containers).
    //            The Dart side passes the raw container index, so we honour it
    //            directly.  The "strange track" heuristic that previously
    //            special-cased id=="1/15" is replaced by a broader check:
    //            if ANY track has a non-null, non-numeric, non-empty ID that
    //            looks like an IPTV/HLS composite key, we skip this pass.
    //
    //  Pass 4 — Label-contains fallback.  Checks whether any track label
    //            contains the 2-letter or 3-letter code as a substring.
    //            Last resort before giving up.
    //
    // After selection, setPreferredAudioLanguage is called so ExoPlayer’s
    // adaptive logic re-selects the same language after seeks/rebuffers
    // without needing another explicit override call from Dart.
    //
    // clearOverridesOfType(TRACK_TYPE_AUDIO) is called before addOverride so
    // switching back to a previously-active track always takes effect.

    private val iso1to3 = mapOf(
        "en" to "eng", "ar" to "ara", "fr" to "fra", "de" to "deu",
        "es" to "spa", "it" to "ita", "pt" to "por", "ja" to "jpn",
        "ko" to "kor", "zh" to "zho", "tr" to "tur", "hi" to "hin",
        "ru" to "rus", "nl" to "nld", "pl" to "pol", "sv" to "swe",
        "he" to "heb", "fa" to "fas"
    )

    private val iso3to1 = mapOf(
        "eng" to "en", "ara" to "ar", "fra" to "fr", "fre" to "fr",
        "deu" to "de", "ger" to "de", "spa" to "es", "ita" to "it",
        "por" to "pt", "jpn" to "ja", "kor" to "ko", "zho" to "zh",
        "chi" to "zh", "tur" to "tr", "hin" to "hi", "rus" to "ru",
        "nld" to "nl", "dut" to "nl", "pol" to "pl", "swe" to "sv",
        "heb" to "he", "fas" to "fa", "per" to "fa"
    )

    private val langDisplayNames = mapOf(
        "ar" to "arabic",  "en" to "english", "fr" to "french",  "de" to "german",
        "es" to "spanish", "it" to "italian", "pt" to "portuguese","ja" to "japanese",
        "ko" to "korean",  "zh" to "chinese", "tr" to "turkish", "hi" to "hindi",
        "ru" to "russian", "nl" to "dutch",   "pl" to "polish",  "sv" to "swedish",
        "he" to "hebrew",  "fa" to "persian"
    )

    /** Normalise any ISO code to 2-letter lowercase, e.g. "ara" → "ar". */
    private fun normLang(raw: String): String = iso3to1[raw.lowercase().trim()] ?: raw.lowercase().trim()

    /**
     * All ISO variants of a language code, e.g. "ar" → {"ar", "ara", "arabic"}.
     * Used for broad matching against format.language and format.label fields.
     */
    private fun isoVariants(langCode: String): Set<String> {
        val lc    = langCode.lowercase().trim()
        val norm1 = normLang(lc)                   // 2-letter
        val norm3 = iso1to3[norm1] ?: norm1        // 3-letter
        val name  = langDisplayNames[norm1] ?: ""   // display name (lowercase)
        return setOf(lc, norm1, norm3, name).filter { it.isNotEmpty() }.toSet()
    }

    /**
     * True when a track ID looks like an IPTV/HLS composite key (e.g. "1/15",
     * "audio:0", "stream_0") rather than a plain integer or null.  When any
     * track has such an ID, the "null-label group-index" fallback (pass 3)
     * is unsafe and skipped.
     */
    private fun hasCompositeTrackIds(trackGroupArray: androidx.media3.common.TrackGroup): Boolean {
        for (i in 0 until trackGroupArray.length) {
            val id = trackGroupArray.getFormat(i).id ?: continue
            if (id.contains('/') || id.contains(':') || id.contains('_')) return true
        }
        return false
    }

    fun setAudioTrack(name: String, index: Int) {
        try {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: run {
                Log.w(TAG, "setAudioTrack: no mapped track info yet")
                return
            }

            // Compute all ISO variants of the requested language once.
            val variants = isoVariants(name)           // e.g. {"ar","ara","arabic"}
            val norm1    = normLang(name)              // 2-letter for setPreferredAudioLanguage
            val norm3    = iso1to3[norm1] ?: norm1     // 3-letter

            Log.d(TAG, "setAudioTrack: name=\"$name\" index=$index variants=$variants")

            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue

                val trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex)
                if (trackGroupArray.length == 0) continue

                // Determine renderer-wide characteristics once.
                var allLabelsNull      = true
                var anyCompositeId     = false
                for (gi in 0 until trackGroupArray.length) {
                    val grp = trackGroupArray[gi]
                    if (hasCompositeTrackIds(grp)) anyCompositeId = true
                    for (ti in 0 until grp.length) {
                        if (grp.getFormat(ti).label != null) allLabelsNull = false
                    }
                }

                // ── Pass 1: exact language-code match ──────────────────────────────
                // format.language carries the BCP-47 / ISO 639 tag assigned by
                // the container parser.  This is the most reliable field.
                for (gi in 0 until trackGroupArray.length) {
                    val grp = trackGroupArray[gi]
                    for (ti in 0 until grp.length) {
                        val fmt   = grp.getFormat(ti)
                        val fLang = (fmt.language ?: "").lowercase().trim()
                        if (fLang.isEmpty()) continue
                        val fNorm = normLang(fLang)
                        if (fNorm == norm1 || fLang == norm3 || fLang in variants) {
                            Log.d(TAG, "setAudioTrack: pass-1 lang match fLang=\"$fLang\" gi=$gi ti=$ti")
                            applyAudioTrackOverride(rendererIndex, gi, ti, norm1)
                            return
                        }
                    }
                }

                // ── Pass 2: exact label match (case-insensitive) ───────────────────
                for (gi in 0 until trackGroupArray.length) {
                    val grp = trackGroupArray[gi]
                    for (ti in 0 until grp.length) {
                        val fmt    = grp.getFormat(ti)
                        val fLabel = (fmt.label ?: "").lowercase().trim()
                        if (fLabel.isEmpty()) continue
                        if (fLabel in variants) {
                            Log.d(TAG, "setAudioTrack: pass-2 label match fLabel=\"$fLabel\" gi=$gi ti=$ti")
                            applyAudioTrackOverride(rendererIndex, gi, ti, norm1)
                            return
                        }
                    }
                }

                // ── Pass 3: null-label group-index fallback ───────────────────────
                // Safe only when all tracks are unlabelled (MKV without metadata)
                // and none have composite IDs (IPTV/HLS).
                if (allLabelsNull && !anyCompositeId && index >= 0 && index < trackGroupArray.length) {
                    Log.d(TAG, "setAudioTrack: pass-3 index fallback index=$index")
                    applyAudioTrackOverride(rendererIndex, index, 0, norm1)
                    return
                }

                // ── Pass 4: label-contains substring fallback ────────────────────
                for (gi in 0 until trackGroupArray.length) {
                    val grp = trackGroupArray[gi]
                    for (ti in 0 until grp.length) {
                        val fLabel = (grp.getFormat(ti).label ?: "").lowercase()
                        if (variants.any { v -> fLabel.contains(v) }) {
                            Log.d(TAG, "setAudioTrack: pass-4 contains match fLabel=\"$fLabel\" gi=$gi ti=$ti")
                            applyAudioTrackOverride(rendererIndex, gi, ti, norm1)
                            return
                        }
                    }
                }

                Log.w(TAG, "setAudioTrack: no match found for name=\"$name\" index=$index")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setAudioTrack failed: $e")
        }
    }

    /**
     * Apply the track selector override and set the preferred audio language
     * so ExoPlayer's adaptive logic re-selects this language automatically
     * after seeks and rebuffers, without needing another Dart call.
     */
    private fun applyAudioTrackOverride(
        rendererIndex: Int,
        groupIndex: Int,
        trackIndex: Int,
        preferredLang: String  // 2-letter ISO 639-1
    ) {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        if (groupIndex < 0 || groupIndex >= trackGroups.length) {
            Log.e(TAG, "applyAudioTrackOverride: groupIndex=$groupIndex out of bounds (len=${trackGroups.length})")
            return
        }
        val group = trackGroups[groupIndex]
        val safeTrack = trackIndex.coerceIn(0, group.length - 1)

        // setPreferredAudioLanguage ensures the adaptive selector re-picks
        // the same language after a seek or mid-stream rebuffer.  We set
        // both ISO forms so ExoPlayer recognises either tag format.
        val norm3 = iso1to3[preferredLang] ?: preferredLang
        trackSelector.setParameters(
            trackSelector.parameters
                .buildUpon()
                .setRendererDisabled(rendererIndex, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(TrackSelectionOverride(group, safeTrack))
                .setPreferredAudioLanguage(norm3)   // sticky: survives seeks/rebuffers
                .build()
        )
        Log.d(TAG, "applyAudioTrackOverride: renderer=$rendererIndex group=$groupIndex track=$safeTrack lang=$preferredLang/$norm3")
    }

    // =========================================================================
    // Subtitle track selection
    // =========================================================================

    fun setSubtitleTrack(name: String, index: Int) {
        try {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_TEXT) continue
                val trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex)
                // Pass 1: label or language match
                for (gi in 0 until trackGroupArray.length) {
                    val grp = trackGroupArray[gi]
                    for (ti in 0 until grp.length) {
                        val fmt = grp.getFormat(ti)
                        if (name.equals(fmt.label, ignoreCase = true) ||
                            name.equals(fmt.language, ignoreCase = true)) {
                            setSubtitleOverride(rendererIndex, gi, ti); return
                        }
                    }
                }
                // Pass 2: index fallback
                if (index >= 0 && index < trackGroupArray.length) {
                    setSubtitleOverride(rendererIndex, index, 0); return
                }
            }
        } catch (e: Exception) { Log.e(TAG, "setSubtitleTrack failed: $e") }
    }

    fun disableSubtitleTrack() {
        try {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_TEXT) continue
                trackSelector.setParameters(
                    trackSelector.parameters.buildUpon()
                        .setRendererDisabled(rendererIndex, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                )
                return
            }
        } catch (e: Exception) { Log.e(TAG, "disableSubtitleTrack failed: $e") }
    }

    private fun setSubtitleOverride(rendererIndex: Int, groupIdx: Int, trackIdx: Int) {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        if (groupIdx < 0 || groupIdx >= trackGroups.length) return
        val group = trackGroups[groupIdx]
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setRendererDisabled(rendererIndex, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(TrackSelectionOverride(group, trackIdx.coerceIn(0, group.length - 1)))
                .build()
        )
    }

    // =========================================================================

    private fun sendSeekToEvent(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        eventSink.success(hashMapOf("event" to "seek", "position" to positionMs))
    }

    fun setMixWithOthers(mixWithOthers: Boolean) = setAudioAttributes(exoPlayer, mixWithOthers)

    fun dispose() {
        disposeMediaSession()
        disposeRemoteNotifications()
        if (isInitialized) exoPlayer?.stop()
        textureEntry.release()
        eventChannel.setStreamHandler(null)
        surface?.release()
        exoPlayer?.release()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as BetterPlayer
        if (if (exoPlayer != null) exoPlayer != that.exoPlayer else that.exoPlayer != null) return false
        return if (surface != null) surface == that.surface else that.surface == null
    }

    override fun hashCode(): Int {
        var result = exoPlayer?.hashCode() ?: 0
        result = 31 * result + (surface?.hashCode() ?: 0)
        return result
    }

    companion object {
        private const val TAG = "BetterPlayer"
        private const val FORMAT_SS    = "ss"
        private const val FORMAT_DASH  = "dash"
        private const val FORMAT_HLS   = "hls"
        private const val FORMAT_OTHER = "other"
        private const val DEFAULT_NOTIFICATION_CHANNEL = "BETTER_PLAYER_NOTIFICATION"
        private const val NOTIFICATION_ID = 20772077

        fun clearCache(context: Context?, result: MethodChannel.Result) {
            try {
                context?.let { deleteDirectory(File(it.cacheDir, "betterPlayerCache")) }
                result.success(null)
            } catch (e: Exception) {
                Log.e(TAG, e.toString())
                result.error("", "", "")
            }
        }

        private fun deleteDirectory(file: File) {
            if (file.isDirectory) file.listFiles()?.forEach { deleteDirectory(it) }
            if (!file.delete()) Log.e(TAG, "Failed to delete cache dir.")
        }

        fun preCache(
            context: Context?, dataSource: String?, preCacheSize: Long,
            maxCacheSize: Long, maxCacheFileSize: Long, headers: Map<String, String?>,
            cacheKey: String?, result: MethodChannel.Result
        ) {
            val dataBuilder = Data.Builder()
                .putString(BetterPlayerPlugin.URL_PARAMETER, dataSource)
                .putLong(BetterPlayerPlugin.PRE_CACHE_SIZE_PARAMETER, preCacheSize)
                .putLong(BetterPlayerPlugin.MAX_CACHE_SIZE_PARAMETER, maxCacheSize)
                .putLong(BetterPlayerPlugin.MAX_CACHE_FILE_SIZE_PARAMETER, maxCacheFileSize)
            if (cacheKey != null) dataBuilder.putString(BetterPlayerPlugin.CACHE_KEY_PARAMETER, cacheKey)
            headers.keys.forEach { dataBuilder.putString(BetterPlayerPlugin.HEADER_PARAMETER + it, headers[it]) }
            if (dataSource != null && context != null) {
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequest.Builder(CacheWorker::class.java)
                        .addTag(dataSource)
                        .setInputData(dataBuilder.build())
                        .build()
                )
            }
            result.success(null)
        }

        fun stopPreCache(context: Context?, url: String?, result: MethodChannel.Result) {
            if (url != null && context != null) WorkManager.getInstance(context).cancelAllWorkByTag(url)
            result.success(null)
        }
    }
}
