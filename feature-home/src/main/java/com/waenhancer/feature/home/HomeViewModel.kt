package com.waenhancer.feature.home

import androidx.lifecycle.viewModelScope
import com.waenhancer.core.base.BaseViewModel
import com.waenhancer.domain.model.WaexTool
import com.waenhancer.domain.usecase.GetWaexToolsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeState(
    val activeToolsCount: Int = 0,
    val totalToolsCount: Int = 0
)

sealed interface HomeEvent {
    data object LoadStats : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    getWaexToolsUseCase: GetWaexToolsUseCase
) : BaseViewModel<HomeState, HomeEvent>(HomeState()) {

    val toolsStats: StateFlow<HomeState> = getWaexToolsUseCase()
        .map { tools ->
            HomeState(
                activeToolsCount = tools.count { it.isEnabled },
                totalToolsCount = tools.size
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HomeState()
        )

    override fun onEvent(event: HomeEvent) {
        // Handle events if any
    }
}
