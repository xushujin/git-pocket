// GitPocket 根构建脚本。
// 说明：为保证构建可复现与低复杂度，本项目不使用 build-logic 复合构建，
// 各模块显式声明依赖；公共编译选项在下面的 subprojects 块中统一配置（单一实现）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// 零警告策略：本地默认关闭以便快速迭代，CI 用 -Pgitpocket.werror=true 强制开启。
val warningsAsErrors = providers.gradleProperty("gitpocket.werror")
    .map(String::toBoolean)
    .getOrElse(false)

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(warningsAsErrors)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
}
