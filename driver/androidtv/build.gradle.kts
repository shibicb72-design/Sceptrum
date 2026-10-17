// Android TV Remote protocol v2 (ports 6466/6467). Pure Kotlin/JVM: no Android
// imports, so the protocol tests run on a plain JVM against FakeAndroidTvServer.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wire)
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

wire {
    kotlin {}
}

dependencies {
    api(libs.wire.runtime)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing:fakes"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
