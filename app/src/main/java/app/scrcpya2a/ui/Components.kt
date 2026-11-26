package app.scrcpya2a.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrcpya2a.R

/** Fundo: degradê preto→violeta com dois brilhos suaves. */
fun Modifier.janusBackground(): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(JanusColors.Bg2, JanusColors.Bg)))
    val r1 = size.width * 0.95f
    val c1 = Offset(size.width * 0.95f, 0f)
    drawCircle(
        Brush.radialGradient(listOf(JanusColors.Violet.copy(alpha = 0.40f), Color.Transparent), center = c1, radius = r1),
        radius = r1, center = c1,
    )
    val r2 = size.width * 0.8f
    val c2 = Offset(0f, size.height * 0.85f)
    drawCircle(
        Brush.radialGradient(listOf(JanusColors.VioletDeep.copy(alpha = 0.45f), Color.Transparent), center = c2, radius = r2),
        radius = r2, center = c2,
    )
}

/** Logo da marca (ícone do app sobre o degradê roxo). */
@Composable
fun BrandLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF1B1340), JanusColors.Violet))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.7f),
        )
    }
}

/** Cartão em caixa: borda grossa, cantos arredondados e sombra dura deslocada. */
@Composable
fun BoxCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    padding: Dp = 16.dp,
    /** Para caixinhas lado a lado: todas ficam com a mesma altura (dentro de uma Row com IntrinsicSize.Max). */
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier.matchParentSize().offset(4.dp, 4.dp)
                .background(if (highlight) JanusColors.Violet else JanusColors.Shadow, shape),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .clip(shape)
                .background(JanusColors.Card, shape)
                .border(2.dp, if (highlight) JanusColors.VioletBright else JanusColors.Border, shape)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

enum class ButtonStyle { Primary, Outline }

/** Botão "gordo": afunda sobre a sombra quando pressionado. */
@Composable
fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Primary,
    height: Dp = 50.dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateDpAsState(if (pressed) 3.dp else 0.dp, label = "press")
    val shape = RoundedCornerShape(14.dp)
    val primary = style == ButtonStyle.Primary
    Box(modifier.alpha(if (enabled) 1f else 0.45f).padding(end = 3.dp, bottom = 3.dp)) {
        Box(
            Modifier.matchParentSize().offset(3.dp, 3.dp)
                .background(if (primary) JanusColors.VioletDeep else JanusColors.Shadow, shape),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .offset(press, press)
                .clip(shape)
                .background(if (primary) JanusColors.Violet else JanusColors.CardHigh, shape)
                .border(2.dp, if (primary) JanusColors.VioletBright else JanusColors.Lilac.copy(alpha = 0.5f), shape)
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (primary) Color.White else JanusColors.Lilac,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** Botão de escolha (resolução etc.). */
@Composable
fun ChunkChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) JanusColors.Violet else JanusColors.CardHigh, shape)
            .border(2.dp, if (selected) JanusColors.VioletBright else JanusColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
            color = if (selected) Color.White else JanusColors.Lilac,
        )
    }
}

/** Botão quadrado com três barrinhas (menu). */
@Composable
fun MenuButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.padding(end = 3.dp, bottom = 3.dp)) {
        Box(Modifier.matchParentSize().offset(3.dp, 3.dp).background(JanusColors.VioletDeep, shape))
        Box(
            Modifier
                .size(46.dp)
                .clip(shape)
                .background(JanusColors.Violet, shape)
                .border(2.dp, JanusColors.VioletBright, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val w = size.width
                val stroke = 3.dp.toPx()
                for (i in 0..2) {
                    val y = size.height * (0.15f + 0.35f * i)
                    drawLine(Color.White, Offset(0f, y), Offset(w, y), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}

/** Título de seção em letras pequenas, largas e coloridas. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 2.dp),
        fontWeight = FontWeight.Black,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        color = JanusColors.VioletBright,
    )
}

/** Pílula de estado, com uma bolinha colorida. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    }
}

/** Mensagem de progresso ou erro, no topo da tela. */
@Composable
fun StatusBanner(busy: Boolean, message: String?, error: String?) {
    val colors = MaterialTheme.colorScheme
    if (error != null) {
        val shape = RoundedCornerShape(16.dp)
        Text(
            error,
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.errorContainer, shape)
                .border(2.dp, colors.error, shape)
                .padding(14.dp),
            color = colors.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else if (busy || message != null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = JanusColors.VioletBright)
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SwitchRow(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = JanusColors.Violet, checkedThumbColor = Color.White,
                checkedBorderColor = JanusColors.VioletBright,
                uncheckedTrackColor = JanusColors.CardHigh, uncheckedBorderColor = JanusColors.Border,
            ),
        )
    }
}

/** Campo de texto no estilo do app (cantos redondos, borda violeta). */
@Composable
fun JanusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = TextStyle.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = keyboardOptions,
        textStyle = textStyle,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JanusColors.VioletBright,
            unfocusedBorderColor = Color(0xFF4B2C9A),
            focusedLabelColor = JanusColors.Lilac,
            cursorColor = JanusColors.VioletBright,
            focusedContainerColor = Color(0xFF0D0720),
            unfocusedContainerColor = Color(0xFF0D0720),
        ),
    )
}

/** Cartão que abre e fecha ao tocar no título (para ajuda e opções avançadas). */
@Composable
fun ExpandableCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BoxCard(padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(if (expanded) "▲" else "▼", color = JanusColors.VioletBright)
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
