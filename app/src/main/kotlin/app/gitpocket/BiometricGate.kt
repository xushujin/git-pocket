package app.gitpocket

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.datastore.SettingsSpecs
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTextButton
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import kotlinx.coroutines.launch

/**
 * 生物识别门禁。
 *
 * 开启后，进程启动（或 Activity 重建）时必须通过指纹 / 面容 / 锁屏密码校验，
 * 才把内容组合出来。校验失败不退出应用，只停留在锁屏页，可重试。
 *
 * 设计取舍：
 * - 只挡"冷启动/重建"，不在每次 onResume 重新上锁——否则从系统文件选择器返回也会被拦。
 * - 设备没有可用生物识别时不静默放行，而是明确告诉用户并把开关关掉，
 *   避免"开了但没用"的假安全。
 */
@Composable
fun BiometricGate(
    enabled: Boolean,
    settings: SettingsRepository,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val context = LocalContext.current
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findFragmentActivity() }

    var unlocked by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf(checkBiometric(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 从系统设置录完指纹返回时重新检测
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = checkBiometric(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unlocked) {
        content()
        return
    }

    val prompt = remember(activity) {
        if (activity == null) {
            null
        } else {
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlocked = true
                        errorMessage = null
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        errorMessage = errString.toString()
                    }
                },
            )
        }
    }

    fun authenticate() {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁 GitPocket")
            .setSubtitle("验证指纹、面容或锁屏密码后继续")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        errorMessage = null
        prompt?.authenticate(info)
    }

    LaunchedEffect(status) {
        if (status == BiometricManager.BIOMETRIC_SUCCESS) authenticate()
    }

    GpScreen {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LockGlyph(Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                text = "已锁定",
                style = typography.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = palette.contentPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (status) {
                    BiometricManager.BIOMETRIC_SUCCESS -> errorMessage ?: "验证后查看你的仓库"
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "设备还没有录入指纹或面容"
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "这台设备不支持生物识别"
                    else -> "生物识别暂时不可用"
                },
                style = typography.caption,
                color = palette.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            when (status) {
                BiometricManager.BIOMETRIC_SUCCESS -> {
                    GpTextButton(text = "解锁", onClick = ::authenticate)
                }

                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    GpTextButton(
                        text = "去录入",
                        onClick = { context.openBiometricEnroll() },
                    )
                }

                else -> {
                    GpTextButton(
                        text = "关闭生物识别解锁",
                        onClick = {
                            scope.launch {
                                settings.set(SettingsSpecs.biometric, false)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun checkBiometric(context: Context): Int {
    val manager = BiometricManager.from(context)
    return manager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
}

private fun Context.openBiometricEnroll() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL)
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
private fun LockGlyph(modifier: Modifier = Modifier) {
    val palette = LocalNeumorphicPalette.current
    Canvas(modifier) {
        val unit = size.minDimension / 40f
        drawRoundRect(
            color = palette.accent,
            topLeft = Offset(8f * unit, 18f * unit),
            size = Size(24f * unit, 18f * unit),
            cornerRadius = CornerRadius(4f * unit),
        )
        drawArc(
            color = palette.accent,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(12f * unit, 6f * unit),
            size = Size(16f * unit, 16f * unit),
            style = Stroke(width = 3f * unit),
        )
        drawCircle(
            color = palette.base,
            radius = 2.2f * unit,
            center = Offset(20f * unit, 26f * unit),
        )
    }
}
