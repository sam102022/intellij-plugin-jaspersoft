package com.samos.jasperreports.intellij.project

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.samos.jasperreports.intellij.JasperBundle
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Version de JasperReports trouvée dans le classpath du module. */
data class JasperVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<JasperVersion> {
    override fun compareTo(other: JasperVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val JAR = Regex("""^jasperreports-(\d+)\.(\d+)\.(\d+)[^/\\]*\.jar$""")

        /** `jasperreports-7.0.8.jar` oui ; `jasperreports-charts-7.0.8.jar` non. */
        fun fromJarName(name: String): JasperVersion? =
            JAR.matchEntire(name)?.destructured?.let { (a, b, c) -> JasperVersion(a.toInt(), b.toInt(), c.toInt()) }
    }
}

/** Tout ce qu'il faut pour lancer le runner sur un rapport. */
data class RunTarget(
    val moduleName: String,
    val javaExecutable: Path,
    val classpath: List<String>,
    val jasperVersion: JasperVersion,
    val jrxml: Path,
    /** Racines du module et du projet : base des chemins relatifs des adaptateurs Studio. */
    val searchRoots: List<String> = emptyList(),
    /** Empreinte des classes compilées et des JAR du classpath (voir [ClasspathStamp]). */
    val classpathStamp: Long = 0,
) {
    /**
     * Un runner est réutilisable tant que le JDK, le classpath ET son contenu ne changent pas : une JVM ne recharge
     * pas une classe déjà chargée, donc après une recompilation du projet il faut un nouveau runner.
     */
    val processKey: String
        get() = javaExecutable.toString() + "|" + classpath.joinToString(File.pathSeparator) + "|" + classpathStamp
}

/**
 * Empreinte du contenu du classpath : date de modification la plus récente et nombre de fichiers des dossiers de
 * classes (target/classes, target/test-classes…), date des JAR (dépendances SNAPSHOT). Quelques dizaines de
 * millisecondes pour un module courant ; calculée hors action de lecture.
 */
object ClasspathStamp {
    fun of(classpath: List<String>): Long {
        var stamp = 17L
        for (entry in classpath) {
            val path = Path.of(entry)
            if (Files.isDirectory(path)) {
                var latest = 0L
                var count = 0L
                try {
                    Files.walk(path).use { files ->
                        files.filter { Files.isRegularFile(it) }.forEach {
                            count++
                            latest = maxOf(latest, Files.getLastModifiedTime(it).toMillis())
                        }
                    }
                } catch (e: Exception) {
                    // Dossier en cours d'écriture par une compilation : l'empreinte changera au prochain appel.
                }
                stamp = stamp * 31 + latest * 7 + count
            } else if (Files.isRegularFile(path)) {
                stamp = stamp * 31 + Files.getLastModifiedTime(path).toMillis()
            }
        }
        return stamp
    }
}

sealed interface ResolveResult {
    data class Ok(val target: RunTarget) : ResolveResult
    data class Error(val message: String) : ResolveResult
}

/**
 * Section 10.3 : classpath et JDK viennent du modèle de modules d'IntelliJ (projet Maven importé),
 * jamais d'un appel à Maven.
 */
object JasperModuleResolver {

    private val MIN_JDK = JavaSdkVersion.JDK_17

    /** Racines du module du fichier et du projet (recherche des adaptateurs Studio), même sans JDK valide. */
    fun searchRoots(project: Project, file: VirtualFile): List<String> = runReadAction {
        val module = ModuleUtilCore.findModuleForFile(file, project)
        val roots = module?.let { ModuleRootManager.getInstance(it).contentRoots.map { root -> root.path } }.orEmpty()
        (roots + listOfNotNull(project.basePath)).distinct()
    }

    fun resolve(project: Project, file: VirtualFile): ResolveResult =
        when (val result = resolveModel(project, file)) {
            is ResolveResult.Ok -> ResolveResult.Ok(result.target.copy(classpathStamp = ClasspathStamp.of(result.target.classpath)))
            is ResolveResult.Error -> result
        }

    /** Partie qui lit le modèle de l'IDE, en action de lecture ; l'empreinte du classpath est calculée hors lecture. */
    private fun resolveModel(project: Project, file: VirtualFile): ResolveResult = runReadAction {
        val module = ModuleUtilCore.findModuleForFile(file, project)
            ?: return@runReadAction ResolveResult.Error(JasperBundle.message("runner.error.noModule"))

        val sdk = ModuleRootManager.getInstance(module).sdk ?: ProjectRootManager.getInstance(project).projectSdk
            ?: return@runReadAction ResolveResult.Error(JasperBundle.message("runner.error.noSdk"))
        val sdkType = sdk.sdkType as? JavaSdkType
            ?: return@runReadAction ResolveResult.Error(JasperBundle.message("runner.error.notJava"))
        val jdkVersion = JavaSdk.getInstance().getVersion(sdk)
        if (jdkVersion == null || !jdkVersion.isAtLeast(MIN_JDK)) {
            return@runReadAction ResolveResult.Error(
                JasperBundle.message("runner.error.jdkTooOld", sdk.versionString ?: sdk.name)
            )
        }
        val java = sdkType.getVMExecutablePath(sdk)
            ?: return@runReadAction ResolveResult.Error(JasperBundle.message("runner.error.noSdk"))

        val classpath = OrderEnumerator.orderEntries(module)
            .withoutSdk()
            .recursively()
            .runtimeOnly()
            .classes()
            .pathsList
            .pathList

        val jasper = classpath.firstNotNullOfOrNull { JasperVersion.fromJarName(File(it).name) }
            ?: return@runReadAction ResolveResult.Error(JasperBundle.message("runner.error.noJasper"))
        if (jasper.major != 7) {
            return@runReadAction ResolveResult.Error(JasperBundle.message("runner.error.notV7", jasper.toString()))
        }

        ResolveResult.Ok(RunTarget(module.name, Path.of(java), classpath, jasper, Path.of(file.path), searchRoots(project, file)))
    }
}
