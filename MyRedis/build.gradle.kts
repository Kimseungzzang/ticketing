plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.115.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

application {
    mainClass.set("com.example.myredis.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}
