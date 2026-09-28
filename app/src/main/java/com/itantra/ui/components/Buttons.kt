package com.itantra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itantra.ui.theme.Palette

/** Main action (Join, Connect, Download): teal fill, navy text, 52 dp, no shadow. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Palette.NavyDeep,
            disabledContainerColor = Palette.Line,
            disabledContentColor = Palette.TextFaint,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 24.dp),
        content = content,
    )
}

/** Secondary action (Stop hosting, Cancel, Import): 1 dp outline, off-white text, 48 dp. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) Palette.Line else Palette.LineFaint),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Palette.OffWhite,
            disabledContentColor = Palette.TextFaint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
        content = content,
    )
}
