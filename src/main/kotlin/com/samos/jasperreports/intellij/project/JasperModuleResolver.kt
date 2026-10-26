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
) {
    /** Un runner est réutilisable tant que le JDK et le classpath ne changent pas. */
    val processKey: String get() = javaExecutable.toString() + "|" + classpath.joinToString(File.pathSeparator)
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

    private val MIN_JDK = JavaSdkVersion.JDK_21

    fun resolve(project: Project, file: VirtualFile): ResolveResult = runReadAction {
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

        val roots = ModuleRootManager.getInstance(module).contentRoots.map { it.path } + listOfNotNull(project.basePath)
        ResolveResult.Ok(RunTarget(module.name, Path.of(java), classpath, jasper, Path.of(file.path), roots.distinct()))
    }
}
