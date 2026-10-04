package com.jlees.budgey.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

private fun newCameraFile(context: Context): File {
    // Captures are copied into app storage right away and then deleted; this catches stragglers.
    com.jlees.budgey.data.TempFiles.pruneCamera(context)
    return File(com.jlees.budgey.data.TempFiles.cameraDir(context), "${UUID.randomUUID()}.jpg")
}

/**
 * One "Scan" entry point: opens Android's own chooser, offering the camera alongside
 * Photos / Files / any gallery app. Returns the picked or captured image (null if cancelled).
 *
 * Usage: `val pickImage = rememberImageSource { uri -> ... }` then call `pickImage()`.
 */
@Composable
fun rememberImageSource(title: String = "Scan", onResult: (Uri?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        // Some camera apps echo back our own EXTRA_OUTPUT uri as data: that's the capture, not a pick.
        val picked = res.data?.data?.takeIf { it.authority != "${context.packageName}.fileprovider" }
        val captured = cameraPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
        val uri = when {
            res.resultCode != Activity.RESULT_OK -> null
            picked != null -> picked
            captured != null -> Uri.fromFile(captured)
            else -> null
        }
        // Picked from Photos/Files or cancelled instead: drop whatever the camera may have started.
        if (captured != null && uri?.path != captured.path) captured.delete()
        callback(uri)
    }
    return {
        val file = newCameraFile(context)
        cameraPath = file.absolutePath
        val cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            clipData = ClipData.newRawUri("", cameraUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val gallery = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(gallery, title).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
        }
        runCatching { launcher.launch(chooser) }.onFailure { callback(null) }
    }
}
