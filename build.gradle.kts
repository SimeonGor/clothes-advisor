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

    tasks.register<Test>("transportTest") {
        description = "Isolated maximum-payload check through production Feign and WebFlux."
        testClassesDirs = transportTest.output.classesDirs
        classpath = transportTest.runtimeClasspath
        maxHeapSize = "3g"
        inputs.file(layout.projectDirectory.file("../config-repository/recommendation-service/recommendation-service.yml"))
    }
}
