plugins {
    id("org.sonarqube") version "7.3.0.8198" apply false
}

allprojects {
    group = "fr.enimaloc"
    version = "0.10.6-ALPHA"
}

subprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://repo.spring.io/snapshot")
            content {
                includeGroupByRegex("org\\.springframework\\.cloud(\\..*)?")
            }
        }
    }
}
