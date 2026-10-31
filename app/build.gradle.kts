import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 키는 저장소에 올리지 않는 keystore.properties에서 읽음. 파일이 없으면 release는 서명 없이 빌드됨.
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.motioncare"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.motioncare"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
            enableV3Signing = true    // Pleos 요구: 서명 키 교체(lineage) 지원 = v3 서명
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false   // 리플렉션을 쓰는 Pleos SDK가 있어 축소하지 않음
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
