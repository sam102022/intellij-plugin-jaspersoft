package com.samos.jasperreports.intellij.injection

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.samos.jasperreports.intellij.JasperBundle

/**
 * Erreurs Java des expressions (EF-30). Le surlignage Java d'IntelliJ ignore les fragments injectés : seuls la
 * syntaxe et les inspections y passent. On signale donc ici les symboles et méthodes introuvables, comme le
 * ferait le compilateur du moteur, en épargnant les fonctions JasperReports ajoutées à la compilation.
 */
class JasperExpressionAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiJavaCodeReferenceElement) return
        val file = element.containingFile ?: return
        val injected = InjectedLanguageManager.getInstance(element.project)
        if (!injected.isInjectedFragment(file)) return
        val host = injected.getInjectionHost(file) ?: return
        val hostFile = host.containingFile ?: return
        if (!hostFile.name.endsWith(".jrxml", ignoreCase = true)) return
        // Seulement le texte écrit dans le rapport, pas l'enveloppe générée (imports, paramètres $F{}…).
        val nameElement = element.referenceNameElement ?: return
        if (injected.intersectWithAllEditableFragments(file, nameElement.textRange).isEmpty()) return
        val name = element.referenceName ?: return

        val call = (element as? PsiReferenceExpression)?.parent as? PsiMethodCallExpression
        if (call != null && call.methodExpression == element) {
            if (call.resolveMethod() != null || element.multiResolve(false).isNotEmpty()) return
            // Qualificatif lui-même introuvable : une seule erreur, sur le qualificatif.
            val qualifier = element.qualifierExpression
            if (qualifier != null && qualifier.type == null) return
            if (element.qualifierExpression == null && isFunction(hostFile, name)) return
            holder.newAnnotation(HighlightSeverity.ERROR, JasperBundle.message("expression.unresolvedMethod", name))
                .range(nameElement).create()
            return
        }
        if (element.advancedResolve(true).element != null || element.multiResolve(false).isNotEmpty()) return
        holder.newAnnotation(HighlightSeverity.ERROR, JasperBundle.message("expression.unresolvedSymbol", name))
            .range(nameElement).create()
    }

    private fun isFunction(hostFile: com.intellij.psi.PsiFile, name: String): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(hostFile) ?: return false
        return name in FunctionLibrary.of(module).methodNames
    }
}
