plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "ai.huntingdogs.foodly"
  compileSdk = 35

  defaultConfig {
    applicationId = "ai.huntingdogs.foodly"
    minSdk = 23
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release { isMinifyEnabled = false }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  // A clean app: no Operator SDK here — the watchdog build adds it automatically.
}
