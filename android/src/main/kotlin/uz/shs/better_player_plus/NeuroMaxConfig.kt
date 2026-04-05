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
 *
 * [BetterPlayer] reads these values in:
 *   - ExoPlayer.Builder (init block)          → extensionRendererMode
 *   - DataSourceUtils.getDataSourceFactory()  → userAgent, connectTimeoutMs, readTimeoutMs
 *   - Player.Listener.onPlayerError()         → onPlaybackError()
 */
@UnstableApi
object NeuroMaxConfig {

    private const val TAG = "NeuroMax/Config"

    // ── ExoPlayer renderer mode ─────────────────────────────────────────────────
    // 2 = EXTENSION_RENDERER_MODE_PREFER:
    //   ✓ Uses the Jellyfin FFmpeg extension for DTS / AC3 / TrueHD / DTS-HD
    //   ✓ Still falls through to platform (HW) decoders for H.264 / H.265
    // The app sets this via NeuroMaxPlayerPlugin before any ExoPlayer is built.
    @JvmField var extensionRendererMode: Int =
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER

    // ── HTTP data source config ────────────────────────────────────────────────
    // Chrome/Android UA — fixes "Read Error" / 403 from IPTV providers
    // (eutv4k.xyz etc.) that block the default ExoPlayer UA string.
    @JvmField var userAgent: String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    // 15 s per socket — default ExoPlayer values are 8 s which causes
    // spurious read errors on slow IPTV streams.
    @JvmField var connectTimeoutMs: Int = 15_000
    @JvmField var readTimeoutMs: Int    = 15_000

    // ── Error relay ─────────────────────────────────────────────────────────────
    // The app registers this to forward ExoPlayer errors to the Dart
    // error EventChannel (com.neuromax/player_errors).
    @JvmField var errorListener: ((errorCode: Int, message: String, cause: String) -> Unit)? = null

    /**
     * Called from [BetterPlayer]'s [Player.Listener.onPlayerError] override.
     *
     * Logs the specific [PlaybackException.errorCode] to Logcat (always) and
     * invokes [errorListener] if the app has registered one.
     *
     * Error code reference:
     *   1001 — ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
     *   1002 — ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
     *   2001 — ERROR_CODE_IO_BAD_HTTP_STATUS  (e.g., 403 from IPTV provider)
     *   3001 — ERROR_CODE_DECODER_INIT_FAILED  (missing codec / FFmpeg ext)
     *   3002 — ERROR_CODE_DECODER_QUERY_FAILED
     *   4001 — ERROR_CODE_DRM_NO_LICENSE
     */
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
}
