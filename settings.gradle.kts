pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GitPocket"

include(
    ":app",
    ":core:model",
    ":core:git",
    ":core:common",
    ":core:designsystem",
    ":core:database",
    ":core:datastore",
    ":core:security",
    ":core:data",
    ":core:update",
    ":feature:repos",
    ":feature:repo",
    ":feature:editor",
    ":feature:commit",
    ":feature:settings",
)
