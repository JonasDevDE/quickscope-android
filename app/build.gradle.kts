import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Release-Signing: Standard ist der Debug-Keystore des Entwickler-PCs (dessen SHA-1 ist bei Google registriert).
// Optional ueberschreibbar per QUICKSCOPE_SIGNING_PROPERTIES (Properties-Datei ausserhalb des Repos mit
// storeFile, storePassword, keyAlias, keyPassword).
val signingPropsFile: File? = (System.getenv("QUICKSCOPE_SIGNING_PROPERTIES") ?: providers.gradleProperty("QUICKSCOPE_SIGNING_PROPERTIES").orNull)
    ?.let { File(it) }
val signingProps = Properties().apply {
    if (signingPropsFile?.exists() == true) signingPropsFile.inputStream().use { load(it) }
}

fun config(key: String, default: String = ""): String =
    (providers.gradleProperty(key).orNull ?: localProps.getProperty(key) ?: default).trim()

android {
    namespace = "com.jonasschnell.quickscope"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jonasschnell.quickscope"
        minSdk = 26
        targetSdk = 36
        // Zum Veroeffentlichen eines Updates: versionCode um 1 erhoehen (und versionName anpassen), dann scripts/publish.sh.
        versionCode = 6
        versionName = "0.2.3"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${config("GOOGLE_WEB_CLIENT_ID")}\"")
        // Adresse des Backends (mit abschliessendem Schraegstrich). Platzhalter, bis die echte Adresse in local.properties
        // oder per -PBASE_URL=... gesetzt wird; zeigt bewusst auf keinen echten Server.
        buildConfigField("String", "BASE_URL", "\"${config("BASE_URL", "https://quickscope.invalid/")}\"")
    }

    signingConfigs {
        create("release") {
            if (signingPropsFile?.exists() == true) {
                val keystore = File(signingProps.getProperty("storeFile", "").trim())
                storeFile = if (keystore.isAbsolute) keystore else File(signingPropsFile.parentFile, keystore.path)
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            } else {
                storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any {
        it.project == project && it.name.contains("Release") &&
            (it.name.startsWith("assemble") || it.name.startsWith("package") || it.name.startsWith("bundle"))
    }
    if (releaseRequested) {
        if (signingPropsFile != null) {
            if (!signingPropsFile.exists()) {
                throw GradleException("Release-Signing: Datei ${signingPropsFile.absolutePath} fehlt (QUICKSCOPE_SIGNING_PROPERTIES).")
            }
            val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { signingProps.getProperty(it).isNullOrBlank() }
            if (missing.isNotEmpty()) throw GradleException("Release-Signing: In der Signing-Properties-Datei fehlen Eintraege: ${missing.joinToString()}.")
        }
        val ks = android.signingConfigs.getByName("release").storeFile
        if (ks == null || !ks.exists()) throw GradleException("Release-Signing: Keystore-Datei nicht gefunden: $ks")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
