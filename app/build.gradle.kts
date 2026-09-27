plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chibiwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chibiwallpaper"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.0-phan10_settings_done"

        // PHẦN 13 — SỬA LỖI: Live2DCubismCore.aar chỉ có native lib cho arm64-v8a/x86/x86_64
        // (xem app/src/main/jniLibs/) — KHÔNG có armeabi-v7a. Trước đây khai armeabi-v7a ở đây
        // trong khi SDK không có lib cho kiến trúc đó → cài lên máy 32-bit thật sẽ crash
        // UnsatisfiedLinkError lúc load native. abiFilters giờ khớp đúng những gì SDK có.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "x86")
        }
    }

    // PHẦN 17 — Key ký ứng dụng CỐ ĐỊNH (chibiwallpaper-release.keystore, commit trong repo).
    // Trước đây build debug không khai signingConfig → Gradle tự dùng
    // ~/.android/debug.keystore của MÁY ĐANG BUILD. Trên GitHub Actions, mỗi lần chạy job là
    // một máy ảo MỚI TINH → file debug.keystore đó được AGP tự sinh ngẫu nhiên lại từ đầu mỗi
    // lần → APK build sau ký bằng key khác APK build trước → Android từ chối coi là "bản cập
    // nhật", máy cài đè báo lỗi "App not installed" / phải gỡ app cũ mới cài được app mới.
    // Khai signingConfig CỐ ĐỊNH dưới đây cho CẢ debug lẫn release → mọi APK xuất ra (dù build
    // ở máy nào, CI nào) đều cùng 1 chữ ký → cài đè bình thường, không cần gỡ app cũ.
    // (Mật khẩu để thẳng ở đây — chỉ 1 người dùng, không cần tách qua GitHub Secrets.)
    signingConfigs {
        create("fixed") {
            storeFile = file("../chibiwallpaper-release.keystore")
            storePassword = "chibi2026pass"
            keyAlias = "chibiwallpaper"
            keyPassword = "chibi2026pass"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // PHẦN 10-A: bật Compose
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Kotlin 1.9.x → "1.5.14" | Kotlin 2.0.x → "1.6.0"
        // Nếu build báo mismatch, sửa dòng này trước tiên.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Cubism SDK for Java (Phần 3.1)
    implementation(project(":framework"))

    // PHẦN 13 — SỬA LỖI: fileTree()/files() KHÔNG đóng gói được thư mục jni/ (.so) bên trong
    // .aar — Gradle chỉ coi nó như 1 jar thường và lấy được classes.jar, native lib bị bỏ qua
    // âm thầm (không báo lỗi lúc build, chỉ crash/thiếu lib/ trong APK cuối). Dòng dưới ĐÚNG
    // là vẫn dùng fileTree — nhưng giờ chỉ có tác dụng ở COMPILE TIME (lấy stub các hàm native
    // để code gọi được). Native lib thật (.so) đã được copy thủ công vào
    // app/src/main/jniLibs/<abi>/libLive2DCubismCoreJNI.so — AGP tự đóng gói mọi thứ trong
    // jniLibs/ vào APK theo đúng abiFilters bên trên, không cần khai gì thêm ở dependencies.
    //
    // (Không dùng cách "chuẩn" hơn là flatDir + implementation(name=..., ext="aar") vì
    // settings.gradle.kts đang set repositoriesMode = FAIL_ON_PROJECT_REPOS — thêm
    // repositories{} trong module này sẽ làm build FAIL ngay lập tức.)
    implementation(fileTree(mapOf("dir" to "../Core/android", "include" to listOf("Live2DCubismCore.aar"))))

    // Coroutines (Phần 4)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Notification (Phần 4)
    implementation("androidx.core:core:1.13.1")

    // ── PHẦN 10-A/B: Jetpack Compose ──────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ComponentActivity + setContent{}
    implementation("androidx.activity:activity-compose:1.9.0")

    // LocalLifecycleOwner trong Compose
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
