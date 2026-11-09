pluginManagement {
    // Versions from gradle.properties, so the modules apply these plugins without repeating them
    val springBootVersion: String by settings
    val dependencyManagementVersion: String by settings
    plugins {
        id("org.springframework.boot") version springBootVersion
        id("io.spring.dependency-management") version dependencyManagementVersion
    }
}

rootProject.name = "catapult"
include("catapult-common", "catapult-api", "catapult-web")
