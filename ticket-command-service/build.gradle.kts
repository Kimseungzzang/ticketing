plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
	sourceCompatibility = "21"
	targetCompatibility = "21"
}

repositories {
	mavenCentral()
	maven("https://jitpack.io")   // 독립 배포된 MyKafka client (JitPack)
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// 모니터링: actuator /actuator/prometheus 노출 → Prometheus 스크랩 (RUN_LOG §19)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")

	// 독립 라이브러리가 된 MyKafka의 client SDK — JitPack 배포본 (protocol 전이 포함).
	implementation("com.github.ghals5737.mykafka:client:v0.1.0")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
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
				val key = line.substring(0, idx).trim()
				val value = line.substring(idx + 1).trim()
				environment(key, value)
			}
	}
}
