import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    // Pas de méthodes passerelles vers les méthodes par défaut des interfaces de la plateforme (ToolWindowFactory…) :
    // le Plugin Verifier les comptait comme des usages d'API dépréciée ou expérimentale.
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// JAR du module :runner, livré dans le dossier "runner/" du plugin, hors du classpath de l'IDE (ENF-04).
// Les attributs imposent la variante JAR : sans eux, Gradle peut choisir le dossier de classes compilées.
val runnerJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.properties")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    runnerJar(project(":runner"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        // Notes de version : section de CHANGELOG.md de la version publiée, convertie en HTML.
        changeNotes = providers.gradleProperty("pluginVersion").zip(
            providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText
        ) { pluginVersion, changelog ->
            val section = changelog.lines()
                .dropWhile { !it.startsWith("## [$pluginVersion]") }
                .drop(1)
                .takeWhile { !it.startsWith("## ") && !it.startsWith("[") }
            check(section.any { it.isNotBlank() }) { "CHANGELOG.md : aucune section pour la version $pluginVersion" }
            fun inline(text: String) = text
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("`(.+?)`"), "<code>$1</code>")
            val html = StringBuilder()
            var paragraph = ""
            var inList = false
            fun flush() {
                if (paragraph.isNotEmpty()) {
                    html.append(if (inList) "<li>${inline(paragraph)}</li>" else "<p>${inline(paragraph)}</p>")
                    paragraph = ""
                }
            }
            for (line in section) {
                when {
                    line.startsWith("### ") -> {
                        flush()
                        if (inList) { html.append("</ul>"); inList = false }
                        html.append("<h3>${inline(line.removePrefix("### "))}</h3>")
                    }
                    line.startsWith("- ") -> {
                        flush()
                        if (!inList) { html.append("<ul>"); inList = true }
                        paragraph = line.removePrefix("- ").trim()
                    }
                    line.isBlank() -> {
                        flush()
                        if (inList) { html.append("</ul>"); inList = false }
                    }
                    else -> paragraph = (paragraph + " " + line.trim()).trim()
                }
            }
            flush()
            if (inList) html.append("</ul>")
            html.toString()
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Les dépendances sont toutes des plugins livrés avec l'IDE : inutile d'interroger la Marketplace.
        freeArgs = listOf("-offline")
        ides {
            recommended()
        }
    }
}

// runIde, les tests et buildPlugin ont chacun leur tâche de préparation (prepareSandbox_runIde, …) :
// le runner doit être ajouté à toutes, pas seulement à prepareSandbox.
tasks.withType<PrepareSandboxTask>().configureEach {
    from(runnerJar) {
        into(pluginName.map { "$it/runner" })
    }
    // Échoue tout de suite plutôt qu'au premier aperçu si le runner n'a pas été copié.
    doLast {
        val jar = destinationDir.resolve("${pluginName.get()}/runner/jasper-runner.jar")
        check(jar.isFile) { "jasper-runner.jar absent du bac à sable : $jar" }
    }
}

tasks.test {
    // Jeux de fichiers réels des tests de détection de format.
    systemProperty("jrxml.corpus.v7", listOf(
        "jasperreports-master-7.1.0/demo/samples",
        "rapports de tests/jasper perso",
    ).joinToString(File.pathSeparator) { rootProject.file(it).absolutePath })
}
