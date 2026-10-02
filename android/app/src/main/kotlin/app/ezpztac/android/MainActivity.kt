package app.ezpztac.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ezpztac.designsystem.EzpzTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EzpzTheme {
                HomeScreen(version = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE)
            }
        }
    }
}
