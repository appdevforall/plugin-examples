package org.appdevforall.codeonthego.layouteditor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityPreviewLayoutBinding
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutParser
import java.io.File

/**
 * Full-screen preview of the current layout, hosted by the IDE's PluginScreenActivity (opened via
 * IdeUIService.openPluginScreen). Ports the in-app PreviewLayoutActivity; reads the target layout
 * from [EditorSubScreenState].
 */
class PreviewLayoutFragment : Fragment() {

    private var _binding: ActivityPreviewLayoutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = ActivityPreviewLayoutBinding.inflate(
            PluginFragmentHelper.getPluginInflater(LayoutEditorPlugin.PLUGIN_ID, inflater),
            container,
            false,
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val layoutFile = EditorSubScreenState.previewLayoutFile
        val basePath = layoutFile?.path?.let { File(it).parent }
        val parser = XmlLayoutParser(requireContext(), basePath)
        layoutFile?.readDesignFile()?.let { parser.processXml(it, requireContext()) }

        val previewContainer = binding.root.findViewById<ViewGroup>(R.id.preview_container)
        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        parser.root?.let { rootView ->
            (rootView.parent as? ViewGroup)?.removeView(rootView)
            previewContainer.addView(rootView, layoutParams)
        } ?: showErrorDialog()
    }

    private fun showErrorDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.preview_render_error_title))
            .setMessage(getString(R.string.preview_render_error_message))
            .setPositiveButton(getString(R.string.msg_ok)) { dialog, _ ->
                dialog.dismiss()
                requireActivity().finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
