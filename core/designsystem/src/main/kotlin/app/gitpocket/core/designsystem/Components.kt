package app.gitpocket.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 页面骨架：状态栏 / 导航栏 / 输入法三处 insets 统一在这里处理。
 *
 * `enableEdgeToEdge()` 之后系统不再帮我们 resize 窗口（清单里的 adjustResize 对
 * edge-to-edge 无效），IME 的高度只能靠 [imePadding] 自己避让；漏了它，页面底部的
 * 输入框（提交信息、分支名、token）就会被键盘直接盖住。
 */
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
            .navigationBarsPadding()
            .imePadding(),
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
    isPassword: Boolean = false,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val style = textStyle ?: typography.body

    // 本地缓冲：输入时立刻反映到界面，不等外部状态回写。
    // 外部值（DataStore → Flow → 重组）通常比输入慢一拍，若直接绑定字符串，
    // 迟到的旧值会把光标重置到开头（"输入第二个字光标跑到第一个字前面"）。
    var buffer by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused && value != buffer.text) {
            buffer = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    BasicTextField(
        value = buffer,
        onValueChange = { newValue ->
            buffer = newValue
            onValueChange(newValue.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .neumorphicInset(RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = style.copy(color = palette.contentPrimary),
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = SolidColor(palette.accent),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
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

/**
 * 进度条（下载这类"有明确总量"的场景用）。
 *
 * [progress] 取 0f..1f；不传时按"不确定进度"渲染一条低透明度的底条。
 * 填充用主题强调色，轨道用表面色 —— 与拟态卡片的层次一致，不引入 Material 的滑块。
 */
@Composable
fun GpProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    val palette = LocalNeumorphicPalette.current
    val clamped = progress.coerceIn(0f, 1f)
    // 进度跳变时补一点动画，避免每 1% 抖一下
    val animated by animateFloatAsState(targetValue = clamped, label = "progress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(palette.darkShadow.copy(alpha = 0.35f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(height / 2))
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.accent.copy(alpha = 0.75f), palette.accent),
                    )
                ),
        )
    }
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

/** 主操作悬浮按钮：带文字的扩展 FAB，比纯图标更容易被发现。 */
@Composable
fun GpExtendedFab(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = modifier
            .height(52.dp)
            .neumorphicRaised(RoundedCornerShape(26.dp), depth = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = typography.bodyStrong, color = palette.accent)
    }
}

/**
 * 通用弹窗（替代 Material3 AlertDialog）。
 *
 * 为什么不用 AlertDialog：弹窗是**独立窗口**，edge-to-edge 下既不会被系统 resize，
 * Compose 也不会替它避让 IME —— 于是带输入框的弹窗（新建分支）一弹键盘，输入框就被盖住。
 * 这里用全屏 Dialog + [safeDrawingPadding]：键盘弹起时可用区域变小，卡片整体上移。
 *
 * 布局约定：整张卡片（标题 / 内容 / 按钮）放在同一个滚动容器里，高度上限取"键盘弹起后的
 * 可见高度"（BoxWithConstraints.maxHeight）。不用 weight —— 只要 Column 里有 weight，
 * Column 就会撑满父级最大高度，内容短的时候底部会留一大块空白。
 */
@Composable
fun GpDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmButton: (@Composable RowScope.() -> Unit)? = null,
    dismissButton: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val shape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // 状态栏 / 导航栏 / 输入法一起避让：键盘弹起时这块区域自动变矮
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 键盘弹起后剩下的可见高度：卡片最多长到这么高，再高就内部滚动
            val cardMaxHeight = maxHeight
            Box(modifier = modifier.fillMaxWidth().neumorphicRaised(shape, depth = 10.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = cardMaxHeight)
                        .clip(shape)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text(title, style = typography.title, color = palette.contentPrimary)
                    Spacer(Modifier.height(12.dp))
                    content()
                    if (confirmButton != null || dismissButton != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissButton?.invoke(this)
                            confirmButton?.invoke(this)
                        }
                    }
                }
            }
        }
    }
}

/** 通用二次确认弹窗（配色跟随主题）。 */
@Composable
fun GpConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    GpDialog(
        title = title,
        onDismissRequest = onCancel,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmText, style = typography.bodyStrong, color = palette.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(dismissText, style = typography.body, color = palette.danger)
            }
        },
    ) {
        Text(message, style = typography.body, color = palette.contentSecondary)
    }
}
