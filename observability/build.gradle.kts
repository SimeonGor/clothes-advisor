plugins {
    `java-library`
    kotlin("jvm") version "2.3.21"
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.0-alpha")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(25) }

tasks.test { useJUnitPlatform() }
