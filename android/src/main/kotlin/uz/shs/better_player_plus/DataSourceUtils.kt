package uz.shs.better_player_plus

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

@UnstableApi
internal object DataSourceUtils {
    private const val USER_AGENT        = "User-Agent"
    private const val USER_AGENT_PROPERTY = "http.agent"

    /**
     * Returns the User-Agent to use for HTTP requests.
     *
     * Priority:
     *   1. Explicit "User-Agent" header in [headers] map (caller-controlled)
     *   2. [NeuroMaxConfig.userAgent] (set by app at startup — Chrome/Android UA)
     *   3. System http.agent property (JVM default, rarely set on Android)
     *
     * Priority 2 is the key change vs the upstream fork.  The default ExoPlayer
     * UA string ("ExoPlayerLib/2.x.x (Linux; Android ...)") is blocked by many
     * IPTV providers, causing 403 / "Read Error".  Using the Chrome UA fixes this.
     */
    @JvmStatic
    fun getUserAgent(headers: Map<String, String>?): String {
        // Explicit header always wins
        if (headers != null && headers.containsKey(USER_AGENT)) {
            val explicit = headers[USER_AGENT]
            if (!explicit.isNullOrEmpty()) return explicit
        }
        // NeuroMax default: Chrome/Android UA configured by NeuroMaxPlayerPlugin
        return NeuroMaxConfig.userAgent
    }

    @JvmStatic
    fun getDataSourceFactory(
        userAgent: String?,
        headers: Map<String, String>?
    ): DataSource.Factory {
        // Effective UA: caller-supplied → NeuroMaxConfig.userAgent
        val effectiveUA = if (userAgent.isNullOrEmpty()) NeuroMaxConfig.userAgent else userAgent

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(effectiveUA)
            .setAllowCrossProtocolRedirects(true)
            // ── NeuroMax: increased timeouts (15 s vs ExoPlayer default 8 s) ────────
            // Some IPTV streams have high TTFB.  8 s caused spurious read errors
            // on slow providers; 15 s is empirically stable.
            .setConnectTimeoutMs(NeuroMaxConfig.connectTimeoutMs)
            .setReadTimeoutMs(NeuroMaxConfig.readTimeoutMs)

        if (headers != null) {
            val notNullHeaders = mutableMapOf<String, String>()
            headers.forEach { entry -> notNullHeaders[entry.key] = entry.value }
            dataSourceFactory.setDefaultRequestProperties(notNullHeaders)
        }
        return dataSourceFactory
    }

    @JvmStatic
    fun isHTTP(uri: Uri?): Boolean {
        if (uri == null || uri.scheme == null) return false
        val scheme = uri.scheme
        return scheme == "http" || scheme == "https"
    }
}
