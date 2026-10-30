plugins {
    java
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// Le runner tourne dans la JVM du projet de l'utilisateur : Java 17 minimum.
tasks.compileJava { options.release.set(17) }
tasks.compileTestJava { options.release.set(17) }

repositories { mavenCentral() }

// Version du moteur pour les tests : 7.0.8 par défaut, `-PjasperVersion=7.1.0` pour vérifier la migration.
val jasperVersion = providers.gradleProperty("jasperVersion").getOrElse("7.0.8")

dependencies {
    // Fournis à l'exécution par le classpath du module de l'utilisateur.
    compileOnly("net.sf.jasperreports:jasperreports:$jasperVersion")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.6")

    // Tests : le vrai moteur et les extensions utilisées par les exemples officiels.
    testImplementation("net.sf.jasperreports:jasperreports:$jasperVersion")
    testImplementation("net.sf.jasperreports:jasperreports-charts:$jasperVersion")
    testImplementation("net.sf.jasperreports:jasperreports-barcode4j:$jasperVersion")
    testImplementation("net.sf.jasperreports:jasperreports-barbecue:$jasperVersion")
    testImplementation("net.sf.jasperreports:jasperreports-json:$jasperVersion")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.6")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { archiveFileName.set("jasper-runner.jar") }

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("jrxml.samples", rootProject.file("jasperreports-master-7.1.0/demo/samples").absolutePath)
    // Le rapport de compilation des exemples est écrit ici.
    systemProperty("runner.reports", layout.buildDirectory.dir("reports/runner").get().asFile.absolutePath)
    // Le compilateur d'expressions (javax.tools) a besoin du classpath complet, que le moteur lit ici.
    val testClasspath = sourceSets.test.get().runtimeClasspath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Djasper.reports.compile.class.path=" + testClasspath.asPath)
    })
    maxHeapSize = "1g"
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
