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

rootProject.name = "sih-dead-reckoning-lite"
include(":app")

// Redirect build artifacts outside OneDrive sync tree to prevent Windows file locking
val localBuildDir = java.io.File(System.getProperty("user.home"), ".gradle-build/sih-dead-reckoning-lite")
gradle.beforeProject {
    layout.buildDirectory.set(java.io.File(localBuildDir, name))
}

