package io.github.nimbice.fanos.core.designsystem.component

import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext

/**
 * Asks before books opened from files leave the library: their text and pictures go from the phone with them, and
 * come back when the files are opened again. [title] names the one book, or is null for several (of [novels] removed,
 * [books] are books); [size] is what they take, worked out while the question shows.
 */
@Composable
fun RemoveBooksDialog(title: String?, novels: Int, books: Int, size: suspend () -> Long, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bytes by produceState<Long?>(null) { value = size() }
    val space = bytes?.let { " (${Formatter.formatShortFileSize(context, it)})" }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (title != null) "Remove this book?" else "Remove ${countOf(novels, "novel")}?") },
        text = {
            val what =
                when {
                    title != null -> "“$title” came from a file. Its text and pictures are"
                    books == novels -> "They came from files. Their text and pictures are"
                    books == 1 -> "One of them came from a file. Its text and pictures are"
                    else -> "$books of them came from files. Their text and pictures are"
                }
            Text(
                "$what deleted from this device to free the space they take$space.\n\n" +
                    "Your place, bookmarks and highlights are kept: open the file again from Browse › On this device and it’s all back.",
            )
        },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
