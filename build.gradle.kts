plugins {
    java
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "fr.esportline"
version = "0.0.1-SNAPSHOT"
description = "catapult"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.husnjak:igdb-api-jvm:1.3.2")
    runtimeOnly("org.postgresql:postgresql")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jsoup:jsoup:1.17.2")
    runtimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

springBoot {
    buildInfo {
        val gitBranch = runCatching {
            ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .start().inputStream.bufferedReader().readLine() ?: "unknown"
        }.getOrDefault("unknown")

        val gitCommit = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .start().inputStream.bufferedReader().readLine() ?: "unknown"
        }.getOrDefault("unknown")

        val gitRemote = runCatching {
            ProcessBuilder("git", "remote", "get-url", "origin")
                .start().inputStream.bufferedReader().readLine() ?: ""
        }.getOrDefault("")

        val githubUrl = gitRemote
            .replace(Regex("^git@github\\.com:(.+?)(\\.git)?$"), "https://github.com/$1")
            .replace(Regex("\\.git$"), "")
            .ifBlank { "#" }

        properties {
            additional.set(
                mapOf(
                    "git.branch" to gitBranch,
                    "git.commit" to gitCommit,
                    "git.github-url" to githubUrl
                )
            )
        }
    }
}
