plugins {
    id("org.sonarqube") version "7.5.0.8588" apply false
}

allprojects {
    group = "fr.enimaloc"
    version = "1.0.0-BETA.98"
}

subprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

/*
 * Test coverage: JaCoCo on every module except the legacy catapult-web-old. `check` fails when a
 * module's line coverage drops below its floor, so new code can't silently lower it.
 */
val coverageFloors = mapOf(
    "catapult-common" to 0.95,
    "catapult-api" to 0.87,
    "catapult-web" to 0.95,
)

configure(subprojects.filter { it.name in coverageFloors }) {
    apply(plugin = "jacoco")

    pluginManager.withPlugin("java") {
        val floor = coverageFloors.getValue(project.name)

        tasks.withType<Test>().configureEach {
            // Gradle's default test heap (512 MB) overflows once the suite has cached its Spring contexts
            maxHeapSize = "1g"
            finalizedBy("jacocoTestReport")
        }

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.withType<Test>())
            reports {
                xml.required = true
                html.required = true
            }
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn("jacocoTestReport")
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = floor.toBigDecimal()
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn("jacocoTestCoverageVerification")
        }
    }
}

