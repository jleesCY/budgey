package com.jlees.budgey.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.height
import java.io.File

/** Thumbnail + attach/remove controls for a receipt or screenshot image. */
@Composable
fun ReceiptSection(
    file: File?,
    onAttach: (android.net.Uri) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onAttach(uri)
    }
    if (file == null) {
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.AddPhotoAlternate, null)
            Spacer(Modifier.width(8.dp))
            Text("Attach receipt or screenshot")
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = file,
                contentDescription = "Receipt",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { viewing = true },
            )
            Spacer(Modifier.width(12.dp))
            Text("Receipt attached", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove receipt") }
        }
    }
    if (viewing && file != null) ReceiptViewer(file) { viewing = false }
}

/** Read-only receipt: a wide thumbnail that opens full screen. */
@Composable
fun ReceiptPreview(file: File, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf(false) }
    AsyncImage(
        model = file,
        contentDescription = "Receipt — tap to view",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable { viewing = true },
    )
    if (viewing) ReceiptViewer(file) { viewing = false }
}

@Composable
private fun ReceiptViewer(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(model = file, contentDescription = "Receipt", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            FilledTonalIconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            ) { Icon(Icons.Rounded.Close, "Close") }
        }
    }
}
