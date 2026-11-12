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
        // El componente Java de rustls-platform-verifier (lo pide el verificador de certificados).
        maven { url = uri("${rootDir}/maven") }
    }
}

rootProject.name = "SonoraMobile"
include(":app")
