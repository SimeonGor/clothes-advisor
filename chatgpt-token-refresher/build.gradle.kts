plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("tools.jackson.core:jackson-databind:3.1.5")
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(25) }
application { mainClass.set("refresher.MainKt") }
tasks.test { useJUnitPlatform() }
