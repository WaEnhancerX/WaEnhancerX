package com.waenhancer.feature.tools

import androidx.lifecycle.viewModelScope
import com.waenhancer.core.base.BaseViewModel
import com.waenhancer.domain.model.WaexTool
import com.waenhancer.domain.usecase.GetWaexToolsUseCase
import com.waenhancer.domain.usecase.ToggleWaexToolUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolsState(
    val isLoading: Boolean = false,
    val tools: List<WaexTool> = emptyList()
)

sealed interface ToolsEvent {
    data class ToggleTool(val id: String, val isEnabled: Boolean) : ToolsEvent
}

@HiltViewModel
class ToolsViewModel @Inject constructor(
    getWaexToolsUseCase: GetWaexToolsUseCase,
    private val toggleWaexToolUseCase: ToggleWaexToolUseCase
) : BaseViewModel<ToolsState, ToolsEvent>(ToolsState()) {

    val uiState: StateFlow<ToolsState> = getWaexToolsUseCase()
        .map { toolsList ->
            ToolsState(
                isLoading = false,
                tools = toolsList
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ToolsState(isLoading = true)
        )

    override fun onEvent(event: ToolsEvent) {
        when (event) {
            is ToolsEvent.ToggleTool -> {
                viewModelScope.launch {
                    toggleWaexToolUseCase(event.id, event.isEnabled)
                }
            }
        }
    }
}
