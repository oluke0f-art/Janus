package app.scrcpya2a.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import app.scrcpya2a.adb.ConnectionHub
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // A notificação do serviço (que mantém os monitores ativos) só aparece se o usuário permitir.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sem ação: é opcional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            JanusTheme {
                // O pareamento por notificação conecta em segundo plano; aqui a interface adota essa conexão.
                val incoming = ConnectionHub.incoming
                LaunchedEffect(incoming) { incoming?.let { vm.onExternalConnected(it) } }
                Surface(Modifier.fillMaxSize()) {
                    when (vm.screen) {
                        Screen.Connect -> ConnectScreen(vm)
                        Screen.Home -> HomeScreen(vm)
                        Screen.Mirror -> MirrorScreen(vm)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.forwardGamepadKey(event)) return true
        if (vm.forwardHardwareKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Sticks, gatilhos e direcional (eixos) de controles de jogo. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (vm.forwardGamepadMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
