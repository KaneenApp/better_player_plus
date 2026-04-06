package uz.shs.better_player_plus

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * NeuroMaxConfig — shared configuration singleton for the NeuroMax media engine.
 *
 * Lives inside better_player_plus so [BetterPlayer] can read configuration
 * values without introducing a circular app → library dependency.
 *
 * The host app writes to this object exactly once at startup
 * (before any [BetterPlayer] instance is created):
 *
 *   NeuroMaxConfig.extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
 *   NeuroMaxConfig.userAgent             = "Mozilla/5.0 ..."
 *   NeuroMaxConfig.errorListener         = { code, msg, cause -> ... }
 *   NeuroMaxConfig.tracksListener        = { tracks -> ... }
 *
 * [BetterPlayer] reads these values in:
 *   - ExoPlayer.Builder (init block)          → extensionRendererMode
 *   - DataSourceUtils.getDataSourceFactory()  → userAgent, connectTimeoutMs, readTimeoutMs
 *   - Player.Listener.onPlayerError()         → onPlaybackError()
 *   - Player.Listener STATE_READY             → onTracksReady()
 */
@UnstableApi
object NeuroMaxConfig {

    private const val TAG = "NeuroMax/Config"

    // ── ExoPlayer renderer mode ─────────────────────────────────────────────────
    @JvmField var extensionRendererMode: Int =
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER

    // ── HTTP data source config ────────────────────────────────────────────────
    @JvmField var userAgent: String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    @JvmField var connectTimeoutMs: Int = 15_000
    @JvmField var readTimeoutMs: Int    = 15_000

    // ── Error relay ─────────────────────────────────────────────────────────────
    @JvmField var errorListener: ((errorCode: Int, message: String, cause: String) -> Unit)? = null

    // ── Track relay ─────────────────────────────────────────────────────────────
    // Set by the app to forward ExoPlayer audio track info to Dart
    // (com.neuromax/player_tracks EventChannel) when STATE_READY fires.
    // Each map contains: index (Int), language (String), label (String),
    // isDefault (Boolean).
    @JvmField var tracksListener: ((List<Map<String, Any>>) -> Unit)? = null

    @JvmStatic
    fun onPlaybackError(error: PlaybackException) {
        val code  = error.errorCode
        val msg   = error.message ?: "unknown"
        val cause = error.cause?.message ?: ""
        Log.e(TAG,
            "[PlaybackException code=$code] $msg" +
            if (cause.isNotEmpty()) " — cause: $cause" else ""
        )
        errorListener?.invoke(code, msg, cause)
    }

    /**
     * Called from [BetterPlayer.sendAudioTracksToNeuroMax] when STATE_READY fires.
     * Forwards the track list to the app's [tracksListener] so the Dart UI can
     * populate the audio picker even for plain MKV/MP4 streams that return no
     * track metadata from the Xtream API.
     */
    @JvmStatic
    fun onTracksReady(tracks: List<Map<String, Any>>) {
        if (tracks.isEmpty()) return
        tracksListener?.invoke(tracks)
    }
}
