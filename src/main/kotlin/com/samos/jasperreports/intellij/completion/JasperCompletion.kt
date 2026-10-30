package com.samos.jasperreports.intellij.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext
import com.samos.jasperreports.intellij.references.JrxmlSymbols
import com.samos.jasperreports.intellij.references.PsiTagNode
import com.samos.jasperreports.intellij.references.ResourceBundles
import com.samos.jasperreports.intellij.references.symbolIcon
import com.samos.jasperreports.intellij.symbols.ExpressionSyntax
import com.samos.jasperreports.intellij.symbols.SymbolKind

/**
 * Complétion après `$F{`, `$P{`, `$P!{`, `$V{` et `$R{` (EF-32, EF-38) : déclarations visibles depuis l'expression
 * (sous-jeu de données d'une table, crosstab…), puis noms intégrés, avec leur classe. L'accolade fermante est
 * ajoutée si elle manque. Enregistré pour XML et pour Java (expressions injectées, EF-30).
 */
class JasperCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            // Dans une expression injectée en Java, on travaille sur le .jrxml hôte.
            val injected = InjectedLanguageManager.getInstance(parameters.originalFile.project)
            val file = injected.getTopLevelFile(parameters.originalFile) ?: return
            val symbols = JrxmlSymbols.of(file) ?: return
            val offset = injected.injectedToHost(parameters.originalFile, parameters.offset)
            // Curseur dans une expression injectée : la passe Java s'en charge, ne pas proposer deux fois.
            if (file == parameters.originalFile && injected.findInjectedElementAt(file, (offset - 1).coerceAtLeast(0)) != null) return
            val leaf = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return
            val text = PsiTreeUtil.getParentOfType(leaf, XmlText::class.java, false) ?: return
            val tag = text.parentTag ?: return
            if (!ExpressionSyntax.isExpressionTag(tag.localName)) return

            val chars = file.viewProvider.document?.charsSequence ?: return
            val before = chars.subSequence((offset - MAX_NAME).coerceAtLeast(0), offset)
            val match = ExpressionSyntax.PARTIAL.find(before) ?: return
            val kind = SymbolKind.ofPrefix(match.groupValues[1][0]) ?: return
            if (match.groupValues[2] == "!" && kind != SymbolKind.PARAMETER) return

            val names: Map<String, String?> = if (kind == SymbolKind.RESOURCE) {
                ResourceBundles.keys(file, symbols.resourceBundle)
            } else {
                symbols.completionNames(kind, PsiTagNode(tag))
            }
            val declared = if (kind == SymbolKind.RESOURCE) names.keys else
                symbols.scopesAt(PsiTagNode(tag)).flatMap { scope -> scope.names(kind).keys }.toSet()

            val prefixed = result.withPrefixMatcher(match.groupValues[3])
            for ((name, detail) in names) {
                val builder = LookupElementBuilder.create(name)
                    .withIcon(symbolIcon(kind))
                    .withTypeText(if (kind == SymbolKind.RESOURCE) detail else detail?.substringAfterLast('.'), true)
                    .withInsertHandler(CLOSE_BRACE)
                    .let { if (name in declared) it.bold() else it }
                // Déclarations du rapport avant les noms intégrés.
                prefixed.addElement(PrioritizedLookupElement.withPriority(builder, if (name in declared) 1.0 else 0.0))
            }
            result.stopHere()
        }
    }

    companion object {
        private const val MAX_NAME = 200

        /** Ajoute `}` dans le document du .jrxml, même si la complétion a eu lieu dans l'expression injectée. */
        private val CLOSE_BRACE = InsertHandler<LookupElement> { context, _ ->
            val injected = InjectedLanguageManager.getInstance(context.project)
            val editor = InjectedLanguageEditorUtil.getTopLevelEditor(context.editor)
            val document = editor.document
            val tail = injected.injectedToHost(context.file, context.tailOffset)
            if (tail >= document.textLength || document.charsSequence[tail] != '}') {
                document.insertString(tail, "}")
            }
            editor.caretModel.moveToOffset(tail + 1)
        }
    }
}

/** Ouvre la complétion dès l'accolade de `$F{` tapée. */
class JasperTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '{') return Result.CONTINUE
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
        if (!top.name.endsWith(".jrxml", ignoreCase = true)) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val before = editor.document.charsSequence.subSequence((offset - 3).coerceAtLeast(0), offset)
        if (TRIGGER.containsMatchIn(before) && JrxmlSymbols.of(top) != null) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }

    private companion object {
        val TRIGGER = Regex("""\$(?:[FVR]|P!?)$""")
    }
}
