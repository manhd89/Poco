plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.luxysiv.puretube"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.luxysiv.puretube"
    minSdk = 24
    targetSdk = 36
    versionCode = 39
    versionName = "2.3.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  //
  // Trên CI, KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD/KEY_ALIAS được nạp từ GitHub Actions
  // secrets và dùng chung cho cả debug lẫn release. Khi build local không có các biến này,
  // debug quay về debug.keystore như cũ.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      val sharedKeystorePath = System.getenv("KEYSTORE_PATH")
      if (sharedKeystorePath != null) {
        storeFile = file(sharedKeystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    buildConfig = true
    // Bật ViewBinding để các màn hình XML mới không phải dùng findViewById.
    viewBinding = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// In đầy đủ stack trace khi unit test thất bại để log CI có đủ nguyên nhân.
tasks.withType<Test>().configureEach {
  // Robolectric tải sẵn gói android-all từ Maven Central lúc chạy test. IP dùng chung
  // trên CI đôi khi bị chặn 429, nên lấy qua mirror chính thức của Google.
  systemProperty(
    "robolectric.dependency.repo.url",
    "https://maven-central.storage-download.googleapis.com/maven2",
  )
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStackTraces = true
    showCauses = true
    showExceptions = true
  }
}

// GitHub Actions chạy unit test trước khi đóng gói APK. Việc này cũng giúp
// phát hiện sớm lỗi ở tầng trích xuất/parse dù workflow chưa có bước riêng.
if (System.getenv("CI") == "true") {
  tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
  }
}

dependencies {
  // Hạ tầng XML View: AppCompat/Material cho theme, widget và Snackbar native.
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.swiperefreshlayout)
  implementation(libs.coil.view)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  // Trình phát video: Media3 ExoPlayer (thay WebView) + DASH/HLS adaptive
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.dash)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.ui)
  // MediaSession + foreground service cho phát nền / notification / lock screen
  implementation(libs.androidx.media3.session)
  // Spring/Fling animation cho hiệu ứng thu nhỏ: nối tiếp vận tốc vuốt tay
  // thay vì tự viết ValueAnimator với đường cong cố định.
  implementation(libs.androidx.dynamicanimation)
  testImplementation(libs.androidx.core)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  "ksp"(libs.androidx.room.compiler)
}
