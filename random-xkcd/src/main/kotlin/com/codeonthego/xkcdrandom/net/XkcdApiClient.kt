package com.codeonthego.xkcdrandom.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The whole xkcd network surface, in one file so the example reads
 * top-to-bottom. Two endpoints:
 *   - GET https://xkcd.com/info.0.json           → latest comic
 *   - GET https://xkcd.com/<num>/info.0.json     → specific comic
 *
 * No auth, no rate-limit headers, no pagination. We keep this client
 * dependency-light: OkHttp + the org.json reader that ships with Android.
 */
class XkcdApiClient(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * Fetch a random comic. Picks a number in [1, latestNum] and keeps
     * picking until one returns a real comic. The only way this returns
     * null is if the initial "latest comic" probe fails — i.e. the
     * network is down. Once we know the network works, we loop until
     * a non-404 number comes back.
     *
     * Why the loop is unbounded: xkcd #404 returns HTTP 404 on its JSON
     * endpoint (the "page not found" joke comic), so a single retry can
     * still land on the same dud. Looping until success is simpler than
     * a retry budget + fallback — and on a healthy network it converges
     * in 1-2 picks.
     */
    fun fetchRandom(): XkcdComic? {
        val latest = fetchLatest() ?: return null
        while (true) {
            val pick = Random.nextInt(1, latest.num + 1)
            if (pick == 404) continue
            fetchByNumber(pick)?.let { return it }
            // null = transient blip; just pick again
        }
    }

    fun fetchLatest(): XkcdComic? = getJson("https://xkcd.com/info.0.json")?.let(::parseComic)

    fun fetchByNumber(num: Int): XkcdComic? =
        getJson("https://xkcd.com/$num/info.0.json")?.let(::parseComic)

    /**
     * Stream the comic's PNG. Caller must close the returned stream.
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
            if (!it.isSuccessful) null
            else it.body?.string()?.let(::JSONObject)
        }
    } catch (_: IOException) {
        // Network / DNS / TLS failure → behave the same as "comic
        // unavailable". Caller falls back to the offline empty state.
        null
    }

    private fun parseComic(obj: JSONObject): XkcdComic? = try {
        val img = obj.getString("img")
        // Defensive parsing: reject anything that isn't an https:// URL.
        // The Tier-3 tooltip claims "fetches use HTTPS only"; enforcing the
        // claim here protects against a future MITM that swaps in http:// or
        // a non-URL scheme. Returning null routes through the same "comic
        // unavailable" fallback as a network failure.
        if (!img.startsWith("https://")) return null
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
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
