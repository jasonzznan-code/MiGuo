package com.miguo.voice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置壳色板（已映射 MigoTokens；主路径色请用 MigoTokens）。 */
object ZenPalette {
    val Paper = Color(0xFFFFFFFF)
    val PaperSoft = Color(0xFFFAFBF8)
    val PaperCard = Color(0xFFFFFFFF)
    val Ink = Color(0xFF272D29)
    val Muted = Color(0xFF727B73)
    val Faint = Color(0xFFA3AAA4)
    val Moss = Color(0xFF526B50)
    val MossDeep = Color(0xFF526B50)
    val Seal = Color(0xFF9F392D)
    val Line = Color(0x1A272D29)
    val CardFill = Color(0xFFF2F3F1)
    val CardActive = Color(0xFFE8EFE3)
}

private val ZenFieldShape = RoundedCornerShape(12.dp)
private val ZenCardShape = RoundedCornerShape(14.dp)

@Composable
fun ZenSectionTitle(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            fontSize = 16.sp,
                        letterSpacing = 0.7.sp,
            color = ZenPalette.Ink,
        )
        if (eyebrow != null) {
            Text(
                eyebrow,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                color = Color(0xFFAAA292),
            )
        }
    }
}

@Composable
fun ZenHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = ZenPalette.Muted,
        modifier = modifier.padding(vertical = 2.dp),
    )
}

@Composable
fun ZenCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ZenCardShape)
            .border(1.dp, Color(0x22807968), ZenCardShape)
            .background(ZenPalette.CardFill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun ZenField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = placeholder?.let { { Text(it, fontSize = 13.sp, color = ZenPalette.Faint) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        shape = ZenFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ZenPalette.Moss,
            unfocusedBorderColor = Color(0x33807968),
            focusedLabelColor = ZenPalette.MossDeep,
            unfocusedLabelColor = ZenPalette.Muted,
            cursorColor = ZenPalette.MossDeep,
            focusedTextColor = ZenPalette.Ink,
            unfocusedTextColor = ZenPalette.Ink,
            focusedContainerColor = ZenPalette.PaperSoft,
            unfocusedContainerColor = ZenPalette.PaperSoft,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ZenOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val stroke = if (danger) ZenPalette.Seal.copy(alpha = 0.55f) else ZenPalette.Moss.copy(alpha = 0.45f)
    val fg = if (danger) ZenPalette.Seal else ZenPalette.MossDeep
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = fg,
            disabledContentColor = ZenPalette.Faint,
        ),
        border = BorderStroke(1.dp, if (enabled) stroke else ZenPalette.Line),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun ZenTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            contentColor = ZenPalette.MossDeep,
            disabledContentColor = ZenPalette.Faint,
        ),
        content = content,
    )
}

@Composable
fun ZenPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ZenPalette.MossDeep,
            contentColor = Color(0xFFFCFAF5),
            disabledContainerColor = ZenPalette.Line,
            disabledContentColor = ZenPalette.Faint,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun ZenSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = ZenPalette.Ink, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFCFAF5),
                checkedTrackColor = ZenPalette.Moss,
                uncheckedThumbColor = ZenPalette.Paper,
                uncheckedTrackColor = Color(0xFFD8D3C6),
                uncheckedBorderColor = Color(0x33807968),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ZenChoices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (v, label) ->
                    val active = v == selected
                    FilterChip(
                        selected = active,
                        onClick = { onSelect(v) },
                        label = {
                            Text(
                                label,
                                fontSize = 12.sp,
                                color = if (active) ZenPalette.MossDeep else ZenPalette.Ink,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ZenPalette.CardActive,
                            containerColor = ZenPalette.PaperSoft,
                            selectedLabelColor = ZenPalette.MossDeep,
                            labelColor = ZenPalette.Ink,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = active,
                            borderColor = Color(0x22807968),
                            selectedBorderColor = ZenPalette.Moss.copy(alpha = 0.55f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ZenTopBar(
    title: String,
    onDone: () -> Unit,
    doneLabel: String = "完成",
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            fontSize = 22.sp,
                        letterSpacing = 1.sp,
            color = ZenPalette.Ink,
            fontWeight = FontWeight.Normal,
        )
        ZenTextButton(onClick = onDone) {
            Text(doneLabel, fontSize = 14.sp, )
        }
    }
}


@Composable
fun ZenSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.sp, color = ZenPalette.Ink)
            Text(valueLabel, fontSize = 12.sp, color = ZenPalette.MossDeep, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = ZenPalette.MossDeep,
                activeTrackColor = ZenPalette.Moss,
                inactiveTrackColor = Color(0xFFD8D3C6),
            ),
        )
    }
}

@Composable
fun ZenFieldLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = ZenPalette.Ink,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
fun ZenHairline(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(ZenPalette.Line),
    )
}
