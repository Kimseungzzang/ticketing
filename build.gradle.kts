import java.util.Properties

// .env 파일을 Gradle 설정 단계에서 로드
val envProps = Properties()
val envFile = file(".env")
if (envFile.exists()) {
    envFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .forEach { line ->
            val idx = line.indexOf("=")
            envProps[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
}

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Kimseungzzang/myredis-client-starter")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: envProps["GITHUB_ACTOR"] as String? ?: (project.findProperty("githubActor") as String?)
            password = System.getenv("GITHUB_TOKEN") ?: envProps["GITHUB_TOKEN"] as String? ?: (project.findProperty("githubToken") as String?)
        }
    }
}

dependencies {
    implementation("com.example:myredis-client-starter:1.0.5")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val idx = line.indexOf("=")
                environment(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }
    }
}
