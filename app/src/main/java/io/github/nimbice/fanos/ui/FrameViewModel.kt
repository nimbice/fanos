package io.github.nimbice.fanos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.repository.RecentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the app's frame shows besides the screens: the number on the Updates tab. */
@HiltViewModel
class FrameViewModel @Inject constructor(private val recent: RecentRepository) : ViewModel() {

    /** Chapters that came into library novels, unread, since the Updates tab was last looked at. */
    val unseenUpdates: StateFlow<Int> = recent.observeUnseenCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch { recent.startCountingUnseen() }
    }
}
