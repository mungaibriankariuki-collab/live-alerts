package com.livealerts.app

import androidx.core.text.HtmlCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ChannelNotFoundException : IOException("Channel not found")

/**
 * Reads YouTube's public pages; no API key needed.
 * https://www.youtube.com/@handle/live serves the current live stream's watch page when
 * the channel is live, and the channel page (or an upcoming stream) otherwise.
 */
object YouTube {
    data class Live(val videoId: String, val title: String)

    private val urlPattern = Regex("""youtube\.com/(@[^/?#\s]+|channel/UC[\w-]{22})""")
    private val channelIdPattern = Regex("""UC[\w-]{22}""")
    private val handlePattern = Regex("""@?[\w.\-]{3,30}""")
    private val canonicalPattern =
        Regex("""<link rel="canonical" href="https://www\.youtube\.com/watch\?v=([\w-]{11})"""")
    private val titlePattern = Regex("""<meta name="title" content="([^"]*)"""")
    private val ogTitlePattern = Regex("""<meta property="og:title" content="([^"]*)"""")
    private val stopMarkers = listOf(
        "\"isLiveNow\":",
        "<link rel=\"canonical\" href=\"https://www.youtube.com/channel/",
        "<link rel=\"canonical\" href=\"https://www.youtube.com/@",
    )

    /** Turns a handle, channel ID or channel link into a path, or null if it isn't one. */
    fun parse(input: String): String? {
        val s = input.trim()
        urlPattern.find(s)?.let { return it.groupValues[1] }
        if (channelIdPattern.matches(s)) return "channel/$s"
        if (handlePattern.matches(s)) return if (s.startsWith("@")) s else "@$s"
        return null
    }

    fun channelName(path: String): String {
        val html = get("https://www.youtube.com/$path")
        return ogTitlePattern.find(html)?.groupValues?.get(1)?.let(::unescape) ?: path
    }

    fun liveNow(path: String): Live? {
        // Stop downloading once the answer is known: a channel canonical link means offline,
        // and isLiveNow (after the canonical and title tags) settles a watch page.
        val html = get("https://www.youtube.com/$path/live", stopMarkers)
        if (!html.contains("\"isLiveNow\":true")) return null
        val videoId = canonicalPattern.find(html)?.groupValues?.get(1) ?: return null
        val title = titlePattern.find(html)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        return Live(videoId, title)
    }

    fun channelUrl(path: String) = "https://www.youtube.com/$path"

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun unescape(s: String) =
        HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

    /** Downloads [url], stopping early once any of [stopAt] has been read. */
    private fun get(url: String, stopAt: List<String> = emptyList()): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        )
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        // Skips the EU cookie-consent interstitial.
        conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        try {
            val code = conn.responseCode
            if (code == 404) throw ChannelNotFoundException()
            if (code !in 200..299) throw IOException("HTTP $code")
            val page = StringBuilder()
            val buf = CharArray(32 * 1024)
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val from = maxOf(0, page.length - 100)
                    page.appendRange(buf, 0, n)
                    if (stopAt.any { page.indexOf(it, from) >= 0 }) break
                }
            }
            return page.toString()
        } finally {
            conn.disconnect()
        }
    }
}
