package org.appdevforall.codeonthego.layouteditor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityPreviewDrawableBinding
import org.appdevforall.codeonthego.layouteditor.views.AlphaPatternDrawable

/**
 * Full-screen drawable preview, hosted by the IDE's PluginScreenActivity (openPluginScreen).
 * Ports PreviewDrawableActivity; the drawable is supplied by [onLoad], set by the caller
 * (DrawableResourceAdapter) right before the screen is opened.
 */
class PreviewDrawableFragment : Fragment() {

    private var _binding: ActivityPreviewDrawableBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = ActivityPreviewDrawableBinding.inflate(
            PluginFragmentHelper.getPluginInflater(LayoutEditorPlugin.PLUGIN_ID, inflater),
            container,
            false,
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.topAppBar.setTitle(R.string.preview_drawable)
        binding.topAppBar.setNavigationOnClickListener { requireActivity().finish() }
        binding.background.setImageDrawable(AlphaPatternDrawable(24))
        onLoad(binding.mainImage, binding.topAppBar)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        @JvmStatic
        var onLoad: (ImageView, MaterialToolbar?) -> Unit = { _, _ -> }
    }
}
