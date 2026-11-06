import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
}

// Cross-service tests belong to the repository build, not a standalone service build.
evaluationDependsOn(":wardrobe-service")
evaluationDependsOn(":recommendation-service")

project(":wardrobe-service") {
    val sourceSets = extensions.getByType<SourceSetContainer>()
    val transportTest = sourceSets.create("transportTest")
    val kotlin = extensions.getByType<KotlinJvmProjectExtension>()
    kotlin.target.compilations.named("transportTest") {
        associateWith(kotlin.target.compilations.getByName("main"))
    }
    configurations[transportTest.implementationConfigurationName]
        .extendsFrom(configurations.getByName("testImplementation"))
    configurations[transportTest.runtimeOnlyConfigurationName]
        .extendsFrom(configurations.getByName("testRuntimeOnly"))

    val wardrobeMain = sourceSets.named("main")
    val recommendationMain = project(":recommendation-service")
        .extensions.getByType<SourceSetContainer>().named("main")
    transportTest.compileClasspath += wardrobeMain.get().output + recommendationMain.get().output
    transportTest.runtimeClasspath += wardrobeMain.get().output +
        recommendationMain.get().output + recommendationMain.get().runtimeClasspath

    val transportTestTask = tasks.register<Test>("transportTest") {
        description = "Isolated maximum-payload check through production Feign and WebFlux."
        testClassesDirs = transportTest.output.classesDirs
        classpath = transportTest.runtimeClasspath
        maxHeapSize = "512m"
        inputs.file(layout.projectDirectory.file("../config-repository/recommendation-service/application.yml"))
    }

    val transportReport = tasks.register<JacocoReport>("jacocoTransportTestReport") {
        description = "Coverage of wardrobe and recommendation in the transport test."
        group = "verification"
        dependsOn(transportTestTask)
        executionData(transportTestTask.get())
        sourceSets(wardrobeMain.get(), recommendationMain.get())
        reports {
            html.required.set(true)
            xml.required.set(true)
        }
    }

    transportTestTask.configure {
        finalizedBy(transportReport)
    }
}
