package org.appdevforall.randomxkcd.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.appdevforall.randomxkcd.R
import org.appdevforall.randomxkcd.XkcdRandomPlugin
import org.appdevforall.randomxkcd.net.XkcdApiClient
import org.appdevforall.randomxkcd.net.XkcdComic
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The "XKCD" tab body.
 *
 * Reading order:
 *   - onCreateView / onViewCreated: standard fragment setup, with the
 *     PluginFragmentHelper-wrapped inflater that lets us resolve our
 *     own R.layout.* against the plugin's APK.
 *   - the button-row wiring + image gesture detector: xkcd.com-style
 *     5-button navigation (|< · < Prev · Random · Next > · >|) above
 *     the comic, plus single/double-tap shortcuts on the image itself.
 *   - the navigation handlers (goFirst / goPrev / goRandom / goNext /
 *     goLast) all go through [loadComic], which dispatches the right
 *     fetch and re-renders.
 *
 * Navigation state:
 *   - [currentComicNum] — the number we're displaying right now.
 *   - [latestComicNum] — the high-water mark from xkcd's latest-comic
 *     probe. Used to disable Next at the upper edge and to validate
 *     range; refreshed on every Last-button tap.
 *
 * Edge handling:
 *   - Comic #404 is xkcd's joke comic: its JSON endpoint returns HTTP
 *     404 deliberately. When a Prev / Next step would land on #404,
 *     the fetch handler keeps stepping in the same direction (Prev
 *     → 403; Next → 405) so the user never sees a "failed to load"
 *     toast for a quirk of the source data.
 */
class XkcdPanelFragment : Fragment() {

    private val api = XkcdApiClient()

    // Bound view references — populated in onViewCreated, cleared in
    // onDestroyView so we don't leak views across configuration changes.
    private var imageCard: FrameLayout? = null
    private var imageView: ImageView? = null
    private var captionView: TextView? = null
    private var altView: TextView? = null
    private var progressView: ProgressBar? = null
    private var emptyView: TextView? = null
    private var btnFirst: Button? = null
    private var btnPrev: Button? = null
    private var btnNext: Button? = null
    private var btnLast: Button? = null

    /** The comic we're currently displaying — used by the clipboard handlers. */
    private var currentComic: XkcdComic? = null

    /** Number of the comic currently displayed. Drives Prev/Next disable logic. */
    private var currentComicNum: Int? = null

    /**
     * High-water mark — the latest comic number known to exist on
     * xkcd.com. Set on the first successful Latest fetch (or after Last).
     * Used to disable Next at the upper edge and to bound Random.
     */
    private var latestComicNum: Int? = null

    /**
     * Raw PNG bytes of the currently-displayed comic. Kept in memory so a
     * "copy image" tap can hit the clipboard without re-downloading or
     * re-encoding the rendered Bitmap.
     */
    private var lastBytes: ByteArray? = null

    /** In-flight fetch, so rapid button presses don't fan out into N parallel fetches. */
    private var loadJob: Job? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        // Plugins must wrap the inflater so R.layout.* resolves against
        // the plugin's APK resources, not the host IDE's. Without this
        // you get a Resources$NotFoundException at inflate time.
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(XkcdRandomPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_xkcd_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imageCard = view.findViewById(R.id.xkcd_image_card)
        imageView = view.findViewById(R.id.xkcd_image)
        captionView = view.findViewById(R.id.xkcd_caption)
        altView = view.findViewById(R.id.xkcd_alt)
        progressView = view.findViewById(R.id.xkcd_progress)
        emptyView = view.findViewById(R.id.xkcd_empty)
        btnFirst = view.findViewById(R.id.xkcd_btn_first)
        btnPrev = view.findViewById(R.id.xkcd_btn_prev)
        btnNext = view.findViewById(R.id.xkcd_btn_next)
        btnLast = view.findViewById(R.id.xkcd_btn_last)
        val btnRandom = view.findViewById<Button>(R.id.xkcd_btn_random)

        // Navigation row: |< · < Prev · Random · Next > · >| mirrors
        // xkcd.com itself. Each handler calls loadComic() with the
        // appropriate Navigation target; loadComic() does the fetch +
        // render + button-state update.
        btnFirst?.setOnClickListener { loadComic(Navigation.First) }
        btnPrev?.setOnClickListener { loadComic(Navigation.Prev) }
        btnRandom.setOnClickListener { loadComic(Navigation.Random) }
        btnNext?.setOnClickListener { loadComic(Navigation.Next) }
        btnLast?.setOnClickListener { loadComic(Navigation.Last) }

        // Until the first comic loads, Prev / Next have nothing to
        // act on — disable them so the affordance is honest.
        updateNavButtonState()

        // Single-tap image → copy URL · Double-tap image → copy image.
        // Standard GestureDetector handles the timing; we don't need a
        // custom state machine. Attaches to the image only (not the whole
        // panel) so scrolling a tall comic doesn't trigger taps.
        val gestureDetector = GestureDetector(view.context, ImageGestureListener())
        imageView?.setOnTouchListener { v, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            handled
        }

        // First show → kick off a Latest fetch (per Bryan, the panel
        // defaults to the most recent comic, not Random). On configuration
        // change (rotation, etc.) the fragment is recreated but we don't
        // re-fetch; the user can tap a nav button if they want a new one.
        if (savedInstanceState == null) loadComic(Navigation.Last)
    }

    override fun onDestroyView() {
        imageCard = null
        imageView = null
        captionView = null
        altView = null
        progressView = null
        emptyView = null
        btnFirst = null
        btnPrev = null
        btnNext = null
        btnLast = null
        super.onDestroyView()
    }

    // --- gesture handling ---

    private inner class ImageGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true  // required to receive subsequent events

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            copyUrlToClipboard()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            copyImageToClipboard()
            return true
        }
    }

    // --- clipboard ---

    private fun copyUrlToClipboard() {
        val comic = currentComic ?: run {
            toast(getString(R.string.toast_no_comic_yet))
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xkcd-url", comic.pageUrl))
        // Android 13+ shows its own clipboard indicator; our toast would double-up.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(getString(R.string.toast_url_copied, comic.pageUrl))
        }
    }

    /**
     * Push the in-memory PNG to the clipboard as image/png.
     *
     * Plugin manifest providers don't get registered at runtime (plugins
     * are loaded via DexClassLoader, not installed as apps), so we route
     * through the host IDE's existing FileProvider authority. The host's
     * file_provider_paths.xml exposes `filesDir` (`<files-path … path="." />`),
     * so we write the current comic's PNG to `filesDir/xkcd_share/last.png`
     * and grant a content URI from there.
     *
     * URI-permission caveat: `ClipData.newUri` does not auto-grant
     * `FLAG_GRANT_READ_URI_PERMISSION` to the eventual paste target. We
     * rely on the host's FileProvider declaring `grantUriPermissions="true"`,
     * which lets the system grant a temporary read grant to whatever app
     * calls `ContentResolver.openInputStream` on our clip URI. This works on
     * stock Android API 24+ but has been observed to fail silently on some
     * OEM clipboard managers — worth real-device verification.
     */
    private fun copyImageToClipboard() {
        val bytes = lastBytes
        if (bytes == null) {
            toast(getString(R.string.toast_image_copy_failed))
            return
        }
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            // Up to 5 MB of file write — off the main thread.
            val target = withContext(Dispatchers.IO) {
                val shareDir = File(ctx.filesDir, "xkcd_share").apply { mkdirs() }
                val out = File(shareDir, "last.png")
                try {
                    out.writeBytes(bytes)
                    out
                } catch (_: Exception) {
                    null
                }
            }
            if (target == null) {
                toast(getString(R.string.toast_image_copy_failed))
                return@launch
            }
            val authority = "${ctx.packageName}.providers.fileprovider"
            val uri = FileProvider.getUriForFile(ctx, authority, target)
            // ClipData.newUri queries the ContentResolver for the URI's
            // MIME type (image/png for our PNG), so the resulting clip
            // advertises image/* to paste targets.
            val clip = ClipData.newUri(ctx.contentResolver, "xkcd-image", uri)
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            // Android 13+ shows its own clipboard indicator; our toast would double-up.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                toast(getString(R.string.toast_image_copied))
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    // --- rendering ---

    private fun showLoading() {
        progressView?.visibility = View.VISIBLE
        emptyView?.visibility = View.GONE
    }

    private fun showEmptyState() {
        progressView?.visibility = View.GONE
        imageCard?.visibility = View.GONE
        captionView?.visibility = View.GONE
        altView?.visibility = View.GONE
        emptyView?.visibility = View.VISIBLE
        emptyView?.setText(R.string.empty_offline)
    }

    private fun showComic(comic: XkcdComic, bmp: android.graphics.Bitmap) {
        progressView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        imageCard?.visibility = View.VISIBLE
        imageView?.setImageBitmap(bmp)
        captionView?.apply {
            visibility = View.VISIBLE
            text = getString(R.string.comic_caption, comic.num, comic.title)
        }
        altView?.apply {
            visibility = View.VISIBLE
            text = getString(R.string.comic_alt_prefix, comic.alt)
        }
    }

    // --- navigation + networking ---

    /** Where to go next, in xkcd.com's nav-bar vocabulary. */
    private enum class Navigation { First, Prev, Random, Next, Last }

    /**
     * Toggle Prev / Next enabled state based on where we are vs. the
     * known range. First / Random / Last are always enabled — their
     * actions don't depend on the current cursor.
     *
     * Called whenever [currentComicNum] or [latestComicNum] changes,
     * plus once on initial wire-up when both are still null.
     */
    private fun updateNavButtonState() {
        val cur = currentComicNum
        val latest = latestComicNum
        // Prev is only meaningful once we know we're past comic #1.
        btnPrev?.isEnabled = cur != null && cur > 1
        // Next is only meaningful once we know the upper bound and
        // we're below it.
        btnNext?.isEnabled = cur != null && latest != null && cur < latest
    }

    /**
     * Dispatch the requested navigation, fetch the resulting comic on
     * `Dispatchers.IO`, then render on the main thread. Skips if a fetch
     * is already in flight so rapid button mashes don't fan out.
     *
     * The fetch is structured as a `nav -> Int?` resolver that decides
     * which comic number to ask for (returning null if there's nothing
     * to do — e.g. Prev at #1). [fetchByNumberSkipping404] handles the
     * #404-joke edge case by stepping past it in the same direction the
     * user requested.
     */
    private fun loadComic(nav: Navigation) {
        if (loadJob?.isActive == true) return
        if (currentComic == null) showLoading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchForNavigation(nav) }
            val (comic, bytes, bmp) = result ?: run {
                if (currentComic == null) showEmptyState() else {
                    progressView?.visibility = View.GONE
                    toast(getString(R.string.toast_fetch_failed))
                }
                return@launch
            }
            currentComic = comic
            currentComicNum = comic.num
            // Last refreshes the high-water mark; First / Prev / Next /
            // Random never lower it. Random can land on a number we
            // didn't know about yet — bump latest in that case so Next
            // doesn't immediately re-disable.
            latestComicNum = maxOf(latestComicNum ?: comic.num, comic.num)
            lastBytes = bytes
            showComic(comic, bmp)
            updateNavButtonState()
        }
    }

    /**
     * Resolve [nav] to a concrete comic + render bytes + decoded bitmap.
     * Runs on `Dispatchers.IO`. Returns null on any IO / parse failure.
     */
    private suspend fun fetchForNavigation(
        nav: Navigation
    ): Triple<XkcdComic, ByteArray, android.graphics.Bitmap>? {
        val comic: XkcdComic = when (nav) {
            Navigation.First -> fetchByNumberSkipping404(1, step = +1) ?: return null
            Navigation.Last -> {
                // Always re-probe Latest — new comics land on M/W/F,
                // and we want the freshest high-water mark.
                api.fetchLatest() ?: return null
            }
            Navigation.Random -> api.fetchRandom() ?: return null
            Navigation.Prev -> {
                val cur = currentComicNum ?: return null
                if (cur <= 1) return null
                fetchByNumberSkipping404(cur - 1, step = -1) ?: return null
            }
            Navigation.Next -> {
                val cur = currentComicNum ?: return null
                val latest = latestComicNum
                if (latest != null && cur >= latest) return null
                fetchByNumberSkipping404(cur + 1, step = +1) ?: return null
            }
        }
        val bytes = downloadImageBytes(comic.imageUrl) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return Triple(comic, bytes, bmp)
    }

    /**
     * Fetch [start], skipping xkcd #404 (the joke comic that returns
     * HTTP 404) by stepping in [step] direction. Bounds the walk at
     * comic #1 and at the known latest number so a runaway can't hammer
     * the API. Returns null if no comic exists in range.
     */
    private fun fetchByNumberSkipping404(start: Int, step: Int): XkcdComic? {
        require(step == 1 || step == -1) { "step must be +1 or -1" }
        var n = start
        val upper = latestComicNum ?: Int.MAX_VALUE
        while (n in 1..upper) {
            if (n == 404) {
                n += step
                continue
            }
            return api.fetchByNumber(n) ?: run {
                // Single transient miss — let the caller render the
                // toast fallback rather than walking blindly.
                null
            }
        }
        return null
    }

    /**
     * Stream + buffer a comic's PNG with a 5 MB cap and cooperative
     * cancellation. Pulled out so both the nav-button path and any
     * future caller can share the same bounded-read loop.
     */
    private suspend fun downloadImageBytes(imageUrl: String): ByteArray? {
        return api.openImageStream(imageUrl)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                // Cooperative cancellation — let lifecycleScope teardown
                // interrupt mid-download.
                coroutineContext.ensureActive()
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_IMAGE_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    companion object {
        /** 5 MB cap — comfortably above the largest xkcd PNG, but bounded. */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    }
}
