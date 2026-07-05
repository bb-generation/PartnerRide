pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
// GitHub Packages credentials for karoo-ext, resolved from (in order): gradle properties
// (-Pgpr.user=... or ~/.gradle/gradle.properties), the gitignored local.properties, then
// env vars (CI convention: USERNAME/TOKEN from github.actor/GITHUB_TOKEN).
val localProperties = java.util.Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun gprCredential(key: String, envVar: String): String? =
    providers.gradleProperty(key).orNull ?: localProperties.getProperty(key) ?: System.getenv(envVar)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext from Github Packages
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = gprCredential("gpr.user", "USERNAME")
                password = gprCredential("gpr.key", "TOKEN")
            }
        }
    }
}

rootProject.name = "PartnerGap"
include("app")
