package org.appdevforall.randomxkcd.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
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
 *     controls above the comic, plus single/double-tap shortcuts on
 *     the image itself.
 *   - loadRandomComic(): coroutine-based fetch + render.
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

    /** The comic we're currently displaying — used by the clipboard handlers. */
    private var currentComic: XkcdComic? = null

    /**
     * Raw PNG bytes of the currently-displayed comic. Kept in memory so a
     * "copy image" tap can hit the clipboard without re-downloading or
     * re-encoding the rendered Bitmap.
     */
    private var lastBytes: ByteArray? = null

    /** In-flight fetch, so rapid Random presses don't fan out into N parallel fetches. */
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

        // Button row mirrors the xkcd.com control bar — explicit, discoverable
        // actions for everything the plugin does. Gestures on the image (below)
        // are convenience shortcuts that do the same things.
        view.findViewById<Button>(R.id.xkcd_btn_random).setOnClickListener {
            loadRandomComic()
        }
        view.findViewById<Button>(R.id.xkcd_btn_copy_url).setOnClickListener {
            copyUrlToClipboard()
        }
        view.findViewById<Button>(R.id.xkcd_btn_copy_image).setOnClickListener {
            copyImageToClipboard()
        }

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

        // First show → kick off a fresh fetch. On configuration change
        // (rotation, etc.) the fragment is recreated but we don't re-fetch;
        // the user can tap Random if they want a new comic.
        if (savedInstanceState == null) loadRandomComic()
    }

    override fun onDestroyView() {
        imageCard = null
        imageView = null
        captionView = null
        altView = null
        progressView = null
        emptyView = null
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
            toast(getString(R.string.toast_image_copy_failed))
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xkcd-url", comic.pageUrl))
        toast(getString(R.string.toast_url_copied, comic.pageUrl))
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
            toast(getString(R.string.toast_image_copied))
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

    // --- networking ---

    /**
     * Fetch a new random comic, then update the UI. All network IO and
     * bitmap decoding are on Dispatchers.IO; the callback hops back to
     * the main thread via the lifecycleScope's Main dispatcher.
     *
     * Skips if a previous fetch is still in flight (rapid Random presses no-op).
     */
    private fun loadRandomComic() {
        if (loadJob?.isActive == true) return
        // Only blank the panel if we have nothing to show yet — otherwise
        // keep the current comic visible while the new one loads.
        if (currentComic == null) showLoading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchAndDecode() }
            val (comic, bytes, bmp) = result ?: run {
                if (currentComic == null) showEmptyState() else {
                    progressView?.visibility = View.GONE
                    toast(getString(R.string.toast_fetch_failed))
                }
                return@launch
            }
            currentComic = comic
            lastBytes = bytes
            showComic(comic, bmp)
        }
    }

    /** Returns (comic, raw PNG bytes, decoded bitmap) on success, null on any IO/parse failure. */
    private suspend fun fetchAndDecode(): Triple<XkcdComic, ByteArray, android.graphics.Bitmap>? {
        val comic = api.fetchRandom() ?: return null
        val bytes = api.openImageStream(comic.imageUrl)?.use { stream ->
            // Bounded read — cap at 5 MB so a pathological response can't
            // OOM the decoder. xkcd images are far below this in practice.
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
        } ?: return null
        // Plain decode. For very large images on low-end devices, Android's
        // bounded-bitmap-decoding pattern (BitmapFactory.Options.inSampleSize)
        // is the production-grade approach — see
        // https://developer.android.com/topic/performance/graphics/load-bitmap
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return Triple(comic, bytes, bmp)
    }

    companion object {
        /** 5 MB cap — comfortably above the largest xkcd PNG, but bounded. */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    }
}
