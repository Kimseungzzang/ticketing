plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
	sourceCompatibility = "21"
	targetCompatibility = "21"
}

repositories {
	mavenCentral()
}

// Spring Boot 4.0 호환 릴리스 트레인. 2025.0.x는 Boot 3.5용이라 ServerProperties NoClassDefFound 발생.
extra["springCloudVersion"] = "2025.1.2"

dependencies {
	implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
	// 로드밸런싱 (lb://) — 정적 인스턴스 목록에 round-robin 분산
	implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
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
				val key = line.substring(0, idx).trim()
				val value = line.substring(idx + 1).trim()
				environment(key, value)
			}
	}
}
