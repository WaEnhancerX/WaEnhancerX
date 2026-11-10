package com.waenhancer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.utils.UiText
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkErrorEvent(
    val title: String,
    val message: String,
    val retry: (() -> Unit)? = null
)

object NetworkErrorCenter {
    private val mutableEvent = MutableStateFlow<NetworkErrorEvent?>(null)
    val event = mutableEvent.asStateFlow()

    fun isNetworkError(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current is SSLException ||
                isNetworkError(current.message)
            ) return true
            current = current.cause
        }
        return false
    }

    fun isNetworkError(message: String?): Boolean {
        val value = message?.lowercase().orEmpty()
        return value.contains("unable to resolve host") ||
            value.contains("unknown host") ||
            value.contains("failed to connect") ||
            value.contains("connection refused") ||
            value.contains("connection reset") ||
            value.contains("network is unreachable") ||
            value.contains("no address associated with hostname") ||
            value.contains("timeout") ||
            value.contains("timed out") ||
            value.contains("socket") ||
            value.contains("ssl") ||
            value.contains("handshake")
    }

    fun show(error: Throwable?, retry: (() -> Unit)? = null) {
        show(message = error?.message, retry = retry)
    }

    fun show(message: String? = null, retry: (() -> Unit)? = null) {
        val timedOut = message?.contains("timeout", ignoreCase = true) == true ||
            message?.contains("timed out", ignoreCase = true) == true
        mutableEvent.value = NetworkErrorEvent(
            title = UiText.get(R.string.waex_ui_no_internet_connection, "No internet connection"),
            message = if (timedOut) {
                UiText.get(R.string.waex_ui_network_timeout_message, "The request took too long. Check your connection and try again.")
            } else {
                UiText.get(R.string.waex_ui_network_unreachable_message, "WAEX couldn't reach the service. Check your connection and try again.")
            },
            retry = retry
        )
    }

    fun dismiss() {
        mutableEvent.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalNetworkErrorHost() {
    val event by NetworkErrorCenter.event.collectAsState()
    val current = event ?: return
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    WaexModalBottomSheet(
        onDismissRequest = NetworkErrorCenter::dismiss,
        containerColor = colors.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = WaexIcons.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(28.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = current.title,
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = current.message,
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
            }
            Button(
                onClick = NetworkErrorCenter::dismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = WaexTheme.radius.fullShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(
                    text = UiText.get(R.string.waex_ui_close, "Close"),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
