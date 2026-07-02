package org.appdevforall.codeonthego.layouteditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.Companion.isPhotoPickerAvailable
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.layouteditor.adapters.PagerAdapter
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityResourceManagerBinding
import org.appdevforall.codeonthego.layouteditor.fragments.resources.ColorFragment
import org.appdevforall.codeonthego.layouteditor.fragments.resources.DrawableFragment
import org.appdevforall.codeonthego.layouteditor.fragments.resources.FontFragment
import org.appdevforall.codeonthego.layouteditor.fragments.resources.StringFragment
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager
import org.appdevforall.codeonthego.layouteditor.utils.FilePicker
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils
import org.appdevforall.codeonthego.vectormaster.VectorMasterDrawable
import java.io.FileNotFoundException

/**
 * Project resource manager (drawables/colors/strings/fonts), hosted full-screen by the IDE's
 * PluginScreenActivity (openPluginScreen). Ports ResourceManagerActivity; uses the already-open
 * project from [ProjectManager], hosts the resource tabs in the child fragment manager, and opens
 * its own sub-screens (XML viewer, drawable preview) via [openLayoutEditorScreen].
 */
class ResourceManagerFragment : Fragment() {

    private var binding: ActivityResourceManagerBinding? = null
    private var project: ProjectFile? = null
    private var photoPicker: FilePicker? = null
    private var fontPicker: FilePicker? = null
    private var xmlPicker: FilePicker? = null
    private var pickMedia: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var requestPermission: ActivityResultLauncher<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { onRequestPermission(it) }
        photoPicker = object : FilePicker(this) {
            override fun onPickFile(uri: Uri?) = onPickPhoto(uri)
        }
        fontPicker = object : FilePicker(this) {
            override fun onPickFile(uri: Uri?) = onPickFont(uri)
        }
        xmlPicker = object : FilePicker(this) {
            override fun onPickFile(uri: Uri?) = onPickXml(uri)
        }
        pickMedia = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? -> onPickPhoto(uri) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = ActivityResourceManagerBinding.inflate(
            PluginFragmentHelper.getPluginInflater(LayoutEditorPlugin.PLUGIN_ID, inflater),
            container,
            false,
        )
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding!!.topAppBar.setTitle(R.string.res_manager)
        binding!!.topAppBar.setNavigationOnClickListener { requireActivity().finish() }
        setupToolbarMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                project = ProjectManager.instance.openedProject
                Log.d("ResourceManagerFragment", "openedProject = ${project?.path}")
                if (project == null) {
                    Log.w("ResourceManagerFragment", "No open project; finishing resource manager")
                    requireActivity().finish()
                    return@launch
                }
                setupViewPager()
            } catch (e: Exception) {
                ToastUtils.showShort(R.string.msg_error_opening_project)
                Log.e("ResourceManagerFragment", "Error loading project", e)
                requireActivity().finish()
            }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun setupViewPager() {
        val currentProject = project ?: return
        val adapter = PagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        adapter.setup(binding!!.pager, binding!!.tabLayout)
        adapter.addFragmentToAdapter(
            DrawableFragment.newInstance(currentProject),
            getString(R.string.drawable),
            ContextCompat.getDrawable(requireContext(), R.drawable.image_outline)!!,
        )
        adapter.addFragmentToAdapter(
            ColorFragment.newInstance(currentProject),
            getString(R.string.color),
            ContextCompat.getDrawable(requireContext(), R.drawable.palette_outline)!!,
        )
        adapter.addFragmentToAdapter(
            StringFragment.newInstance(currentProject),
            getString(R.string.string),
            ContextCompat.getDrawable(requireContext(), R.drawable.format_letter_case)!!,
        )
        adapter.addFragmentToAdapter(
            FontFragment.newInstance(currentProject),
            getString(R.string.font),
            ContextCompat.getDrawable(requireContext(), R.drawable.format_font)!!,
        )
        adapter.setupPager(ViewPager2.ORIENTATION_HORIZONTAL)
        adapter.setupMediatorWithIcon()
        Log.d("ResourceManagerFragment", "Resource tabs set up for ${currentProject.path}")
    }

    private fun setupToolbarMenu() {
        binding!!.topAppBar.inflateMenu(R.menu.menu_resource_manager)
        binding!!.topAppBar.setOnMenuItemClickListener { onMenuItemSelected(it) }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        val fragment = childFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
        return when (item.itemId) {
            R.id.menu_add -> {
                handleAddResourceAction(fragment)
                true
            }
            R.id.menu_viewxml -> {
                handleViewXmlAction(fragment)
                true
            }
            else -> false
        }
    }

    private fun handleAddResourceAction(fragment: Fragment?) {
        if (fragment == null) {
            showErrorState("Something went wrong..")
            return
        }
        when (fragment) {
            is DrawableFragment -> showDrawableTypePicker()
            is ColorFragment -> fragment.addColor()
            is StringFragment -> fragment.addString()
            is FontFragment -> fontPicker!!.launch("font/*")
        }
    }

    private fun handleViewXmlAction(fragment: Fragment?) {
        val currentProject = project
        if (currentProject == null) {
            showErrorState(R.string.msg_error_opening_project)
            return
        }
        if (fragment == null) {
            showErrorState("Something went wrong..")
            return
        }
        when (fragment) {
            is ColorFragment -> launchXmlViewer(currentProject.colorsPath)
            is StringFragment -> launchXmlViewer(currentProject.stringsPath)
            else -> SBUtils.make(binding!!.root, "Unavailable for this fragment..")
                .setSlideAnimation()
                .showAsSuccess()
        }
    }

    private fun showDrawableTypePicker() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_drawable_type)
            .setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    arrayOf("Vector Drawable", "Image Drawable"),
                )
            ) { _, w: Int ->
                when (w) {
                    0 -> xmlPicker!!.launch("text/xml")
                    1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launchPhotoPicker()
                    } else {
                        photoPicker?.launch("image/*")
                    }
                }
            }
            .show()
    }

    private fun launchXmlViewer(filePath: String) {
        EditorSubScreenState.xml = FileUtil.readFile(filePath)
        openLayoutEditorScreen(ShowXmlFragment::class.java.name, getString(R.string.xml_preview))
    }

    private fun showErrorState(message: String) {
        SBUtils.make(binding!!.root, message).setSlideAnimation().showAsError()
    }

    private fun showErrorState(messageResId: Int) {
        SBUtils.make(binding!!.root, messageResId).setSlideAnimation().showAsError()
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun launchPhotoPicker() {
        if (isPhotoPickerAvailable(requireContext())) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_DENIED
            ) {
                requestPermission!!.launch(Manifest.permission.READ_MEDIA_IMAGES)
                return
            }
            pickMedia!!.launch(
                PickVisualMediaRequest.Builder().setMediaType(ImageOnly).build()
            )
        } else {
            photoPicker!!.launch("image/*")
        }
    }

    private fun onPickPhoto(uri: Uri?) {
        if (uri != null) {
            if (FileUtil.isDownloadsDocument(uri)) {
                SBUtils.make(binding!!.root, R.string.select_from_storage).showAsError()
                return
            }
            val fragment = childFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
            if (fragment is DrawableFragment) fragment.addDrawable(uri)
        } else {
            SBUtils.make(binding!!.root, "No image selected").setFadeAnimation().show()
        }
    }

    private fun onPickFont(uri: Uri?) {
        if (uri != null) {
            if (FileUtil.isDownloadsDocument(uri)) {
                SBUtils.make(binding!!.root, R.string.select_from_storage).showAsError()
                return
            }
            val fragment = childFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
            if (fragment is FontFragment) fragment.addFont(uri)
        } else {
            SBUtils.make(binding!!.root, "No font selected").setFadeAnimation().show()
        }
    }

    private fun onPickXml(uri: Uri?) {
        if (uri != null) {
            if (FileUtil.isDownloadsDocument(uri)) {
                SBUtils.make(binding!!.root, R.string.select_from_storage).showAsError()
                return
            }
            val fragment = childFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
            if (fragment is DrawableFragment) {
                try {
                    val drawable = VectorMasterDrawable(requireContext())
                    drawable.setInputStream(requireContext().contentResolver.openInputStream(uri))
                    if (drawable.isVector) {
                        fragment.addDrawable(uri)
                    } else {
                        SBUtils.make(binding!!.root, "Not a valid vector drawable")
                            .setFadeAnimation()
                            .setType(SBUtils.Type.INFO)
                            .show()
                    }
                } catch (e: FileNotFoundException) {
                    ToastUtils.showShort(e.toString())
                }
            }
        } else {
            SBUtils.make(binding!!.root, "No drawable selected").setFadeAnimation().show()
        }
    }

    private fun onRequestPermission(isGranted: Boolean) {
        val root = requireActivity().findViewById<View>(android.R.id.content)
        if (isGranted) {
            SBUtils.make(root, R.string.permission_granted).setSlideAnimation().showAsSuccess()
        } else {
            SBUtils.make(root, R.string.permission_denied).setSlideAnimation().showAsError()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
