package app.scrcpya2a.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.scrcpya2a.session.ControlMessages
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val SENTINEL = "\u200B" // caractere invisível: permite detectar "apagar" num campo vazio

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MirrorScreen(vm: MainViewModel) {
    val activity = LocalContext.current.findActivity()
    val fullscreen = vm.fullscreen
    var keyboardOpen by remember { mutableStateOf(false) }
    var orientationMode by remember { mutableStateOf(0) } // 0 automático, 1 paisagem, 2 retrato
    var showHint by remember { mutableStateOf(false) }

    // Voltar: sai da tela cheia primeiro; só depois fecha o espelhamento.
    // Voltar: sai da tela cheia; depois volta à lista SEM encerrar o monitor.
    BackHandler { if (vm.fullscreen) vm.setFullscreen(false) else vm.closeViewer() }
    val viewing = vm.viewing

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.let {
                WindowInsetsControllerCompat(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(fullscreen) {
        val a = activity
        if (a != null) {
            val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (fullscreen) {
            showHint = true
            delay(5000)
            showHint = false
        } else {
            showHint = false
        }
    }
    LaunchedEffect(orientationMode) {
        activity?.requestedOrientation = when (orientationMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val rootModifier = Modifier.fillMaxSize().background(Color.Black)
    Column(if (fullscreen) rootModifier else rootModifier.safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = if (viewing != null && viewing.width > 0 && viewing.height > 0) viewing.width.toFloat() / viewing.height else 9f / 16f
            AndroidView(
                modifier = Modifier.aspectRatio(ratio),
                factory = { ctx ->
                    val exitDetector = FourFingerTapDetector()
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = vm.onSurfaceReady(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) = vm.onSurfaceDestroyed()
                        })
                        setOnTouchListener { v, ev ->
                            // 4 dedos × 3 toques: sai da tela cheia
                            if (exitDetector.onTouchEvent(ev) && vm.fullscreen) vm.setFullscreen(false)
                            vm.onTouch(ev, v.width, v.height)
                            true
                        }
                        // mouse físico: cursor (hover) e roda de rolagem
                        setOnGenericMotionListener { v, ev -> vm.onGenericMotion(ev, v.width, v.height) }
                    }
                },
            )
            // Cursor do touchpad: o Android não desenha o cursor de toques injetados, então o Janus desenha o seu.
            if (vm.touchpad && viewing != null && viewing.width > 0 && vm.cursorX >= 0f) {
                Canvas(Modifier.aspectRatio(ratio)) {
                    drawTouchpadCursor(
                        vm.cursorX / viewing.width * size.width,
                        vm.cursorY / viewing.height * size.height,
                    )
                }
            }
            if (viewing == null || !viewing.streaming) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    CircularProgressIndicator()
                    Text(viewing?.status ?: "Preparando…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (showHint) {
                Text(
                    "Toque 3 vezes com 4 dedos para sair da tela cheia",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (!fullscreen) {
            if (keyboardOpen) KeyboardPanel(vm)
            val status = buildList {
                viewing?.let { v ->
                    add(vm.deviceLabel(v.deviceId))
                    if (v.displayId >= 0) add("Monitor #${v.displayId}")
                    if (v.deviceName.isNotEmpty()) add(v.deviceName)
                }
                if (vm.touchpad) add("Touchpad ligado")
                if (vm.audioEnabled && !viewing?.audioState.isNullOrEmpty()) add(viewing?.audioState ?: "")
            }.joinToString(" · ")
            if (status.isNotEmpty()) {
                Text(
                    status,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF101010)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            ControlBar(
                vm = vm,
                onToggleKeyboard = { keyboardOpen = !keyboardOpen },
                onRotateApp = { orientationMode = (orientationMode + 1) % 3 },
            )
        }
    }
}

@Composable
private fun ControlBar(vm: MainViewModel, onToggleKeyboard: () -> Unit, onRotateApp: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavButton("◀", "Voltar", Modifier.weight(1f)) { vm.sendBack() }
        NavButton("●", "Início", Modifier.weight(1f)) { vm.sendKey(ControlMessages.KEYCODE_HOME) }
        NavButton("■", "Recentes", Modifier.weight(1f)) { vm.sendKey(ControlMessages.KEYCODE_APP_SWITCH) }
        NavButton("Aa", "Teclado", Modifier.weight(1f)) { onToggleKeyboard() }
        Box(Modifier.weight(1f)) {
            NavButton("•••", "Mais", Modifier.fillMaxWidth()) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem(if (vm.touchpad) "Touchpad: ligado" else "Touchpad: desligado") {
                    vm.toggleTouchpad()
                    menuOpen = false
                }
                if (vm.audioEnabled) {
                    MenuItem(if (vm.muted) "Ativar som" else "Silenciar") {
                        vm.toggleMute()
                        menuOpen = false
                    }
                }
                MenuItem("Tela cheia") {
                    vm.setFullscreen(true)
                    menuOpen = false
                }
                MenuItem("Girar este aparelho") {
                    onRotateApp()
                    menuOpen = false
                }
                MenuItem("Girar o outro aparelho") {
                    vm.rotateSource()
                    menuOpen = false
                }
                // outros monitores ativos (de qualquer aparelho): troca sem sair da tela de espelhamento
                vm.sessions.filter { it.id != vm.viewingId }.forEach { other ->
                    MenuItem(
                        "Ir para: ${vm.deviceLabel(other.deviceId)} · " +
                            (if (other.displayId >= 0) "#${other.displayId}" else "monitor") +
                            (if (other.width > 0) " ${other.width}×${other.height}" else ""),
                    ) {
                        menuOpen = false
                        vm.switchViewing(other.id)
                    }
                }
                MenuItem("Voltar à lista (mantém o monitor ativo)") {
                    menuOpen = false
                    vm.closeViewer()
                }
                MenuItem("Fechar este monitor") {
                    menuOpen = false
                    vm.closeViewedMonitor()
                }
            }
        }
    }
}

@Composable
private fun NavButton(glyph: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = JanusColors.Lilac),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = JanusColors.Lilac),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun KeyboardPanel(vm: MainViewModel) {
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    fun reset() {
        field = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { new ->
                when {
                    new.text.isEmpty() -> vm.sendKey(ControlMessages.KEYCODE_DEL)
                    new.text != SENTINEL -> vm.sendText(new.text.replace(SENTINEL, ""))
                }
                reset()
            },
            label = { Text("Digite aqui (enviado ao outro aparelho)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            // O tipo Password desliga sugestões/composição do teclado, que enviariam texto duplicado.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { vm.sendKey(ControlMessages.KEYCODE_ENTER) }),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            BarButton("Enter") { vm.sendKey(ControlMessages.KEYCODE_ENTER) }
            BarButton("Apagar") { vm.sendKey(ControlMessages.KEYCODE_DEL) }
            BarButton("Tab") { vm.sendKey(ControlMessages.KEYCODE_TAB) }
            BarButton("Esc") { vm.sendKey(ControlMessages.KEYCODE_ESCAPE) }
            BarButton("←") { vm.sendKey(ControlMessages.KEYCODE_DPAD_LEFT) }
            BarButton("↑") { vm.sendKey(ControlMessages.KEYCODE_DPAD_UP) }
            BarButton("↓") { vm.sendKey(ControlMessages.KEYCODE_DPAD_DOWN) }
            BarButton("→") { vm.sendKey(ControlMessages.KEYCODE_DPAD_RIGHT) }
            BarButton("Vol +") { vm.sendKey(ControlMessages.KEYCODE_VOLUME_UP) }
            BarButton("Vol −") { vm.sendKey(ControlMessages.KEYCODE_VOLUME_DOWN) }
            BarButton("Energia") { vm.sendKey(ControlMessages.KEYCODE_POWER) }
        }
    }
}


/** Seta de mouse branca com contorno preto, com a ponta em ([x], [y]). */
private fun DrawScope.drawTouchpadCursor(x: Float, y: Float) {
    val u = density * 1.25f
    val path = Path().apply {
        moveTo(x, y)
        lineTo(x, y + 17f * u)
        lineTo(x + 4.5f * u, y + 13f * u)
        lineTo(x + 7.5f * u, y + 20f * u)
        lineTo(x + 10f * u, y + 19f * u)
        lineTo(x + 7f * u, y + 12.5f * u)
        lineTo(x + 12.5f * u, y + 12.5f * u)
        close()
    }
    drawPath(path, Color.White)
    drawPath(path, Color.Black, style = Stroke(width = 1.6f * density))
}
