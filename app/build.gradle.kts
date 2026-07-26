import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing key, resolved from (in order): the gitignored local.properties
// (signing.storeFile/storePassword/keyAlias/keyPassword, for local release builds), env vars
// KEYSTORE_FILE + KEY_ALIAS/KEY_PASSWORD/KEYSTORE_PASSWORD (a plain keystore path — the
// convention used by the gitignored build-signed-release.bat), then env vars KEYSTORE_BASE64 +
// KEY_ALIAS/KEY_PASSWORD/KEYSTORE_PASSWORD (CI convention: keystore arrives as a base64 GitHub
// secret). This is a shared multi-app keystore — see CLAUDE.md for how it was generated and
// which alias belongs to this app. Falls back to debug-signing if none of this is configured,
// so a bare `assembleRelease` still works with zero setup.
val localSigningProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingCredential(key: String, envVar: String): String? =
    localSigningProperties.getProperty(key) ?: System.getenv(envVar)

val releaseKeystoreFile: File? = when {
    System.getenv("KEYSTORE_BASE64") != null ->
        File.createTempFile("partnerride-release", ".jks").apply {
            writeBytes(Base64.getDecoder().decode(System.getenv("KEYSTORE_BASE64")))
            deleteOnExit()
        }
    System.getenv("KEYSTORE_FILE") != null -> file(System.getenv("KEYSTORE_FILE")!!)
    localSigningProperties.getProperty("signing.storeFile") != null ->
        file(localSigningProperties.getProperty("signing.storeFile")!!)
    else -> null
}

android {
    namespace = "net.bbgen.karoo.partnerride"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.bbgen.karoo.partnerride"
        // 26 covers Karoo 2 (Android 8) and Karoo 3 (Android 12)
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.5.1"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = signingCredential("signing.storePassword", "KEYSTORE_PASSWORD")
            keyAlias = signingCredential("signing.keyAlias", "KEY_ALIAS")
            keyPassword = signingCredential("signing.keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The debug-signing fallback above exists so a bare `assembleRelease` works with zero setup
// locally. In CI it is a trap: a release APK signed with the debug key cannot be installed over
// an existing real-key build (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and the only way out for a
// user is an uninstall that loses their couple code. A missing secret must therefore fail the
// build rather than quietly attach an unusable APK to a published release.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    description = "Fails a CI release build when the real signing key is not configured"
    val keystoreConfigured = releaseKeystoreFile != null
    val isCi = System.getenv("CI") != null
    doLast {
        if (isCi && !keystoreConfigured) {
            error(
                "Release signing is not configured but CI is set — refusing to fall back to " +
                    "debug signing. Check the KEYSTORE_BASE64, KEY_ALIAS, KEY_PASSWORD and " +
                    "KEYSTORE_PASSWORD repository secrets.",
            )
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

// manifest.json for Karoo OS's MANIFEST_URL update check (AndroidManifest.xml) and, if ever
// submitted, the curated Extensions Library. Hosted at a stable GitHub Releases "latest" URL, so
// it's regenerated and re-uploaded on every release rather than committed to the repo.
val manifestBaseUrl = "https://github.com/bb-generation/PartnerRide/releases/latest/download"
tasks.register("generateManifest") {
    description = "Generates manifest.json with current version information"
    group = "build"

    doLast {
        val manifest = mapOf(
            "label" to "PartnerRide",
            "packageName" to android.defaultConfig.applicationId,
            "iconUrl" to "$manifestBaseUrl/icon.png",
            "latestApkUrl" to "$manifestBaseUrl/app-release.apk",
            "latestVersion" to android.defaultConfig.versionName,
            "latestVersionCode" to android.defaultConfig.versionCode,
            "developer" to "bb-generation",
            "description" to ("Shows the live straight-line distance to a riding partner's Karoo, " +
                "signed by who is ahead, over a direct BLE broadcast between two devices " +
                "(no phone or internet needed)."),
        )
        layout.buildDirectory.file("manifest.json").get().asFile
            .writeText(groovy.json.JsonBuilder(manifest).toPrettyString())
        println("Generated manifest.json ${android.defaultConfig.versionName} (${android.defaultConfig.versionCode})")
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    // graphical data fields (RemoteViews via Glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.preview)
    // settings persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
