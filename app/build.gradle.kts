import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val vendoredRuntime = mapOf(
  "../third_party/commonmark/commonmark-0.24.0.jar" to
    "679338e0b7fc15c02d275d598654b01a149893bc28a87992e90123c8d06af25b",
  "../third_party/speech/jna-5.13.0.aar" to
    "3a03daf5a7212201c3a9b44f734dfc9babb3addcb5cbfa639f9f535e887925c0",
  "../third_party/speech/vosk-android-0.3.47.aar" to
    "e3b82bb877753569f5e5f007807e4bc6165c34ba04e772d1df93552af0d27ffa",
).mapKeys { (path, _) -> file(path) }

val verifyVendoredRuntime = tasks.register("verifyVendoredRuntime") {
  inputs.files(vendoredRuntime.keys)
  doLast {
    for ((artifact, expected) in vendoredRuntime) {
      val digest = MessageDigest.getInstance("SHA-256")
      artifact.inputStream().use { input ->
        DigestInputStream(input, digest).use { hashed ->
          hashed.transferTo(OutputStream.nullOutputStream())
        }
      }
      check(HexFormat.of().formatHex(digest.digest()) == expected) {
        "Checksum mismatch for ${artifact.name}"
      }
    }
  }
}
tasks.named("preBuild") { dependsOn(verifyVendoredRuntime) }

android {
  namespace = "com.cairn.securepocket"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.cairn.securepocket"
    minSdk = 30
    targetSdk = 35
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    versionCode = 9
    versionName = "1.8.0"
  }

  // Instrumentation uses a separate package so it cannot replace a user's app data.
  testBuildType = "validation"

  buildTypes {
    create("validation") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".isotest"
      matchingFallbacks += listOf("debug")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.google.mediapipe:tasks-genai:0.10.27")
  constraints {
    implementation("com.google.guava:guava:33.8.0-android") {
      because("Use a patched Android release for the Guava dependency brought in by MediaPipe.")
    }
    implementation("com.google.protobuf:protobuf-javalite:4.33.6") {
      because("Use a patched Java Lite runtime for CVE-2024-7254.")
    }
  }
  implementation(files(vendoredRuntime.keys))
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.kotlinx.serialization.json)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  add("validationImplementation", libs.androidx.compose.ui.test.manifest)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
