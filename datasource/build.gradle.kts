// 순수 Kotlin(JVM) 모듈: Android/Pleos SDK 없이 컴파일·테스트할 수 있는 데이터 소스 추상화 계층.
// 앱 모듈이 이 모듈에 의존하고, Android/Pleos 구현은 app/.../adapters 에 둔다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}
