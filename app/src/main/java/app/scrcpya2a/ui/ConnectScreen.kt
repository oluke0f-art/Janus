package app.scrcpya2a.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.scrcpya2a.adb.PairingService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectScreen(vm: MainViewModel) {
    // Procura aparelhos na rede enquanto esta tela estiver aberta.
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    val pairingFirst = vm.pairingHighlight || vm.pairingServices.isNotEmpty()

    val context = LocalContext.current
    val startNotificationPairing = {
        PairingService.start(context)
        vm.reportInfo(
            "Notificação de pareamento ativa. Vá às Configurações (Depuração sem fio → Parear com código), puxe a barra de notificações e digite o código lá.",
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startNotificationPairing() else vm.reportError("Permita as notificações do Janus para digitar o código pela notificação.")
    }
    val onNotificationPairing = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startNotificationPairing()
        }
    }

    JanusPage(header = { BrandTitle("Seu celular como tela de outro") }) {
        if (vm.connected) {
            ChunkyButton(
                "← Voltar aos aparelhos conectados (${vm.devices.size})",
                onClick = vm::cancelAdd, style = ButtonStyle.Outline, modifier = Modifier.fillMaxWidth(),
            )
        }
        StatusBanner(vm.busy, vm.message, vm.error)

        if (pairingFirst) PairingSection(vm, onNotificationPairing)
        DevicesSection(vm)
        if (!pairingFirst) PairingSection(vm, onNotificationPairing)

        ExpandableCard("Como ativar no outro aparelho") {
            Text(
                "1. Ative as Opções do desenvolvedor (toque 7 vezes em “Número da versão”).\n" +
                    "2. Ative a Depuração sem fio, com os dois aparelhos na mesma rede Wi-Fi.\n" +
                    "3. Primeira vez: toque em “Parear dispositivo com código de pareamento” e deixe a janela aberta; " +
                    "o Janus detecta sozinho, só falta digitar o código. Se a janela fechar ao vir para o Janus, use “Parear pela notificação”.\n" +
                    "4. Depois do pareamento o aparelho aparece em “Aparelhos encontrados”.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        ExpandableCard("Conexão manual (avançado)") {
            Text(
                "Use se a busca automática não encontrar o aparelho (algumas redes bloqueiam a descoberta).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            JanusTextField(
                value = vm.host, onValueChange = { vm.host = it }, label = "IP do outro aparelho",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            JanusTextField(
                value = vm.connectPort, onValueChange = { vm.connectPort = it.filter(Char::isDigit) },
                label = "Porta de conexão (tela principal da Depuração sem fio)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            ChunkyButton("Conectar", onClick = vm::connect, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DevicesSection(vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Aparelhos encontrados")
        if (vm.discoveredDevices.isEmpty()) {
            BoxCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vm.discovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JanusColors.VioletBright)
                    Text(
                        "Procurando na rede Wi-Fi… Ative a Depuração sem fio no outro aparelho.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // caixinhas em duas colunas
        vm.discoveredDevices.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { d ->
                    BoxCard(Modifier.weight(1f), highlight = d.host == vm.host.trim(), fillHeight = true) {
                        Text("${d.host}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Text(
                            "porta ${d.port}" + when {
                                vm.devices.any { it.id == d.host } -> " · conectado"
                                d.host == vm.host.trim() -> " · último usado"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        val already = vm.devices.any { it.id == d.host }
                        ChunkyButton(
                            if (already) "Abrir" else "Conectar",
                            onClick = { if (already) vm.selectDevice(d.host) else vm.connectTo(d) },
                            enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), height = 44.dp,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Pareamento sempre visível: sem precisar abrir nenhum modo manual. */
@Composable
private fun PairingSection(vm: MainViewModel, onNotificationPairing: () -> Unit) {
    val detected = vm.pairingServices.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Primeira vez? Pareie")
        BoxCard(highlight = vm.pairingHighlight || detected != null) {
            Text(
                "No outro aparelho, abra Depuração sem fio → “Parear dispositivo com código de pareamento” " +
                    "e deixe a janela aberta. Depois digite aqui o código de 6 dígitos.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (detected != null) {
                StatusPill("Janela detectada · ${detected.host}:${detected.port}", JanusColors.Ok)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = JanusColors.VioletBright)
                    Text(
                        "Aguardando a janela de pareamento…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            JanusTextField(
                value = vm.pairCode,
                onValueChange = { vm.pairCode = it.filter(Char::isDigit).take(6) },
                label = "Código de pareamento",
                textStyle = LocalTextStyle.current.copy(fontSize = 26.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            if (detected == null) {
                JanusTextField(
                    value = vm.manualPairTarget,
                    onValueChange = { vm.manualPairTarget = it },
                    label = "Só se não for detectada: IP:porta da janela",
                    placeholder = "192.168.0.20:37123",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            ChunkyButton(
                "Parear e conectar",
                onClick = vm::pairWithCode,
                enabled = !vm.busy && vm.pairCode.length == 6 && (detected != null || vm.manualPairTarget.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "A janela de pareamento fecha quando você sai das Configurações. Se ela sumir ao voltar para cá, " +
                    "pareie pela notificação: o Janus mostra um campo para digitar o código sem sair de lá.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChunkyButton(
                "Parear pela notificação",
                onClick = onNotificationPairing,
                style = ButtonStyle.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
