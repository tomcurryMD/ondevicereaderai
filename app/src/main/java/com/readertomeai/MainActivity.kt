package com.readertomeai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.readertomeai.ui.navigation.NavGraph
import com.readertomeai.ui.navigation.Screen
import com.readertomeai.ui.theme.ReaderToMeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Track the book ID to navigate to after import from intent
    private var pendingBookNavigation: Long? = null
    private var navControllerReady: ((Long) -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result — no action needed, TTS works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // Handle incoming file intent
        handleIncomingIntent(intent)

        setContent {
            ReaderToMeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // Wire up pending navigation from intent
                    LaunchedEffect(Unit) {
                        navControllerReady = { bookId ->
                            navController.navigate(Screen.Reader.createRoute(bookId))
                        }
                        // If we already have a pending book, navigate now
                        pendingBookNavigation?.let { bookId ->
                            navController.navigate(Screen.Reader.createRoute(bookId))
                            pendingBookNavigation = null
                        }
                    }

                    NavGraph(navController = navController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Handle ACTION_VIEW intents — import the document and navigate to the reader.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri: Uri = intent.data ?: return

            // Take persistent read permission if available
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { /* Not all URIs support this */ }

            lifecycleScope.launch {
                val book = ReaderToMeApp.instance.bookRepository.importBook(uri)
                if (book != null) {
                    val navigate = navControllerReady
                    if (navigate != null) {
                        navigate(book.id)
                    } else {
                        pendingBookNavigation = book.id
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
