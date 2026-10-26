plugins {
    // Télécharge automatiquement un JDK 21 si aucun n'est installé localement.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jasperreports-intellij"

// Programme exécuté dans une JVM séparée, avec le classpath du projet ouvert.
include("runner")
