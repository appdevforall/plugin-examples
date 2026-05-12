package com.codeonthego.xkcdrandom.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.codeonthego.xkcdrandom.R
import com.codeonthego.xkcdrandom.XkcdRandomPlugin
import com.codeonthego.xkcdrandom.ui.TapCountClassifier
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * The "XKCD" tab body.
 *
 * Reading order:
 *   - onCreateView / onViewCreated: standard fragment setup, with the
 *     PluginFragmentHelper-wrapped inflater that lets us resolve our
 *     own R.layout.* against the plugin's APK.
 *   - the OnTouchListener: where ACTION_UP feeds the
 *     [TapCountClassifier] and decides what to do next.
 *
 * Why we don't use [android.view.GestureDetector]:
 *   - GestureDetector resolves single/double tap but not triple.
 *   - A small purpose-built [TapCountClassifier] reads cleaner in the
 *     Tier-3 walkthrough.
 */
class XkcdPanelFragment : Fragment() {

    private val tapClassifier = TapCountClassifier()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Bound view references — populated in onViewCreated, cleared in
    // onDestroyView so we don't leak views across configuration changes.
    private var imageCard: FrameLayout? = null
    private var imageView: ImageView? = null
    private var captionView: TextView? = null
    private var altView: TextView? = null
    private var progressView: ProgressBar? = null
    private var emptyView: TextView? = null

    /** Pending tap-window timeout — cancelled if we resolve early. */
    private val resolveBurstRunnable = Runnable { handleClassification(tapClassifier.resolve()) }

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

        // Tap dispatch: ACTION_UP feeds the classifier *only* if the
        // gesture didn't move beyond the system touch slop — otherwise
        // every fling/scroll on a tall comic would also fire a tap.
        val root = view.findViewById<View>(R.id.xkcd_root)
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                        handleTap()
                        root.performClick()  // accessibility-friendly
                    }
                }
            }
            // We never consume the event here; let the ScrollView keep
            // its scroll behavior so long content is still scrollable.
            false
        }

        showEmptyState()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(resolveBurstRunnable)
        imageCard = null
        imageView = null
        captionView = null
        altView = null
        progressView = null
        emptyView = null
        super.onDestroyView()
    }

    // --- gesture handling ---

    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        val burstClosedEarly = tapClassifier.onTap(now)
        if (burstClosedEarly) {
            // Triple-tap: resolve immediately for snappy feedback.
            mainHandler.removeCallbacks(resolveBurstRunnable)
            handleClassification(tapClassifier.resolve())
            return
        }
        // Otherwise, wait one window for more taps. Re-arm the timeout
        // on every tap so the burst only fires after the user pauses.
        mainHandler.removeCallbacks(resolveBurstRunnable)
        mainHandler.postDelayed(resolveBurstRunnable, TapCountClassifier.DEFAULT_WINDOW_MS)
    }

    private fun handleClassification(c: TapCountClassifier.Classification?) {
        // Guard against the deferred Handler runnable firing after the
        // view has been torn down — would otherwise touch viewLifecycleOwner.
        if (!isAdded || view == null) return
        // Wiring to actual behaviors (fetch / clipboard) arrives in
        // subsequent commits. For now, every classification is a no-op.
        when (c) {
            TapCountClassifier.Classification.SINGLE -> { /* loadRandomComic — later */ }
            TapCountClassifier.Classification.DOUBLE -> { /* copyUrlToClipboard — later */ }
            TapCountClassifier.Classification.TRIPLE -> { /* copyImageToClipboard — later */ }
            null -> { /* nothing to do */ }
        }
    }

    // --- rendering ---

    private fun showEmptyState() {
        progressView?.visibility = View.GONE
        imageCard?.visibility = View.GONE
        captionView?.visibility = View.GONE
        altView?.visibility = View.GONE
        emptyView?.visibility = View.VISIBLE
        emptyView?.setText(R.string.empty_offline)
    }
}
