package com.appdevforall.sketchtoui.plugin.fragments

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.appdevforall.sketchtoui.plugin.GeneratedStringsWriter
import com.appdevforall.sketchtoui.plugin.R
import com.appdevforall.sketchtoui.plugin.SketchToUiPlugin
import com.appdevforall.sketchtoui.plugin.SketchToUiState
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.SelectionRange
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.computervision.data.repository.VisionRepositoryImpl
import org.appdevforall.codeonthego.computervision.data.repository.DrawableImportHelper
import org.appdevforall.codeonthego.computervision.data.source.OcrSource
import org.appdevforall.codeonthego.computervision.data.source.YoloModelSource
import org.appdevforall.codeonthego.computervision.domain.GenericBoxResolver
import org.appdevforall.codeonthego.computervision.domain.RegionOcrProcessor
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.usecase.GenerateXmlUC
import org.appdevforall.codeonthego.computervision.domain.usecase.ImportPlaceholderImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.PrepareImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.RemovePlaceholderImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.RunVisionUC
import org.appdevforall.codeonthego.computervision.ui.CvOperation
import org.appdevforall.codeonthego.computervision.ui.GuidelinesView
import org.appdevforall.codeonthego.computervision.ui.SelectedImportedImage
import org.appdevforall.codeonthego.computervision.ui.ZoomableImageView
import org.appdevforall.codeonthego.computervision.utils.DetectionVisualizer
import org.appdevforall.codeonthego.computervision.utils.getSortedPlaceholders
import org.appdevforall.codeonthego.computervision.utils.XmlFileManager

class SketchToUiFragment : Fragment() {

    private var selectedBitmap: Bitmap? = null
    private var generatedXml: GeneratedXml? = null
    private var currentDetections: List<DetectionResult> = emptyList()
    private var parsedAnnotations: Map<String, String> = emptyMap()
    private var pendingImagePlaceholderId: String? = null
    private var selectedImagesByPlaceholderId: Map<String, SelectedImportedImage> = emptyMap()
    private var leftGuidePct: Float = DEFAULT_LEFT_GUIDE_PCT
    private var rightGuidePct: Float = DEFAULT_RIGHT_GUIDE_PCT
    private val detectionVisualizer by lazy { DetectionVisualizer(requireContext()) }
    private val drawableImportHelper by lazy { DrawableImportHelper(requireContext().contentResolver) }
    private val importPlaceholderImageUC by lazy { ImportPlaceholderImageUC(drawableImportHelper) }
    private val removePlaceholderImageUC by lazy { RemovePlaceholderImageUC(drawableImportHelper) }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var imageView: ZoomableImageView
    private lateinit var guidelinesView: GuidelinesView
    private lateinit var detectButton: Button
    private lateinit var updateButton: Button
    private lateinit var saveButton: Button

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::loadImage)
        }
    }

    private val pickPlaceholderImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(::loadPlaceholderImage)
            ?: toast(getString(R.string.msg_no_image_selected))
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(SketchToUiPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = Unit
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sketch_to_ui, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindViews(view)
        applyStatusBarColor()
        setupToolbar()
        setupClickListeners()
        resetTransientState()
        refreshTarget()
    }

    fun refreshTarget() {
        val current = editorService()?.getCurrentFile()
        if (SketchToUiState.layoutFilePath == null && current != null && current.isLikelyLayoutXml()) {
            SketchToUiState.setLayoutFile(current.absolutePath, current.name)
        }
    }

    private fun bindViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        imageView = view.findViewById(R.id.imageView)
        guidelinesView = view.findViewById(R.id.guidelinesView)
        detectButton = view.findViewById(R.id.detectButton)
        updateButton = view.findViewById(R.id.updateButton)
        saveButton = view.findViewById(R.id.saveButton)
        imageView.onMatrixChangeListener = { matrix ->
            guidelinesView.updateMatrix(matrix)
        }
        guidelinesView.onGuidelinesChanged = { leftPct, rightPct ->
            leftGuidePct = leftPct
            rightGuidePct = rightPct
        }
    }

    private fun setupToolbar() {
        toolbar.title = getString(R.string.title_generate_xml)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyStatusBarColor() {
        val color = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorPrimary)
        requireActivity().window.statusBarColor = color
    }

    private fun setupClickListeners() {
        imageView.setOnClickListener { openImagePicker() }
        imageView.onImageTapListener = ::handleImageTap
        detectButton.setOnClickListener {
            runDetection()
        }
        updateButton.setOnClickListener {
            confirmUpdate()
        }
        saveButton.setOnClickListener {
            saveGeneratedXml()
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    private fun loadImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            resetTransientState()
            setBusy(true)
            try {
                val prepared = PrepareImageUC(requireContext().contentResolver)(uri).getOrElse { error ->
                    toast(getString(R.string.error_detection_failed, error.message ?: error.javaClass.simpleName), long = true)
                    return@launch
                }
                selectedBitmap = prepared.bitmap
                leftGuidePct = prepared.leftPct
                rightGuidePct = prepared.rightPct
                generatedXml = null
                detectionVisualizer.clearCache()
                renderImage()
                guidelinesView.setImageDimensions(prepared.bitmap.width, prepared.bitmap.height)
                guidelinesView.updateGuidelines(leftGuidePct, rightGuidePct)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to load image", error)
                toast(getString(R.string.error_detection_failed, error.message ?: error.javaClass.simpleName), long = true)
            } finally {
                setBusy(false)
                updateButtonState()
            }
        }
    }

    private fun runDetection() {
        val bitmap = selectedBitmap
        if (bitmap == null) {
            toast(getString(R.string.error_no_image))
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            try {
                val result = withContext(Dispatchers.Default) {
                    runComputerVisionPipeline(bitmap)
                }

                result
                    .onSuccess { output ->
                        generatedXml = output.generatedXml
                        currentDetections = output.detections
                        parsedAnnotations = output.annotations
                        pendingImagePlaceholderId = null
                        selectedImagesByPlaceholderId = emptyMap()
                        renderDetections(output.detections)
                    }
                    .onFailure { error ->
                        toast(getString(R.string.error_detection_failed, error.message ?: error.javaClass.simpleName), long = true)
                    }
            } catch (error: Throwable) {
                Log.w(TAG, "Detection failed", error)
                toast(getString(R.string.error_detection_failed, error.message ?: error.javaClass.simpleName), long = true)
            } finally {
                setBusy(false)
                updateButtonState()
            }
        }
    }

    private suspend fun runComputerVisionPipeline(bitmap: Bitmap): Result<PipelineOutput> {
        val hostContext = requireContext()
        val pluginContext = SketchToUiPlugin.getPluginAndroidContext() ?: hostContext
        val ocrSource = OcrSource(pluginContext)
        val repository = VisionRepositoryImpl(
            assetManager = hostContext.assets,
            yoloModelSource = YoloModelSource(),
            ocrSource = ocrSource
        )

        return try {
            repository.initModel().getOrThrow()
            val visionResult = RunVisionUC(
                repository = repository,
                boxResolver = GenericBoxResolver(),
                regionOcrProcessor = RegionOcrProcessor(ocrSource)
            )(
                bitmap = bitmap,
                leftPct = leftGuidePct,
                rightPct = rightGuidePct
            ) { operation ->
                view?.post { announceStatus(operation.toStatusText()) }
            }.getOrThrow()

            val (layoutXml, stringsXml) = GenerateXmlUC()(
                detections = visionResult.detections,
                annotations = visionResult.annotations,
                selectedImagesByPlaceholderId = selectedImagesByPlaceholderId.mapValues { it.value.drawableReference },
                sourceImageWidth = bitmap.width,
                sourceImageHeight = bitmap.height,
                targetDpWidth = TARGET_DP_WIDTH,
                targetDpHeight = TARGET_DP_HEIGHT
            ).getOrThrow()

            Result.success(
                PipelineOutput(
                    detectionCount = visionResult.detections.size,
                    detections = visionResult.detections,
                    annotations = visionResult.annotations,
                    generatedXml = GeneratedXml(layoutXml, stringsXml)
                )
            )
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            repository.release()
        }
    }

    private fun CvOperation.toStatusText(): String = when (this) {
        CvOperation.InitializingModel -> getString(R.string.status_initializing_model)
        CvOperation.RunningYolo -> getString(R.string.status_running_yolo)
        CvOperation.RunningOcr -> getString(R.string.status_running_ocr)
        CvOperation.MergingDetections -> getString(R.string.status_merging_detections)
        CvOperation.GeneratingXml -> getString(R.string.status_generating_xml)
        CvOperation.SavingFile -> getString(R.string.status_saving_file)
        CvOperation.Idle -> getString(R.string.status_detecting)
    }

    private fun confirmUpdate() {
        val xml = generatedXml
        if (xml == null) {
            toast(getString(R.string.error_no_generated_xml))
            return
        }

        val targetName = SketchToUiState.layoutFileName
            ?: editorService()?.getCurrentFile()?.name
            ?: "current layout"

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_update_title)
            .setMessage(getString(R.string.confirm_update_message, targetName))
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { dialog, _ ->
                dialog.dismiss()
                applyGeneratedResult(xml)
            }
            .show()
    }

    private fun applyGeneratedResult(xml: GeneratedXml) {
        val target = SketchToUiState.layoutFilePath?.let(::File)
            ?: editorService()?.getCurrentFile()

        if (target == null) {
            toast(getString(R.string.error_no_layout_file), long = true)
            return
        }

        val stringsWritten = GeneratedStringsWriter(projectService(), fileService()).write(xml.stringsXml)
        val layoutWritten = replaceOpenEditorContent(target, xml.layoutXml) ||
            fileService()?.writeFile(target, xml.layoutXml) == true

        if (layoutWritten && stringsWritten) {
            resetTransientState()
            toast(getString(R.string.status_updated))
            requireActivity().onBackPressedDispatcher.onBackPressed()
        } else {
            toast(getString(R.string.error_update_failed), long = true)
        }
    }

    private fun replaceOpenEditorContent(target: File, layoutXml: String): Boolean {
        val service = editorService() ?: return false
        val current = service.getCurrentFile() ?: return false
        if (current.canonicalPath != target.canonicalPath) return false

        val lineCount = service.getLineCount(current)
        if (lineCount <= 0) return false

        val lastLine = lineCount - 1
        val lastColumn = service.getLineText(current, lastLine)?.length ?: 0
        return service.replaceRange(
            current,
            SelectionRange(0, 0, lastLine, lastColumn),
            layoutXml
        )
    }

    private fun saveGeneratedXml() {
        val xml = generatedXml
        if (xml == null) {
            toast(getString(R.string.error_no_generated_xml))
        } else {
            XmlFileManager(requireContext())
                .saveXmlToDownloads(xml.layoutXml)
                .onSuccess { fileName ->
                    toast(getString(R.string.msg_saved_to_downloads, fileName), long = true)
                }
                .onFailure { error ->
                    toast(getString(R.string.msg_error_saving_file, error.message), long = true)
                }
        }
    }

    private fun handleImageTap(imageX: Float, imageY: Float): Boolean {
        val tappedDeleteId = detectionVisualizer.getTappedDeleteIconId(imageX, imageY)
        if (tappedDeleteId != null) {
            removePlaceholderImage(tappedDeleteId)
            return true
        }

        val placeholder = findImagePlaceholderAt(imageX, imageY) ?: return false
        pendingImagePlaceholderId = resolvePlaceholderId(placeholder)
        pickPlaceholderImageLauncher.launch("image/*")
        return true
    }

    private fun loadPlaceholderImage(uri: Uri) {
        val placeholderId = pendingImagePlaceholderId ?: return
        val bitmap = selectedBitmap ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            try {
                importPlaceholderImageUC(uri, SketchToUiState.layoutFilePath, placeholderId)
                    .onSuccess { importedDrawable ->
                        selectedImagesByPlaceholderId = selectedImagesByPlaceholderId +
                            (placeholderId to SelectedImportedImage(importedDrawable.resourceName, importedDrawable.drawableReference))
                        pendingImagePlaceholderId = null
                        regenerateGeneratedXml(bitmap)
                        renderDetections(currentDetections)
                        toast(getString(R.string.msg_placeholder_image_selected))
                    }
                    .onFailure { error ->
                        pendingImagePlaceholderId = null
                        toast("Image import failed: ${error.message}", long = true)
                    }
            } finally {
                setBusy(false)
                updateButtonState()
            }
        }
    }

    private fun removePlaceholderImage(placeholderId: String) {
        val importedImageInfo = selectedImagesByPlaceholderId[placeholderId] ?: return
        val bitmap = selectedBitmap ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            try {
                removePlaceholderImageUC(SketchToUiState.layoutFilePath, importedImageInfo.resourceName)
                    .onSuccess {
                        selectedImagesByPlaceholderId = selectedImagesByPlaceholderId - placeholderId
                        pendingImagePlaceholderId = null
                        regenerateGeneratedXml(bitmap)
                        renderDetections(currentDetections)
                        toast(getString(R.string.msg_image_removed))
                    }
                    .onFailure { error ->
                        toast("Failed to clean up image file: ${error.message}", long = true)
                    }
            } finally {
                setBusy(false)
                updateButtonState()
            }
        }
    }

    private fun regenerateGeneratedXml(bitmap: Bitmap) {
        GenerateXmlUC()(
            detections = currentDetections,
            annotations = parsedAnnotations,
            selectedImagesByPlaceholderId = selectedImagesByPlaceholderId.mapValues { it.value.drawableReference },
            sourceImageWidth = bitmap.width,
            sourceImageHeight = bitmap.height,
            targetDpWidth = TARGET_DP_WIDTH,
            targetDpHeight = TARGET_DP_HEIGHT
        ).onSuccess { (layoutXml, stringsXml) ->
            generatedXml = GeneratedXml(layoutXml, stringsXml)
        }.onFailure { error ->
            toast("XML generation failed: ${error.message}", long = true)
        }
    }

    private fun findImagePlaceholderAt(imageX: Float, imageY: Float): DetectionResult? {
        return currentDetections
            .getSortedPlaceholders()
            .firstOrNull { it.boundingBox.contains(imageX, imageY) }
    }

    private fun resolvePlaceholderId(detection: DetectionResult): String {
        val index = currentDetections.getSortedPlaceholders().indexOf(detection)
        return "ph_${index.coerceAtLeast(0)}"
    }

    private fun updateButtonState() {
        detectButton.isEnabled = selectedBitmap != null
        updateButton.isEnabled = generatedXml != null
        saveButton.isEnabled = generatedXml != null
    }

    private fun resetTransientState() {
        selectedBitmap = null
        generatedXml = null
        currentDetections = emptyList()
        parsedAnnotations = emptyMap()
        pendingImagePlaceholderId = null
        selectedImagesByPlaceholderId = emptyMap()
        leftGuidePct = DEFAULT_LEFT_GUIDE_PCT
        rightGuidePct = DEFAULT_RIGHT_GUIDE_PCT
        detectionVisualizer.clearCache()
        imageView.setImageDrawable(null)
        imageView.isEnabled = true
        guidelinesView.setImageDimensions(0, 0)
        guidelinesView.updateGuidelines(leftGuidePct, rightGuidePct)
        updateButtonState()
    }

    private fun renderImage() {
        imageView.setImageBitmap(selectedBitmap)
    }

    private fun renderDetections(detections: List<DetectionResult>) {
        val bitmap = selectedBitmap ?: return
        val visualizedBitmap = detectionVisualizer.visualize(
            bitmap = bitmap,
            detections = detections,
            selectedPlaceholderIds = selectedImagesByPlaceholderId.keys
        )
        imageView.setImageBitmap(visualizedBitmap)
        guidelinesView.setImageDimensions(bitmap.width, bitmap.height)
        guidelinesView.updateGuidelines(leftGuidePct, rightGuidePct)
    }

    private fun setBusy(isBusy: Boolean) {
        imageView.isEnabled = !isBusy
        detectButton.isEnabled = !isBusy && selectedBitmap != null
        updateButton.isEnabled = !isBusy && generatedXml != null
        saveButton.isEnabled = !isBusy && generatedXml != null
    }

    private fun announceStatus(message: String) {
        view?.announceForAccessibility(message)
    }

    private fun editorService(): IdeEditorService? =
        PluginFragmentHelper.getServiceRegistry(SketchToUiPlugin.PLUGIN_ID)
            ?.get(IdeEditorService::class.java)

    private fun fileService(): IdeFileService? =
        PluginFragmentHelper.getServiceRegistry(SketchToUiPlugin.PLUGIN_ID)
            ?.get(IdeFileService::class.java)

    private fun projectService(): IdeProjectService? =
        PluginFragmentHelper.getServiceRegistry(SketchToUiPlugin.PLUGIN_ID)
            ?.get(IdeProjectService::class.java)

    private fun File.isLikelyLayoutXml(): Boolean =
        extension.equals("xml", ignoreCase = true) &&
            invariantSeparatorsPath.contains("/res/layout")

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(requireContext(), message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private data class PipelineOutput(
        val detectionCount: Int,
        val detections: List<DetectionResult>,
        val annotations: Map<String, String>,
        val generatedXml: GeneratedXml
    )

    private data class GeneratedXml(
        val layoutXml: String,
        val stringsXml: String
    )

    companion object {
        private const val TAG = "SketchToUiFragment"
        private const val TARGET_DP_WIDTH = 360
        private const val TARGET_DP_HEIGHT = 640
        private const val DEFAULT_LEFT_GUIDE_PCT = 0.2f
        private const val DEFAULT_RIGHT_GUIDE_PCT = 0.8f
    }
}
