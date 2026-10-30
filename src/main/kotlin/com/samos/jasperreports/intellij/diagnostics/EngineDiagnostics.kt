package com.samos.jasperreports.intellij.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.runner.RunnerDiagnostic
import com.samos.jasperreports.intellij.runner.Severity
import com.samos.jasperreports.intellij.symbols.ExpressionSyntax
import java.util.concurrent.ConcurrentHashMap

/**
 * Derniers diagnostics du moteur par rapport (EF-50) : compilation, aperçu, Compile All. Ils sont affichés dans
 * l'éditeur et, de là, dans l'onglet du fichier de la fenêtre Problems, jusqu'à la compilation suivante.
 */
@Service(Service.Level.PROJECT)
class EngineDiagnostics(private val project: Project) {

    private val byFile = ConcurrentHashMap<String, List<RunnerDiagnostic>>()

    fun get(file: VirtualFile): List<RunnerDiagnostic> = byFile[file.path].orEmpty()

    fun set(file: VirtualFile, diagnostics: List<RunnerDiagnostic>) {
        val relevant = diagnostics.filter { it.severity != Severity.INFO }
        val previous = if (relevant.isEmpty()) byFile.remove(file.path) else byFile.put(file.path, relevant)
        if (previous == relevant || (previous == null && relevant.isEmpty())) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !file.isValid) return@invokeLater
            val psi = ReadAction.computeBlocking<com.intellij.psi.PsiFile?, RuntimeException> { PsiManager.getInstance(project).findFile(file) }
            psi?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "JasperReports engine diagnostics") }
        }, project.disposed)
    }

    companion object {
        fun getInstance(project: Project): EngineDiagnostics = project.service()

        /**
         * EF-51 : place d'un diagnostic dans le fichier. Une erreur d'expression est retrouvée par le texte de
         * l'expression (la ligne Java générée contient ce texte) ; une erreur du lecteur XML par son numéro de
         * ligne ; sinon la balise racine.
         */
        fun locate(root: XmlTag, document: Document?, diagnostic: RunnerDiagnostic): PsiElement {
            expressionCode(diagnostic.code)?.let { code ->
                PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)
                    .firstOrNull { ExpressionSyntax.isExpressionTag(it.localName) && normalize(it.value.trimmedText).contains(code) }
                    ?.let { return it }
            }
            val line = diagnostic.line
            if (line != null && document != null && line in 1..document.lineCount) {
                val offset = document.getLineStartOffset(line - 1)
                val element = root.containingFile.findElementAt(offset + countIndent(document, line - 1))
                PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)?.let { return it }
            }
            return root
        }

        private fun countIndent(document: Document, line: Int): Int {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            return document.charsSequence.subSequence(start, end).takeWhile { it == ' ' || it == '\t' }.length
        }

        /**
         * Morceau distinctif du code cité par le moteur. La ligne Java générée (`value = …; //$JR_EXPR_ID=3$`) contient
         * les $F{} $P{} $V{} déjà traduits (`((Type)field_x.getValue())`) : on garde le plus long morceau entre eux,
         * qui figure tel quel dans l'expression du rapport.
         */
        fun expressionCode(code: String?): String? {
            val text = code?.trim()
                ?.substringBefore("//\$JR_EXPR_ID")
                ?.removePrefix("value = ")
                ?.trim()?.removeSuffix(";")?.trim()
                ?: return null
            // Les $F{x} sont devenus ((Type) field_x.getValue()) : on cherche le plus long morceau sans eux.
            val pieces = text.split(Regex("\\(\\([\\w.<>\\[\\]]+\\)\\s*(field|parameter|variable)_\\w+\\.(get|getOld|getEstimated)Value\\(\\)\\)"))
            return pieces.map { normalize(it) }.filter { it.length >= MIN_CODE }.maxByOrNull { it.length }
        }

        private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

        private const val MIN_CODE = 3
    }
}

/** Affiche les diagnostics mémorisés dans l'éditeur du rapport. */
class EngineDiagnosticsAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val root = element as? XmlTag ?: return
        if (root.parentTag != null) return
        val file = root.containingFile?.virtualFile ?: return
        if (!file.name.endsWith(".jrxml", ignoreCase = true)) return
        val diagnostics = EngineDiagnostics.getInstance(root.project).get(file)
        if (diagnostics.isEmpty()) return
        val document = PsiDocumentManager.getInstance(root.project).getDocument(root.containingFile)
        for (diagnostic in diagnostics) {
            val target = EngineDiagnostics.locate(root, document, diagnostic)
            val range = if (target is XmlTag && target != root && target.value.textRange.length > 0 && ExpressionSyntax.isExpressionTag(target.localName)) {
                target.value.textRange
            } else {
                // Premier mot de la balise : surligner tout le rapport serait illisible.
                TextRange.from(target.textRange.startOffset, minOf(target.textLength, (target as? XmlTag)?.name?.length?.plus(1) ?: 1))
            }
            val severity = if (diagnostic.severity == Severity.WARNING) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            val message = JasperBundle.message("diagnostics.engine", diagnostic.message)
            holder.newAnnotation(severity, message)
                .range(range)
                .tooltip("<html>${escape(message)}${diagnostic.code?.let { "<br><code>${escape(it)}</code>" }.orEmpty()}</html>")
                .create()
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
