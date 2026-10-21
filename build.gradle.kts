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
