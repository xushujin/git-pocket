package app.gitpocket.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GpScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.base)
            .statusBarsPadding()
            .navigationBarsPadding(),
        content = content,
    )
}

@Composable
fun GpTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            GpIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 8.dp)
        ) {
            Text(
                text = title,
                style = typography.title,
                color = palette.contentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = typography.caption,
                    color = palette.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

@Composable
fun GpIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val palette = LocalNeumorphicPalette.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) palette.contentPrimary else palette.contentSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun GpCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier.fillMaxWidth().neumorphicRaised(shape)) {
        Column(
            modifier = Modifier
                .clip(shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

@Composable
fun GpPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) palette.accent else palette.accent.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.bodyStrong,
            color = if (enabled) palette.onAccent else palette.onAccent.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

@Composable
fun GpTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = typography.bodyStrong,
            color = if (enabled) palette.accent else palette.contentSecondary.copy(alpha = 0.5f),
        )
    }
}

@Composable
fun GpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle? = null,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val style = textStyle ?: typography.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .neumorphicInset(RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = style.copy(color = palette.contentPrimary),
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = style,
                        color = palette.contentSecondary.copy(alpha = 0.7f),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
fun GpListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.body,
                color = palette.contentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = typography.caption,
                    color = palette.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun GpBadge(text: String, color: Color? = null) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background((color ?: palette.accent).copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = typography.caption,
            color = color ?: palette.accent,
            maxLines = 1,
        )
    }
}

@Composable
fun GpSectionHeader(text: String) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Text(
        text = text,
        style = typography.section,
        color = palette.contentSecondary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun GpDivider() {
    val palette = LocalNeumorphicPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.contentSecondary.copy(alpha = 0.12f))
    )
}

@Composable
fun GpEmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = typography.title, color = palette.contentPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = typography.body,
            color = palette.contentSecondary,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            GpPrimaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun GpLoading(text: String = "加载中…") {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = typography.body, color = palette.contentSecondary)
    }
}
