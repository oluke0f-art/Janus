package app.scrcpya2a.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Links do criador (aparecem no menu). */
private data class SocialLink(val badge: String, val name: String, val handle: String, val url: String)

private val SOCIALS = listOf(
    SocialLink("GH", "GitHub", "oluke0f-art", "https://github.com/oluke0f-art"),
    SocialLink("YT", "YouTube", "@OLUKE.F", "https://www.youtube.com/@OLUKE.F"),
    SocialLink("TT", "TikTok", "@oluke28", "https://www.tiktok.com/@oluke28"),
    SocialLink("IG", "Instagram", "@oluke.skull", "https://www.instagram.com/oluke.skull"),
)

private const val LIVEPIX_URL = "https://livepix.gg/oluke"

/**
 * Moldura das telas de conexão e de monitores: fundo, cabeçalho com botão de menu (três barrinhas),
 * conteúdo rolável e, no fim da rolagem, o rodapé "Me pague um café".
 *
 * O conteúdo tem altura mínima igual à da tela, então o rodapé fica sempre abaixo da dobra e só
 * aparece quando o usuário arrasta até o final.
 */
@Composable
fun JanusPage(
    header: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().janusBackground().safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = viewport).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        header()
                        MenuButton(onClick = { menuOpen = true })
                    }
                    content()
                }
                CoffeeFooter()
            }
        }
    }
    if (menuOpen) MenuDialog(onDismiss = { menuOpen = false })
}

/** Título da marca para o cabeçalho. */
@Composable
fun RowScope.BrandTitle(subtitle: String) {
    BrandLogo(48.dp)
    Column(Modifier.weight(1f)) {
        Text(
            "JANUS",
            fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
            color = Color.White,
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun safeOpen(open: (String) -> Unit, url: String) {
    try {
        open(url)
    } catch (_: Exception) {
        // sem navegador/app para abrir o link: ignora
    }
}

@Composable
private fun MenuDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            BoxCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "MENU", Modifier.weight(1f),
                            fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, color = Color.White,
                        )
                        ChunkyButton("Fechar", onClick = onDismiss, style = ButtonStyle.Outline, height = 40.dp, modifier = Modifier.size(width = 104.dp, height = 43.dp))
                    }
                    Text(
                        "Janus é um app de código aberto (GPL-3.0) baseado no scrcpy. Use um celular como tela e controle de outro.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionLabel("Siga o criador")
                    SOCIALS.chunked(2).forEach { pair ->
                        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { s ->
                                SocialTile(s, Modifier.weight(1f)) { safeOpen(uri::openUri, s.url) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SocialTile(link: SocialLink, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(JanusColors.CardHigh, shape)
            .border(2.dp, JanusColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(JanusColors.Violet)
                .border(2.dp, JanusColors.VioletBright, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(link.badge, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.White, letterSpacing = 1.sp)
        }
        Text(link.name, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text(link.handle, style = MaterialTheme.typography.bodySmall, color = JanusColors.Lilac, maxLines = 1)
    }
}

/** Barra "Me pague um café" no fim da tela (só aparece ao rolar até o final). */
@Composable
private fun CoffeeFooter() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(JanusColors.VioletBright))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(JanusColors.Violet, JanusColors.VioletDeep)))
                .clickable { safeOpen(uri::openUri, LIVEPIX_URL) }
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("☕🥐", fontSize = 34.sp)
            Text(
                "Me pague um café",
                fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center,
            )
            Text(
                "Apoie o Janus pelo LivePix · livepix.gg/oluke",
                style = MaterialTheme.typography.bodySmall, color = JanusColors.Lilac, textAlign = TextAlign.Center,
            )
        }
    }
}
