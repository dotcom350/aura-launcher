import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Version: un solo sitio, version.properties (lo actualiza scripts/release.ps1)
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

// Firma de las versiones publicadas. Orden: variables de entorno (GitHub Actions) -> keystore.properties (PC).
// Sin nada de eso, la release se firma con la clave de depuracion (sirve para probar, no para publicar).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signing(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

android {
    namespace = "com.david.aura"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.david.aura"
        minSdk = 25
        // Fire OS 8 = Android 11. targetSdk 30 evita restricciones de servicios en primer plano.
        targetSdk = 30
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    signingConfigs {
        val storePath = signing("AURA_KEYSTORE", "storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signing("AURA_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signing("AURA_KEY_ALIAS", "keyAlias")
                keyPassword = signing("AURA_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin minificar: Compose + kotlinx.serialization + NanoHTTPD funcionan sin reglas extra
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Servidor web embebido para el control remoto
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Codigo QR para conectar el movil escaneando
    implementation("com.google.zxing:core:3.5.3")
    // Fondos de video (archivo subido o enlace directo mp4/webm/HLS)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
}
