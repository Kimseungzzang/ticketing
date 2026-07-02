import java.util.Properties

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
    kotlin("plugin.jpa") version "2.2.21"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Kimseungzzang/myredis-client-starter")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: envProps["GITHUB_ACTOR"] as String? ?: (project.findProperty("githubActor") as String?)
            password = System.getenv("GITHUB_TOKEN") ?: envProps["GITHUB_TOKEN"] as String? ?: (project.findProperty("githubToken") as String?)
        }
    }
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.example:myredis-client-starter:1.0.5")
    implementation("com.github.ghals5737.mykafka:client:v0.1.0")
    // 모니터링: actuator /actuator/prometheus 노출 → Prometheus 스크랩 (RUN_LOG §19)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // 분산추적: MyKafka produce 등 커스텀 구간에 수동 span 부여.
    //   agent는 API 호출을 실제 구현으로 이어주기만 하므로 API 클래스는 런타임에 있어야 함(implementation).
    implementation("io.opentelemetry:opentelemetry-api:1.54.1")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.postgresql:postgresql")

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
    // OTEL_AGENT env가 있으면 OpenTelemetry java agent 부착(분산추적). 평소 실행엔 영향 없음.
    System.getenv("OTEL_AGENT")?.let { jvmArgs("-javaagent:$it") }
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
