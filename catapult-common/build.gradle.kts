import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    // Not applied (a library, no boot jar): only on the classpath for the BOM coordinates below
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

description = "catapult-common"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        // The Boot version the other modules build with (gradle.properties)
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
