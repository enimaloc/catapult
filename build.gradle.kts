plugins {
    id("org.sonarqube") version "7.5.0.8588" apply false
}

allprojects {
    group = "fr.enimaloc"
    version = "0.3.7-BETA"
}

subprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
