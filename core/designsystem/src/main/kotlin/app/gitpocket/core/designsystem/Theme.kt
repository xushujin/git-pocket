package app.gitpocket.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.gitpocket.core.model.ThemeMode
import app.gitpocket.core.model.ThemeSettings

/**
 * 拟态配色。浅色靠"左上高光 + 右下暗影"制造凸起，
 * 深色靠"顶部高光线 + 内阴影"表达层级（深色下投影物理上不成立）。
 */
@Immutable
data class NeumorphicPalette(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    val base: Color,
    val surface: Color,
    val lightShadow: Color,
    val darkShadow: Color,
    val contentPrimary: Color,
    val contentSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val success: Color,
    val diffAddBg: Color,
    val diffAddFg: Color,
    val diffDelBg: Color,
    val diffDelFg: Color,
)

object GitPocketPalettes {

    val mist = NeumorphicPalette(
        id = "mist", displayName = "薄雾", isDark = false,
        base = Color(0xFFE8ECF2), surface = Color(0xFFEFF3F9),
        lightShadow = Color(0xFFFFFFFF), darkShadow = Color(0xFFBFC8D6),
        contentPrimary = Color(0xFF2A3140), contentSecondary = Color(0xFF6B7686),
        accent = Color(0xFF4C6FFF), onAccent = Color(0xFFFFFFFF),
        danger = Color(0xFFD64545), success = Color(0xFF2E9E6B),
        diffAddBg = Color(0xFFE1F3E8), diffAddFg = Color(0xFF1E6B45),
        diffDelBg = Color(0xFFFBE6E6), diffDelFg = Color(0xFF8F2F2F),
    )

    val paper = NeumorphicPalette(
        id = "paper", displayName = "纸张", isDark = false,
        base = Color(0xFFF1ECE3), surface = Color(0xFFF8F4EC),
        lightShadow = Color(0xFFFFFFFF), darkShadow = Color(0xFFD3C9B8),
        contentPrimary = Color(0xFF3A342B), contentSecondary = Color(0xFF7A7062),
        accent = Color(0xFFB4763A), onAccent = Color(0xFFFFFFFF),
        danger = Color(0xFFC2453F), success = Color(0xFF4F7D4A),
        diffAddBg = Color(0xFFE9F0E3), diffAddFg = Color(0xFF3C5F35),
        diffDelBg = Color(0xFFF7E5DF), diffDelFg = Color(0xFF8A3A2C),
    )

    val moss = NeumorphicPalette(
        id = "moss", displayName = "苔原", isDark = false,
        base = Color(0xFFE6EDE4), surface = Color(0xFFEFF4EC),
        lightShadow = Color(0xFFFDFFFB), darkShadow = Color(0xFFBFCBB9),
        contentPrimary = Color(0xFF2C352A), contentSecondary = Color(0xFF68755F),
        accent = Color(0xFF4A7C59), onAccent = Color(0xFFFFFFFF),
        danger = Color(0xFFC0504A), success = Color(0xFF3E7A4F),
        diffAddBg = Color(0xFFDDEFE1), diffAddFg = Color(0xFF235B38),
        diffDelBg = Color(0xFFF6E4E2), diffDelFg = Color(0xFF83352F),
    )

    val graphite = NeumorphicPalette(
        id = "graphite", displayName = "石墨", isDark = true,
        base = Color(0xFF23262B), surface = Color(0xFF2A2E34),
        lightShadow = Color(0xFF3B414A), darkShadow = Color(0xFF14161A),
        contentPrimary = Color(0xFFE7EAF0), contentSecondary = Color(0xFF9AA3AF),
        accent = Color(0xFF7A93FF), onAccent = Color(0xFF10131A),
        danger = Color(0xFFFF6B6B), success = Color(0xFF4CC38A),
        diffAddBg = Color(0xFF1E3329), diffAddFg = Color(0xFF7FD8A8),
        diffDelBg = Color(0xFF3A2226), diffDelFg = Color(0xFFFF9B9B),
    )

    val midnight = NeumorphicPalette(
        id = "midnight", displayName = "午夜", isDark = true,
        base = Color(0xFF161A24), surface = Color(0xFF1D2230),
        lightShadow = Color(0xFF2B3344), darkShadow = Color(0xFF0A0D13),
        contentPrimary = Color(0xFFDDE3F0), contentSecondary = Color(0xFF8D97AC),
        accent = Color(0xFF6FA8FF), onAccent = Color(0xFF0A0F18),
        danger = Color(0xFFFF7A7A), success = Color(0xFF57C99A),
        diffAddBg = Color(0xFF172B33), diffAddFg = Color(0xFF7FD0D8),
        diffDelBg = Color(0xFF33202A), diffDelFg = Color(0xFFFF9DB0),
    )

    val ocean = NeumorphicPalette(
        id = "ocean", displayName = "深海", isDark = true,
        base = Color(0xFF14211F), surface = Color(0xFF1A2A27),
        lightShadow = Color(0xFF27403A), darkShadow = Color(0xFF081210),
        contentPrimary = Color(0xFFDCEAE5), contentSecondary = Color(0xFF8AA79E),
        accent = Color(0xFF4FD1B5), onAccent = Color(0xFF06120F),
        danger = Color(0xFFFF8080), success = Color(0xFF5BD6A8),
        diffAddBg = Color(0xFF17332B), diffAddFg = Color(0xFF8CE3C0),
        diffDelBg = Color(0xFF33211F), diffDelFg = Color(0xFFFFA79B),
    )

    val all: List<NeumorphicPalette> = listOf(mist, paper, moss, graphite, midnight, ocean)
    val light: List<NeumorphicPalette> = all.filter { !it.isDark }
    val dark: List<NeumorphicPalette> = all.filter { it.isDark }

    fun byId(id: String): NeumorphicPalette = all.firstOrNull { it.id == id } ?: mist
}

@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

@Immutable
data class GitPocketTypography(
    val display: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    val title: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    val section: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val bodyStrong: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
)

val LocalNeumorphicPalette = staticCompositionLocalOf { GitPocketPalettes.mist }
val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalGitPocketTypography = staticCompositionLocalOf { GitPocketTypography() }

/** 全工程唯一的主题入口。禁止在别处再包一层。 */
@Composable
fun GitPocketTheme(
    settings: ThemeSettings = ThemeSettings(),
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val isDark = when (settings.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = GitPocketPalettes.byId(
        if (isDark) settings.darkPaletteId else settings.lightPaletteId
    )

    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val window = activity.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.base,
            onBackground = palette.contentPrimary,
            surface = palette.surface,
            onSurface = palette.contentPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.contentSecondary,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.base,
            onBackground = palette.contentPrimary,
            surface = palette.surface,
            onSurface = palette.contentPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.contentSecondary,
            error = palette.danger,
        )
    }

    CompositionLocalProvider(
        LocalNeumorphicPalette provides palette,
        LocalSpacing provides Spacing(),
        LocalGitPocketTypography provides GitPocketTypography(),
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
