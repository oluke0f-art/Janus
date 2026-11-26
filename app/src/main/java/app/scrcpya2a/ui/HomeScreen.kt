package app.scrcpya2a.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrcpya2a.session.SessionConfig

private data class Preset(val label: String, val w: Int, val h: Int)

private val PRESETS = listOf(
    Preset("720p", 1280, 720),
    Preset("900p", 1600, 900),
    Preset("1080p", 1920, 1080),
    Preset("1440p", 2560, 1440),
    Preset("4K", 3840, 2160),
)
private const val CUSTOM = -1

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    // Lista de monitores atualizada sozinha enquanto esta tela está aberta (sem botão de buscar).
    LaunchedEffect(vm.currentDeviceId) { vm.pollDisplays() }

    var selected by remember { mutableIntStateOf(2) }
    var customW by remember { mutableStateOf("1920") }
    var customH by remember { mutableStateOf("1080") }
    var dpi by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    JanusPage(header = { BrandTitle("Seu celular como tela de outro") }) {
        // ------------------------------------------------------------- aparelhos
        DeviceSwitcher(vm)
        vm.currentDevice?.let { device ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusPill("Conectado · ${device.host}", JanusColors.Ok, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                ChunkyButton(
                    "Desconectar", onClick = { vm.disconnectDevice(device.id) }, style = ButtonStyle.Outline,
                    height = 38.dp, modifier = Modifier.size(width = 132.dp, height = 41.dp),
                )
            }
        }
        StatusBanner(vm.busy, vm.message, vm.error)

        // ---------------------------------------------------------------- monitores
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Monitores · ${vm.currentDevice?.name ?: ""}")
            val rows = vm.monitorRows
            rows.chunked(2).forEach { pair ->
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { row -> MonitorTile(row, vm, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (!vm.displaysLoaded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = JanusColors.VioletBright)
                    Text("Procurando monitores…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (rows.any { !it.active && !it.isMain }) {
                Text(
                    "“Sem dono” é uma sobra de sessão antiga ou criada por outro app. Fechar encerra os servidores scrcpy antigos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vm.displaysError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // ------------------------------------------------------------ novo monitor
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Novo monitor virtual")
            BoxCard {
                Text(
                    "Cria uma tela extra de verdade no outro aparelho, com a resolução escolhida. A tela física não muda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEachIndexed { i, p -> ChunkChip(p.label, selected == i) { selected = i } }
                    ChunkChip("Outra", selected == CUSTOM) { selected = CUSTOM }
                }
                if (selected != CUSTOM) {
                    Text(
                        "${PRESETS[selected].w} × ${PRESETS[selected].h}",
                        fontSize = 22.sp, fontWeight = FontWeight.Black, color = JanusColors.Lilac,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JanusTextField(
                            value = customW, onValueChange = { customW = it.filter(Char::isDigit) }, label = "Largura",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        JanusTextField(
                            value = customH, onValueChange = { customH = it.filter(Char::isDigit) }, label = "Altura",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                JanusTextField(
                    value = dpi, onValueChange = { dpi = it.filter(Char::isDigit) }, label = "DPI (opcional)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                ChunkyButton(
                    "Criar monitor virtual",
                    onClick = {
                        val (w, h) = if (selected == CUSTOM) {
                            (customW.toIntOrNull() ?: 0) to (customH.toIntOrNull() ?: 0)
                        } else PRESETS[selected].let { it.w to it.h }
                        val dpiValue = dpi.toIntOrNull()
                        if (w !in 200..8192 || h !in 200..8192) {
                            localError = "Use largura e altura entre 200 e 8192."
                        } else if (dpi.isNotEmpty() && (dpiValue == null || dpiValue <= 0)) {
                            localError = "DPI inválido."
                        } else {
                            localError = null
                            vm.startSession(SessionConfig.VirtualDisplay(w, h, dpiValue))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ------------------------------------------------------------------ opções
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Opções")
            BoxCard {
                SwitchRow(
                    "Transmitir áudio",
                    "Toca o som do outro aparelho neste (exige Android 11 ou superior lá).",
                    vm.audioEnabled, vm::updateAudioEnabled,
                )
                SwitchRow(
                    "Repassar controles de jogo",
                    "Gamepads ligados a este aparelho viram um gamepad no outro.",
                    vm.gamepadEnabled, vm::updateGamepadEnabled,
                )
                SwitchRow(
                    "Fechar monitores sem dono ao criar um novo",
                    "Encerra sobras antigas, sem mexer nos monitores que este app mantém abertos.",
                    vm.closeOldServers, vm::updateCloseOldServers,
                )
            }
        }
    }
}

/** Caixinha de um monitor: número (id), mini-tela na proporção real, resolução e botões. */
@Composable
private fun MonitorTile(row: MonitorRow, vm: MainViewModel, modifier: Modifier) {
    BoxCard(modifier, highlight = row.active, padding = 14.dp, fillHeight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (row.displayId >= 0) "#${row.displayId}" else "#?",
                Modifier.weight(1f),
                fontSize = 34.sp, fontWeight = FontWeight.Black, color = JanusColors.Lilac,
            )
            MiniScreen(row.width, row.height, row.active)
        }
        Text(row.title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (row.width > 0) "${row.width} × ${row.height}" else if (row.isMain) "Resolução nativa" else "Resolução…",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            row.detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.active) JanusColors.VioletBright else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        ChunkyButton(
            if (row.active) "Abrir" else if (row.isMain) "Espelhar" else "Conectar",
            onClick = { vm.openRow(row) },
            modifier = Modifier.fillMaxWidth(), height = 44.dp,
        )
        if (row.active || !row.isMain) {
            ChunkyButton(
                if (row.active && row.kind != app.scrcpya2a.session.SessionManager.Kind.Virtual) "Parar" else "Fechar",
                onClick = { vm.closeRow(row) },
                style = ButtonStyle.Outline,
                modifier = Modifier.fillMaxWidth(), height = 40.dp,
            )
        }
    }
}

/** Desenho da tela na proporção da resolução (celular em pé, monitor deitado). */
@Composable
private fun MiniScreen(width: Int, height: Int, active: Boolean) {
    val ratio = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.45f, 1.9f) else 16f / 9f
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .height(30.dp)
            .aspectRatio(ratio)
            .background(
                if (active) Brush.linearGradient(listOf(JanusColors.Violet, JanusColors.VioletBright))
                else SolidColor(JanusColors.CardHigh),
                shape,
            )
            .border(2.dp, if (active) JanusColors.VioletBright else JanusColors.Border, shape),
    )
}

/** Faixa com os aparelhos conectados: toque para trocar de aparelho; "+" liga mais um. */
@Composable
private fun DeviceSwitcher(vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Aparelhos")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            vm.devices.forEach { d ->
                val selected = d.id == vm.currentDeviceId
                val shape = RoundedCornerShape(16.dp)
                val count = vm.activeCount(d.id)
                Column(
                    Modifier
                        .clip(shape)
                        .background(if (selected) JanusColors.Violet else JanusColors.CardHigh, shape)
                        .border(2.dp, if (selected) JanusColors.VioletBright else JanusColors.Border, shape)
                        .clickable { vm.selectDevice(d.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        d.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                        color = if (selected) androidx.compose.ui.graphics.Color.White else JanusColors.Lilac,
                        maxLines = 1,
                    )
                    Text(
                        d.host + if (count > 0) " · $count ativo(s)" else "",
                        fontSize = 12.sp, color = JanusColors.Lilac.copy(alpha = 0.85f), maxLines = 1,
                    )
                }
            }
            val addShape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .clip(addShape)
                    .border(2.dp, JanusColors.Lilac.copy(alpha = 0.5f), addShape)
                    .clickable { vm.addDevice() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ Aparelho", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = JanusColors.Lilac)
            }
        }
    }
}
