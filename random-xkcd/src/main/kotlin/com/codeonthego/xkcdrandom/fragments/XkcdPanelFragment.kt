package com.codeonthego.xkcdrandom.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.codeonthego.xkcdrandom.R
import com.codeonthego.xkcdrandom.XkcdRandomPlugin
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * The "XKCD" tab body — shell.
 *
 * This commit lays out the Fragment + view bindings + render
 * primitives. Behavior (tap handling, network fetch, clipboard) lands
 * in subsequent commits.
 */
class XkcdPanelFragment : Fragment() {

    // Bound view references — populated in onViewCreated, cleared in
    // onDestroyView so we don't leak views across configuration changes.
    private var imageCard: FrameLayout? = null
    private var imageView: ImageView? = null
    private var captionView: TextView? = null
    private var altView: TextView? = null
    private var progressView: ProgressBar? = null
    private var emptyView: TextView? = null

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

        // For now, start in the empty state — fetching arrives in a later
        // commit that adds the network client.
        showEmptyState()
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
