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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
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
 * ajoutée si elle manque.
 */
class JasperCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val file = parameters.originalFile
            val symbols = JrxmlSymbols.of(file) ?: return
            val offset = parameters.offset
            val leaf = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return
            val text = PsiTreeUtil.getParentOfType(leaf, XmlText::class.java, false) ?: return
            val tag = text.parentTag ?: return
            if (!ExpressionSyntax.isExpressionTag(tag.localName)) return

            val chars = parameters.editor.document.charsSequence
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

        private val CLOSE_BRACE = InsertHandler<LookupElement> { context, _ ->
            val document = context.document
            val tail = context.tailOffset
            if (tail >= document.textLength || document.charsSequence[tail] != '}') {
                document.insertString(tail, "}")
            }
            context.editor.caretModel.moveToOffset(tail + 1)
        }
    }
}

/** Ouvre la complétion dès l'accolade de `$F{` tapée. */
class JasperTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '{' || !file.name.endsWith(".jrxml", ignoreCase = true)) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val before = editor.document.charsSequence.subSequence((offset - 3).coerceAtLeast(0), offset)
        if (TRIGGER.containsMatchIn(before) && JrxmlSymbols.of(file) != null) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }

    private companion object {
        val TRIGGER = Regex("""\$(?:[FVR]|P!?)$""")
    }
}
