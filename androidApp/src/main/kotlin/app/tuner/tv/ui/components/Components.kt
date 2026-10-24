package app.tuner.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.tuner.tv.ui.theme.Palette
import coil3.compose.AsyncImage

private val Shape = RoundedCornerShape(10.dp)

/** Focusable surface with the app's focus treatment (lift, accent outline). */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = Shape,
    color: Color = Palette.Surface,
    focusedColor: Color = Palette.SurfaceFocus,
    scale: Float = 1.04f,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            focusedContainerColor = focusedColor,
            contentColor = Palette.Text,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = shape),
        ),
    ) { content() }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.SurfaceHigh,
            focusedContainerColor = Palette.Accent,
            contentColor = Palette.Text,
            focusedContentColor = Color(0xFF00201C),
            disabledContainerColor = Palette.Surface,
            disabledContentColor = Palette.TextDim,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = modifier)
}

@Composable
fun Dim(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Image with a generated fallback (initials over a gradient) for missing logos and posters. */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, fontSize: Int = 18) {
    val brush = remember(title) { placeholderBrush(title) }
    val initials = remember(title) { initials(title) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(brush), contentAlignment = Alignment.Center) {
        Text(
            initials,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun initials(s: String): String {
    val words = s.split(' ', '-', '_', '|', ':').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
    return when {
        words.isEmpty() -> "•"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private val PLACEHOLDERS = listOf(
    Color(0xFF1E3A5F) to Color(0xFF0F1B2D), Color(0xFF3B1F4A) to Color(0xFF1A0F24),
    Color(0xFF1F4A3B) to Color(0xFF0F2419), Color(0xFF4A2F1F) to Color(0xFF24150F),
    Color(0xFF2A2F55) to Color(0xFF12142A),
).map { (a, b) -> Brush.linearGradient(listOf(a, b)) }

private fun placeholderBrush(seed: String): Brush = PLACEHOLDERS[(seed.hashCode() and 0x7fffffff) % PLACEHOLDERS.size]

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent, height: Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color))
    }
}

@Composable
fun Badge(text: String, color: Color = Palette.AccentDim, textColor: Color = Palette.Accent) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textColor)
    }
}

/**
 * Remote-friendly text field. It stays read-only while focused, so the D-pad
 * moves past it; OK starts editing and opens the keyboard, and the keyboard's
 * action key (or Up/Down) ends editing. Focus never leaves the field when editing toggles,
 * which keeps the rest of the screen stable.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) } // ignore the release of an OK pressed elsewhere
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(8.dp)
    LaunchedEffect(editing) { if (editing) keyboard?.show() else keyboard?.hide() }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused) Palette.Accent else Palette.TextDim)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editing,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(if (editing) Palette.Accent else Color.Transparent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = {
                editing = false
                onDone()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) { editing = false; okDown = false }
                }
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    val direction = when (e.key) {
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> null
                    }
                    when {
                        // Not editing: arrows navigate instead of moving the text cursor.
                        !editing && direction != null -> { if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(direction); true }
                        !editing && ok && e.type == KeyEventType.KeyUp -> { if (okDown) editing = true; okDown = false; true }
                        !editing && ok -> { if (e.type == KeyEventType.KeyDown) okDown = true; true }
                        // Single-line field: Up/Down have no meaning while editing. Closing the
                        // keyboard with Back doesn't reach us, so leave editing and move on.
                        editing && (direction == FocusDirection.Up || direction == FocusDirection.Down) -> {
                            if (e.type == KeyEventType.KeyDown) { editing = false; focusManager.moveFocus(direction!!) }
                            true
                        }
                        editing && e.key == Key.Back && e.type == KeyEventType.KeyUp -> { editing = false; true }
                        else -> false
                    }
                }
                .clip(shape)
                .background(if (focused) Palette.SurfaceFocus else Palette.Surface)
                .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Palette.Accent else Palette.Outline), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = Palette.TextDim.copy(alpha = 0.6f), fontSize = 16.sp, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
fun LoadingDots(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Accent.copy(alpha = 0.4f + it * 0.2f))) }
    }
}
