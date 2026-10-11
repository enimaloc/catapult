plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
}

description = "catapult-common"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.4")
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
