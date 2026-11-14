package app.ljud

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LjudTheme {
                LjudApp(vm, onLogin = { vm.login(this) }, onPhoneSpeaker = { vm.setPhoneSpeaker(this, it) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground(this)
    }

    override fun onStop() {
        vm.foreground = false
        super.onStop()
    }
}

@Composable
fun LjudTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD8B5))
        else -> lightColorScheme(primary = Color(0xFF2E6B57))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
