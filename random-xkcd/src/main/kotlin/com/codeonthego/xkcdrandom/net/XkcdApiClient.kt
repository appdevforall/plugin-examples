package com.codeonthego.xkcdrandom.net

import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * The whole xkcd network surface, in one file so the example reads
 * top-to-bottom. Two endpoints:
 *   - GET https://xkcd.com/info.0.json           → latest comic
 *   - GET https://xkcd.com/<num>/info.0.json     → specific comic
 *
 * No auth, no rate-limit headers, no pagination. We keep this client
 * dependency-light: OkHttp + the org.json reader that ships with Android.
 *
 * **Threading:** every public method here makes a blocking HTTP call via
 * `OkHttpClient.execute()`. Always call these from `Dispatchers.IO` (or a
 * thread you don't mind blocking). `fetchRandom` is `suspend` because it
 * loops + needs cooperative cancellation; the others are plain blocking
 * functions and read top-to-bottom.
 */
class XkcdApiClient(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * Fetch a random comic. Picks a number in `[1, latestNum]` (upper
     * bound exclusive in `Random.nextInt`) and keeps picking until one
     * returns a real comic. Returns `null` only if the initial "latest
     * comic" probe fails — i.e. the network is down.
     *
     * Why the loop is unbounded: xkcd #404 returns HTTP 404 on its JSON
     * endpoint (the "page not found" joke comic), so a single retry can
     * still land on the same dud. Looping until success is simpler than
     * a retry budget + fallback, and on a healthy network it converges
     * in 1-2 picks. The `ensureActive()` at the top of each iteration
     * cooperates with `lifecycleScope` cancellation — if the host tears
     * the Fragment down mid-fetch, the loop exits with a
     * `CancellationException` rather than running to completion.
     */
    suspend fun fetchRandom(): XkcdComic? {
        val latest = fetchLatest() ?: return null
        while (true) {
            coroutineContext.ensureActive()
            val pick = Random.nextInt(1, latest.num + 1)  // upper bound exclusive
            if (pick == 404) continue
            fetchByNumber(pick)?.let { return it }
            // null = transient blip; just pick again on the next iteration
        }
    }

    /** Blocking HTTP GET. Call from `Dispatchers.IO`. */
    fun fetchLatest(): XkcdComic? = getJson("https://xkcd.com/info.0.json")?.let(::parseComic)

    /** Blocking HTTP GET. Call from `Dispatchers.IO`. */
    fun fetchByNumber(num: Int): XkcdComic? =
        getJson("https://xkcd.com/$num/info.0.json")?.let(::parseComic)

    /**
     * Stream the comic's PNG. **Blocking** — call from `Dispatchers.IO`.
     * Caller must close the returned stream.
     *
     * Returns null if the request failed, the response body was empty,
     * or any IO error occurred (timeout, DNS, TLS). Returning null —
     * rather than throwing — keeps the caller's empty-state branch
     * reachable; without it the spinner can hang on a flaky connection.
     */
    fun openImageStream(imageUrl: String): InputStream? = try {
        val response = client.newCall(Request.Builder().url(imageUrl).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            null
        } else {
            // body() can be null on 204 etc. — for xkcd it shouldn't, but
            // handle the case explicitly.
            response.body?.byteStream()
        }
    } catch (_: IOException) {
        null
    }

    private fun getJson(url: String): JSONObject? = try {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) return@use null
            // Reject pathologically large responses before we slurp them
            // into memory. xkcd's comic JSON is < 1 KB in practice; the
            // 64 KB cap is generous but bounded. Some servers omit
            // Content-Length, in which case we fall through and trust
            // OkHttp's read; the upstream byte cap in `fetchAndDecode`
            // handles the bitmap path separately.
            val contentLength = it.body?.contentLength() ?: -1L
            if (contentLength in (MAX_JSON_BYTES + 1)..Long.MAX_VALUE) return@use null
            it.body?.string()?.let(::JSONObject)
        }
    } catch (_: IOException) {
        // Network / DNS / TLS failure → behave the same as "comic
        // unavailable". Caller falls back to the offline empty state.
        null
    }

    private fun parseComic(obj: JSONObject): XkcdComic? = try {
        val img = obj.getString("img")
        // Defensive parsing: reject any image URL that isn't hosted on
        // xkcd's own image CDN. The Tier-3 tooltip claims "fetches use
        // HTTPS only" — enforcing scheme + host here protects against a
        // future MITM that swaps in a different host, and against a
        // malicious `img` field that points the bitmap decoder at an
        // attacker-controlled server. Returning null routes through the
        // same "comic unavailable" fallback as a network failure.
        if (!img.startsWith(XKCD_IMG_HOST_PREFIX)) return null
        XkcdComic(
            num = obj.getInt("num"),
            title = obj.optString("safe_title", obj.optString("title", "")),
            alt = obj.optString("alt", ""),
            imageUrl = img,
        )
    } catch (_: Exception) {
        // Malformed payload → null, treated as a fetch failure.
        null
    }

    companion object {
        /** Only accept image URLs served by xkcd's own image CDN. */
        private const val XKCD_IMG_HOST_PREFIX = "https://imgs.xkcd.com/"

        /** Bound the JSON read so a pathological response can't OOM the parser. */
        private const val MAX_JSON_BYTES = 64L * 1024L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
