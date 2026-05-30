plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.3.0.8198"
}

group = "fr.enimaloc"
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
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.husnjak:igdb-api-jvm:1.3.2")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.getunleash:unleash-client-java:9.2.4")
    implementation("com.github.growthbook:growthbook-sdk-java:0.10.10")
    implementation("com.ibm.icu:icu4j:76.1")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.webjars.npm:chart.js:4.4.9")
    implementation("org.webjars.npm:htmx.org:2.0.4")
    implementation("org.webjars:webjars-locator-lite")
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
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required = true
        html.required = true
    }
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

sonar {
    properties {
        property("sonar.projectKey", "enimaloc_catapult_876520ec-6736-4f21-ae81-d9ee3ec3c1ce")
        property("sonar.projectName", "catapult")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
    }
}
