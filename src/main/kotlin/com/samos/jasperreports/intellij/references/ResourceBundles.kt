package com.samos.jasperreports.intellij.references

import com.intellij.lang.properties.PropertiesReferenceManager
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Fichiers du bundle déclaré par `resourceBundle` (messages.properties et ses variantes de langue), EF-38. */
object ResourceBundles {

    fun files(file: PsiFile, bundle: String?): List<PropertiesFile> {
        if (bundle.isNullOrBlank()) return emptyList()
        val module = ModuleUtilCore.findModuleForPsiElement(file.originalFile) ?: return emptyList()
        return PropertiesReferenceManager.getInstance(file.project).findPropertiesFiles(module, bundle.replace('/', '.'))
    }

    fun findProperty(file: PsiFile, bundle: String?, key: String): PsiElement? =
        files(file, bundle).firstNotNullOfOrNull { it.findPropertyByKey(key)?.psiElement }

    /** Clés du bundle et leur valeur dans le premier fichier qui les définit (le fichier par défaut d'abord). */
    fun keys(file: PsiFile, bundle: String?): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        files(file, bundle).sortedBy { it.name.length }.forEach { properties ->
            properties.properties.forEach { property -> property.key?.let { result.putIfAbsent(it, property.value) } }
        }
        return result
    }
}
