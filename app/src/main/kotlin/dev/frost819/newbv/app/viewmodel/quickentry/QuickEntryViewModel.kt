package dev.frost819.newbv.app.viewmodel.quickentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页快捷收藏 ViewModel。
 *
 * 暴露已收藏入口的 key 集合供按钮判断状态，并代理收藏/取消操作。
 *
 * @param quickEntryRepository 快捷收藏仓库。
 */
@HiltViewModel
class QuickEntryViewModel
    @Inject
    constructor(
        private val quickEntryRepository: QuickEntryRepository,
    ) : ViewModel() {
        /** 已收藏入口的 key 集合。 */
        val savedKeys: StateFlow<Set<String>> =
            quickEntryRepository.entries
                .map { entries -> entries.map { it.key }.toSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        /** 收藏（saved = true）或取消收藏（saved = false）。 */
        fun setSaved(
            entry: QuickEntry,
            saved: Boolean,
        ) {
            viewModelScope.launch { quickEntryRepository.setSaved(entry, saved) }
        }
    }
