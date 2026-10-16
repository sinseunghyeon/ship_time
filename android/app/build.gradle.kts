plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.edencrew.shiptime"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.edencrew.shiptime"
        // 무선 디버깅 ADB 페어링은 Android 11+(런타임 체크). 표시 전용은 그 이하도 가능.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // 친구 배포용: 디버그 키로 서명 (Play 스토어에 올릴 거면 별도 keystore로 교체)
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// Kotlin 2.2 부터 kotlinOptions{} 는 에러 → compilerOptions 사용
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 앱 내 무선 디버깅(ADB) — 셸 권한으로 시간대 변경
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // 숨김 API(IAlarmManager 트랜잭션 코드, SystemProperties) 리플렉션 허용
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}

flutter {
    source = "../.."
}
