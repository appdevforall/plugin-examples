package org.appdevforall.codeonthego.layouteditor

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.ClipboardUtils
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityShowXMLBinding
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils.Companion.make
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Full-screen read-only XML viewer, hosted by the IDE's PluginScreenActivity (openPluginScreen).
 * Ports ShowXMLActivity; reads the XML to display from [EditorSubScreenState]. Sora themes,
 * grammars and the editor font are loaded from the plugin's own assets/resources.
 */
class ShowXmlFragment : Fragment() {

    private var _binding: ActivityShowXMLBinding? = null
    private val binding get() = _binding!!

    private val pluginContext: Context
        get() = PluginFragmentHelper.getPluginContext(LayoutEditorPlugin.PLUGIN_ID) ?: requireContext()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = ActivityShowXMLBinding.inflate(
            PluginFragmentHelper.getPluginInflater(LayoutEditorPlugin.PLUGIN_ID, inflater),
            container,
            false,
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.topAppBar.setTitle(R.string.xml_preview)
        binding.topAppBar.setNavigationOnClickListener { requireActivity().finish() }

        binding.editor.apply {
            setText(EditorSubScreenState.xml)
            typefaceText = jetBrainsMono()
            typefaceLineNumber = jetBrainsMono()
            isEditable = false
        }
        try {
            loadDefaultThemes()
            ThemeRegistry.getInstance().setTheme("darcula")
            loadDefaultLanguages()
            ensureTextmateTheme()
            binding.editor.setEditorLanguage(TextMateLanguage.create("text.xml", true))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        binding.fab.setOnClickListener {
            ClipboardUtils.copyText(binding.editor.text.toString())
            make(binding.root, getString(R.string.copied))
                .setAnchorView(binding.fab)
                .setSlideAnimation()
                .show()
        }

        binding.editor.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 20 && binding.fab.isExtended) binding.fab.shrink()
            if (y < oldY - 20 && !binding.fab.isExtended) binding.fab.extend()
            if (y == 0) binding.fab.extend()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    @Throws(Exception::class)
    private fun loadDefaultThemes() {
        FileProviderRegistry.getInstance()
            .addFileProvider(AssetsFileResolver(pluginContext.assets))

        val themeRegistry = ThemeRegistry.getInstance()
        val path = "editor/textmate/darcula.json"
        themeRegistry.loadTheme(
            ThemeModel(
                IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(path), path, null,
                ),
                "darcula",
            ),
        )
        themeRegistry.setTheme("darcula")
    }

    private fun loadDefaultLanguages() {
        GrammarRegistry.getInstance().loadGrammars("editor/textmate/languages.json")
    }

    @Throws(Exception::class)
    private fun ensureTextmateTheme() {
        val editor = binding.editor
        var editorColorScheme: EditorColorScheme? = editor.colorScheme
        if (editorColorScheme !is TextMateColorScheme) {
            editorColorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            editor.colorScheme = editorColorScheme
        }
    }

    private fun jetBrainsMono(): Typeface? =
        ResourcesCompat.getFont(pluginContext, R.font.jetbrains_mono_regular)
}
