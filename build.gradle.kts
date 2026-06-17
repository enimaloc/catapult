plugins {
    id("org.sonarqube") version "7.3.0.8198" apply false
}

allprojects {
    group = "fr.enimaloc"
    version = "0.5.13-ALPHA"
}

subprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
