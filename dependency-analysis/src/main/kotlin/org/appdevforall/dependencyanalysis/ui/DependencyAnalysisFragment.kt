package org.appdevforall.dependencyanalysis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import org.appdevforall.dependencyanalysis.DependencyAnalysisPlugin
import org.appdevforall.dependencyanalysis.R

/**
 * The "Dependencies" bottom-sheet tab body.
 *
 * SCAFFOLD: this fragment wires the plugin-aware inflater and inflates the root
 * layout so the module is coherent and the tab registers. The UI agent fills in
 * the states (intro / running / results / clean / setup-needed / error), the
 * Analyze + Fix actions, the confirm + post-apply bottom sheets, and the
 * open-build.gradle-buffer reconciliation — driving everything through
 * [org.appdevforall.dependencyanalysis.data.GradleAnalysisRunner].
 *
 * MANDATORY: override onGetLayoutInflater (NOT onCreateView) to wrap the inflater
 * via PluginFragmentHelper, or R.layout.* throws Resources$NotFoundException —
 * plugin resources live in a separate DexClassLoader APK.
 */
class DependencyAnalysisFragment : Fragment() {

    private var rootView: View? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(DependencyAnalysisPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_dependency_analysis, container, false)
            .also { rootView = it }
    }

    override fun onDestroyView() {
        // Clear view refs to avoid leaking the view tree past the Fragment's view
        // lifecycle. The UI agent must also cancel any in-flight runner work here.
        rootView = null
        super.onDestroyView()
    }
}
