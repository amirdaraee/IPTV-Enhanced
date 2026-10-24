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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(placeholderBrush(title)), contentAlignment = Alignment.Center) {
        Text(
            initials(title),
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

private fun placeholderBrush(seed: String): Brush {
    val hues = listOf(
        Color(0xFF1E3A5F) to Color(0xFF0F1B2D), Color(0xFF3B1F4A) to Color(0xFF1A0F24),
        Color(0xFF1F4A3B) to Color(0xFF0F2419), Color(0xFF4A2F1F) to Color(0xFF24150F),
        Color(0xFF2A2F55) to Color(0xFF12142A),
    )
    val (a, b) = hues[(seed.hashCode() and 0x7fffffff) % hues.size]
    return Brush.linearGradient(listOf(a, b))
}

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
 * Remote-friendly text field. Focus lands on a read-only row; pressing OK starts
 * editing and opens the keyboard, and the keyboard's action key (or Back) ends
 * editing, so the D-pad never gets trapped inside the IME.
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
    var editorHadFocus by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val active = editing || focused
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) Palette.Accent else Palette.TextDim)
        Spacer(Modifier.height(6.dp))
        val shape = RoundedCornerShape(8.dp)
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(Palette.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    keyboard?.hide()
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(editorFocus)
                    .onFocusChanged {
                        // onFocusChanged also fires (unfocused) on attach; only a real focus loss ends editing.
                        if (it.isFocused) editorHadFocus = true
                        else if (editorHadFocus && editing) { editorHadFocus = false; editing = false }
                    }
                    .clip(shape)
                    .background(Palette.SurfaceFocus)
                    .border(BorderStroke(2.dp, Palette.Accent), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
            LaunchedEffect(Unit) {
                runCatching { editorFocus.requestFocus() }
                keyboard?.show()
            }
        } else {
            Surface(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().focusRequester(rowFocus).onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Palette.Surface,
                    focusedContainerColor = Palette.SurfaceFocus,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Palette.Outline), shape = shape),
                    focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = shape),
                ),
            ) {
                val shown = if (password) "•".repeat(value.length) else value
                Text(
                    shown.ifEmpty { placeholder },
                    color = if (value.isEmpty()) Palette.TextDim.copy(alpha = 0.6f) else Palette.Text,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
    // Return focus to the row after editing ends (but never steal it on first composition).
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) wasEditing = true
        else if (wasEditing) { wasEditing = false; runCatching { rowFocus.requestFocus() } }
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
