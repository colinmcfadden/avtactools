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
        if (savedInstanceState == null && isNews(intent)) {
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
        if (!isNews(intent)) return
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

/**
 * Whether [intent] brings something new. A task reopened from the recent apps after its activity was finished (Back, on Android 10 and 11;
 * a reboot, on any) is started again with the intent that first started it, flagged as coming from history: a link in it was already read,
 * and reading it again would accept an invitation twice (the server then answers that it was used) or offer a file again.
 */
internal fun isNews(intent: Intent?): Boolean = intent == null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
