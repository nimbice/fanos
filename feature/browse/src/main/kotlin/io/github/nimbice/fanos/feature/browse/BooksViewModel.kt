package io.github.nimbice.fanos.feature.browse

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.book.BookImporter
import io.github.nimbice.fanos.core.data.book.OpenedBook
import io.github.nimbice.fanos.core.data.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Book files being opened: the [index]th (from 1) of [count], called [name]. */
data class BookProgress(val index: Int, val count: Int, val name: String?)

/** How opening book files went. */
sealed interface BookResult {
    /** The one file opened. */
    data class Opened(val book: OpenedBook) : BookResult

    /** The one file couldn't be opened. */
    data class Failed(val message: String) : BookResult

    /** Several files: how many were added, were in the library already, and weren't books. */
    data class Several(val added: Int, val already: Int, val failed: Int) : BookResult
}

/** Books opened from files on the phone, from Browse: one file or several, one after another, into the library. */
@HiltViewModel
class BooksViewModel @Inject constructor(private val importer: BookImporter) : ViewModel() {
    private val _progress = MutableStateFlow<BookProgress?>(null)

    /** The files being opened, while they are. */
    val progress: StateFlow<BookProgress?> = _progress.asStateFlow()

    private val _results = Channel<BookResult>(Channel.BUFFERED)
    val results: Flow<BookResult> = _results.receiveAsFlow()

    fun open(uris: List<Uri>) {
        if (_progress.value != null || uris.isEmpty()) return
        _progress.value = BookProgress(1, uris.size, null)
        viewModelScope.launch {
            var added = 0
            var already = 0
            var failed = 0
            var last: Result<OpenedBook>? = null
            uris.forEachIndexed { index, uri ->
                _progress.value = BookProgress(index + 1, uris.size, withContext(Dispatchers.IO) { importer.nameOf(uri) })
                val result = suspendRunCatching { importer.open(uri) }
                result.onSuccess { if (it.already) already++ else added++ }.onFailure { failed++ }
                last = result
            }
            _progress.value = null
            val one = last
            _results.send(
                if (uris.size == 1 && one != null) {
                    one.fold({ BookResult.Opened(it) }, { BookResult.Failed(userMessage(it)) })
                } else {
                    BookResult.Several(added, already, failed)
                },
            )
        }
    }
}
