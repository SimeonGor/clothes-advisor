pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "clothes-advisor"

include("wardrobe-service")
include("config-server")
include("discovery-server")
include("api-gateway")
include("recommendation-service")
include("account-service")
include("chatgpt-token-refresher")
