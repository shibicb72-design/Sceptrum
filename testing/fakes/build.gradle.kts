// Test doubles shared by the protocol and app tests: a scripted fake Android TV
// (TLS + protobuf on local sockets) and fixed certificate fixtures.
plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    api(project(":driver:androidtv"))
    implementation(libs.kotlinx.coroutines.core)
}
