package app.ezpztac.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.ezpztac.android.incoming.IncomingIntake
import app.ezpztac.designsystem.EzpzTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val shell: AppViewModel by viewModels()

    @Inject lateinit var intake: IncomingIntake

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A link from an email opens the app on its screen; rotation and recreation do not replay it. Nor does a file: it is read once, when it arrives.
        if (savedInstanceState == null) {
            shell.onLink(intent?.dataString)
            receiveFiles(intent)
        }
        setContent {
            EzpzTheme {
                AppRoot(shell)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shell.onLink(intent.dataString)
        receiveFiles(intent)
    }

    /**
     * Files another app opened with this one (Files, a mail, the share sheet) are read now, while the sender's permission to read them lasts, and wait for the
     * person's answer: nothing is imported here. The intent is then replaced by a plain launch, so the file is not offered again if the activity is recreated.
     */
    private fun receiveFiles(received: Intent?) {
        if (!intake.carriesFiles(received)) return
        lifecycleScope.launch { intake.accept(received) }
        setIntent(Intent(Intent.ACTION_MAIN))
    }
}
