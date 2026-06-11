package com.waenhancer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.waenhancer.app.navigation.MainNavigation
import com.waenhancer.core.database.dao.WaexToolDao
import com.waenhancer.core.database.entity.WaexToolEntity
import com.waenhancer.core.ui.theme.WaEnhancerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var toolDao: WaexToolDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed mock tools to local database for demonstration
        lifecycleScope.launch {
            val currentTools = toolDao.getAllToolsFlow().first()
            if (currentTools.isEmpty()) {
                toolDao.insertTools(
                    listOf(
                        WaexToolEntity("1", "Invisible Mode", "Read messages without triggering read receipts.", false, "Privacy"),
                        WaexToolEntity("2", "Media Cleaner", "Automatically clear cache and junk media.", true, "Utility"),
                        WaexToolEntity("3", "Message Scheduler", "Schedule WhatsApp messages to be sent later.", false, "Automation"),
                        WaexToolEntity("4", "Chat Backup Pro", "Export chats to structured JSON format.", false, "Backup")
                    )
                )
            }
        }

        enableEdgeToEdge()
        setContent {
            WaEnhancerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }
}
