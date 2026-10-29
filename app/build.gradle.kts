import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "chabaklog.app"
    compileSdk = 35
    // 기어(GEAR_SELECTION)·외기 온도(ENV_OUTSIDE_TEMPERATURE)는 Pleos SDK가 아니라 AAOS 표준 API로 읽는다.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.chabaklog.app"
        // Pleos Playground 배포 요구사항: API 28 이상 (서명 키 교체·lineage 지원)
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // release 서명: 저장소 밖에 둔 keystore.properties가 있으면 그 키로, 없으면 debug 키로 서명(빌드는 항상 되게).
    // keystore.properties 예: storeFile=/Users/.../chabaklog-release.jks, storePassword=…, keyAlias=chabaklog, keyPassword=…
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreProps != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            // 난독화는 끈다: Pleos SDK가 리플렉션·직렬화(kotlinx.serialization)를 써서 R8 규칙 검증 없이 켜면 위험
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":datasource"))
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
