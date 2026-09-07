package org.appdevforall.codeonthego.layouteditor.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils.Companion.make

/**
 * Class for FilePicker. Works from either a Fragment (plugin editor) or an AppCompatActivity
 * (legacy sub-activities), registering its result launchers on the given caller.
 */
abstract class FilePicker private constructor(
  caller: ActivityResultCaller,
  private val activityProvider: () -> Activity,
  private val contextProvider: () -> Context,
) {
  constructor(fragment: Fragment) : this(
    fragment,
    { fragment.requireActivity() },
    { fragment.requireContext() },
  )

  constructor(activity: AppCompatActivity) : this(activity, { activity }, { activity })

  private val getFile: ActivityResultLauncher<String> =
    caller.registerForActivityResult(ActivityResultContracts.GetContent()) { onPickFile(it) }

  private val reqPermission: ActivityResultLauncher<String> =
    caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { onRequestPermission(it) }

  private fun contentRoot(): View = activityProvider().findViewById(android.R.id.content)

  private fun onRequestPermission(isGranted: Boolean) {
    if (isGranted) make(contentRoot(), R.string.permission_granted)
      .setSlideAnimation()
      .showAsSuccess()
    else make(contentRoot(), R.string.permission_denied)
      .setSlideAnimation()
      .showAsError()
  }

  /**
   * Abstract method called onPickFile, takes in a Nullable Uri as a parameter
   *
   * @param uri Nullable Uri
   */
  abstract fun onPickFile(uri: Uri?)

  /**
   * Method launch, takes in a String MIME type as a parameter
   */
  fun launch(mimeType: String) {
    checkPermissions(mimeType)
    getFile.launch(mimeType)
  }

  private fun checkPermissions(mimeType: String) {
    val isImageType =
      mimeType == "image/*" || mimeType == "image/png" || mimeType == "image/jpg" || mimeType == "image/jpeg"

    if (isImageType) {
      val ctx = contextProvider()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES)
          == PackageManager.PERMISSION_DENIED
        ) {
          reqPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
      } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_DENIED
      ) {
        reqPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
      }
    }
  }
}
