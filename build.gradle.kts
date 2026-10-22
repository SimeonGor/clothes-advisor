import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    id("jacoco-report-aggregation")
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    jacoco
}

dependencies {
    jacocoAggregation(project(":wardrobe-service"))
    jacocoAggregation(project(":config-server"))
    jacocoAggregation(project(":discovery-server"))
    jacocoAggregation(project(":api-gateway"))
    jacocoAggregation(project(":recommendation-service"))
    jacocoAggregation(project(":account-service"))
}

jacoco {
    toolVersion = "0.8.14"
}

reporting {
    reports {
        create<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName = "test"
        }
    }
}

tasks.register<JacocoCoverageVerification>("testCodeCoverageVerification") {
    val aggregateReport = tasks.named<JacocoReport>("testCodeCoverageReport")
    dependsOn(aggregateReport)
    sourceDirectories.from(aggregateReport.map { it.sourceDirectories })
    classDirectories.from(aggregateReport.map { it.classDirectories })
    executionData.from(aggregateReport.map { it.executionData })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn("testCodeCoverageVerification")
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
        inputs.file(layout.projectDirectory.file("../config-repository/recommendation-service.yml"))
    }
}
