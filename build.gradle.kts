plugins {
    id("org.sonarqube") version "7.5.0.8588" apply false
}

allprojects {
    group = "fr.enimaloc"
    version = "1.7.0"
}

subprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

/*
 * Test sharding for CI: `-PtestShard=<i>/<n>` (i from 0) makes each module's `test` task run only the
 * classes whose name hashes to shard i, so n jobs split a module's suite. String.hashCode is the same
 * on every JVM, and a nested class is hashed by its outer class so both land in the same shard.
 */
val testShard = providers.gradleProperty("testShard").orNull?.let { spec ->
    val parts = spec.split("/").mapNotNull { it.trim().toIntOrNull() }
    require(parts.size == 2 && parts[1] > 0 && parts[0] in 0 until parts[1]) {
        "testShard must be <index>/<count> with 0 <= index < count, got '$spec'"
    }
    parts[0] to parts[1]
}

// The build cache (gradle.properties) is for compiled outputs: a test result read back from it would
// hide a run under another environment, and `clean test` must still rerun the suite
subprojects {
    tasks.withType<Test>().configureEach {
        outputs.doNotCacheIf("test results are never taken from the build cache") { true }
    }
}

if (testShard != null) {
    val (index, count) = testShard
    subprojects {
        tasks.withType<Test>().matching { it.name == "test" }.configureEach {
            exclude { element ->
                val outerClass = element.relativePath.pathString.removeSuffix(".class").substringBefore('$')
                !element.isDirectory && element.name.endsWith(".class") &&
                    Math.floorMod(outerClass.hashCode(), count) != index
            }
        }
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
        }

        // The report reads the `test` task's data only: tying it to every Test task made running
        // englishConventions alone (a CI shard) run the whole suite as well
        tasks.named("test") {
            finalizedBy("jacocoTestReport")
        }

        // CI's final job merges the shards' coverage: `-PjacocoExecDir=<dir>` (relative to the root)
        // reads every coverage/<module>/*.exec under it instead of this module's own test runs
        val mergedExecutionData = providers.gradleProperty("jacocoExecDir").orNull?.let { dir ->
            fileTree(rootProject.file(dir)) { include("**/coverage/${project.name}/*.exec") }
        }
        if (mergedExecutionData != null) {
            tasks.withType<JacocoReportBase>().configureEach {
                executionData.setFrom(mergedExecutionData)
            }
        }

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
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

