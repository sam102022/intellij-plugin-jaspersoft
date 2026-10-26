plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Fournis à l'exécution par le classpath du projet ouvert, jamais embarqués (ENF-04).
    compileOnly("net.sf.jasperreports:jasperreports:7.0.8")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.6")
}

tasks.jar {
    archiveFileName.set("jasper-runner.jar")
}
