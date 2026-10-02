package app.ezpztac.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.ezpztac.designsystem.EzpzTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val shell: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A link from an email opens the app on its screen; rotation and recreation do not replay it.
        if (savedInstanceState == null) shell.onLink(intent?.dataString)
        setContent {
            EzpzTheme {
                AppRoot(shell)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shell.onLink(intent.dataString)
    }
}
