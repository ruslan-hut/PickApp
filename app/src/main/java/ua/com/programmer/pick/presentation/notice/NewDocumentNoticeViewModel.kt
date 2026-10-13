package ua.com.programmer.pick.presentation.notice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.util.NoticeAlert
import ua.com.programmer.pick.data.notice.NewDocumentNotifier
import ua.com.programmer.pick.domain.model.NewDocumentNotice
import javax.inject.Inject

/**
 * Activity-scoped state of the new-document banner: what is on screen now.
 * Notices arriving while the banner is up join it and restart its timer.
 */
@HiltViewModel
class NewDocumentNoticeViewModel @Inject constructor(
    notifier: NewDocumentNotifier,
    private val alert: NoticeAlert,
) : ViewModel() {

    private companion object {
        const val SHOW_MS = 8_000L
    }

    private val _shown = MutableStateFlow<List<NewDocumentNotice>>(emptyList())
    val shown: StateFlow<List<NewDocumentNotice>> = _shown.asStateFlow()

    private var hideJob: Job? = null

    init {
        viewModelScope.launch {
            notifier.notices.collect { show(it) }
        }
    }

    private fun show(documents: List<NewDocumentNotice>) {
        _shown.update { (it + documents).distinctBy(NewDocumentNotice::id) }
        alert.play()
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(SHOW_MS)
            _shown.value = emptyList()
        }
    }

    fun dismiss() {
        hideJob?.cancel()
        _shown.value = emptyList()
    }
}
