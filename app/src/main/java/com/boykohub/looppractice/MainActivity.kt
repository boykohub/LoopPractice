package com.boykohub.looppractice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val vm: PracticeEngine get() = (application as LoopApp).engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val scheme = darkColorScheme(
                primary = Color(0xFF7FD1FF),
                onPrimary = Color(0xFF00243A),
                secondary = Color(0xFFFFC15E),
                background = Color(0xFF121316),
                surface = Color(0xFF121316),
                surfaceVariant = Color(0xFF22252B),
            )
            MaterialTheme(colorScheme = scheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { u -> if (u != null) vm.load(u) }
                    PlayerScreen(vm, onOpen = { picker.launch(arrayOf("audio/*", "video/*")) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.uiVisible = true
        // Starts the background-playback service (allowed here because the app is in front).
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        vm.uiVisible = false
        vm.saveState()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_VIEW -> i.data?.let { vm.load(it) }
            // "Share → Music Practice" from Files, Gallery, Drive, WhatsApp…
            Intent.ACTION_SEND -> {
                val u: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else
                    @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                (u ?: i.data)?.let { vm.load(it) }
            }
        }
    }
}
