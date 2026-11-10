import java.util.Properties
import java.util.Base64

val localProps = Properties().apply {
    listOf(rootProject.file("../local.properties"), rootProject.file("local.properties")).forEach { f ->
        if (f.exists()) f.inputStream().use { load(it) }
    }
}

fun resolveConfig(key: String): String? =
    (providers.gradleProperty(key).orNull ?: localProps.getProperty(key))?.trim()?.takeIf { it.isNotEmpty() }

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)

    if (project.name == "app") {
        val extraDefines = mutableListOf<String>()
        listOf("GOOGLE_WEB_CLIENT_ID", "BASE_URL", "DISABLE_AUTO_UPDATE").forEach { key ->
            val value = resolveConfig(key)
            if (value != null) {
                val encoded = Base64.getEncoder().encodeToString("$key=$value".toByteArray(Charsets.UTF_8))
                extraDefines.add(encoded)
            }
        }
        if (extraDefines.isNotEmpty()) {
            val existing = project.findProperty("dart-defines")?.toString()?.takeIf { it.isNotEmpty() }
            val combined = if (existing != null) "$existing,${extraDefines.joinToString(",")}" else extraDefines.joinToString(",")
            project.extensions.extraProperties.set("dart-defines", combined)
        }
    }

    val configureAndroid: () -> Unit = {
        val android = project.extensions.findByName("android")
        if (android != null) {
            try {
                val method = android.javaClass.methods.firstOrNull {
                    (it.name == "compileSdkVersion" || it.name == "setCompileSdkVersion") &&
                        it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Integer::class.java)
                }
                method?.invoke(android, 36)
            } catch (_: Exception) {}
        }
    }
    if (project.state.executed) {
        configureAndroid()
    } else {
        project.afterEvaluate { configureAndroid() }
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
