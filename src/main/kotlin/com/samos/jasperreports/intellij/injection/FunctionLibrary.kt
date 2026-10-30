package com.samos.jasperreports.intellij.injection

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.io.StringReader
import java.util.Properties

/**
 * Fonctions appelables sans préfixe dans les expressions (SUM, DATEFORMAT, ou fonctions du projet) : classes
 * déclarées par `net.sf.jasperreports.extension.functions.*` dans les jasperreports_extension.properties du
 * module et de ses bibliothèques (jasperreports-functions, par exemple). Le compilateur du moteur les ajoute à la
 * volée (JRClassGenerator.modifySource) : méthodes statiques importées, méthodes d'instance recopiées.
 */
class FunctionLibrary(val classes: List<String>, val methodNames: Set<String>) {

    companion object {
        private const val EXTENSION_FILE = "jasperreports_extension.properties"
        private const val PREFIX = "net.sf.jasperreports.extension.functions."

        fun of(module: Module): FunctionLibrary = CachedValuesManager.getManager(module.project).getCachedValue(module) {
            CachedValueProvider.Result.create(compute(module),
                ProjectRootManager.getInstance(module.project), PsiModificationTracker.getInstance(module.project))
        }

        private fun compute(module: Module): FunctionLibrary {
            val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)
            val names = FilenameIndex.getVirtualFilesByName(EXTENSION_FILE, scope).flatMap { file ->
                val properties = Properties()
                runCatching { properties.load(StringReader(VfsUtilCore.loadText(file))) }
                properties.stringPropertyNames().filter { it.startsWith(PREFIX) }
                    .flatMap { properties.getProperty(it).split(',') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }.distinct()
            val facade = JavaPsiFacade.getInstance(module.project)
            val found = names.mapNotNull { facade.findClass(it, scope) }
            return FunctionLibrary(
                found.mapNotNull { it.qualifiedName },
                found.flatMap { psiClass -> psiClass.allMethods.map { it.name } }.toSet(),
            )
        }
    }
}
