package com.samos.jasperreports.intellij.injection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.samos.jasperreports.intellij.references.JrxmlSymbols
import com.samos.jasperreports.intellij.references.PsiTagNode
import com.samos.jasperreports.intellij.symbols.ExpressionSyntax
import com.samos.jasperreports.intellij.symbols.ReportSymbols
import com.samos.jasperreports.intellij.symbols.SymbolKind

/*
 * Injection de langage (EF-30, EF-37, EF-39).
 *
 * Expressions : le texte devient un fragment Java, comme dans la classe que génère JasperReports
 * (JRClassGenerator : mêmes imports, classe qui étend JREvaluator pour msg() et str(), fonctions importées). Chaque $F{} $P{} $V{} $R{}
 * reste du XML (références du plugin) et est remplacé dans le fragment par un paramètre typé avec la classe de sa
 * déclaration : `$F{montant}.` propose alors les méthodes de BigDecimal.
 *
 * Requêtes `<query language="sql">` : fragment SQL si le langage SQL existe (IntelliJ Ultimate), $P{} devient `?`,
 * $P!{} un identifiant et $X{…} une condition vraie.
 */
class JasperInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? XmlText ?: return
        if (host !is PsiLanguageInjectionHost) return
        val tag = host.parentTag ?: return
        val name = tag.localName
        if (!ExpressionSyntax.isExpressionTag(name)) return
        val symbols = JrxmlSymbols.of(host.containingFile) ?: return
        val range = contentRange(host) ?: return
        if (name == "query") {
            injectSql(registrar, host, tag, range)
        } else if (isJavaReport(symbols) && tag.getAttributeValue("type").let { it == null || it == "default" }) {
            // type="simpleText" : texte avec $P{} intercalés, pas du Java.
            injectJava(registrar, host, tag, symbols, range)
        }
    }

    private fun isJavaReport(symbols: ReportSymbols): Boolean =
        symbols.root.attribute("language")?.lowercase().let { it == null || it == "java" }

    /** Partie injectable du texte : l'intérieur de l'unique section CDATA, ou tout le texte s'il n'y en a pas. */
    private fun contentRange(host: XmlText): TextRange? {
        val text = host.text
        val cdata = text.indexOf(CDATA_START)
        if (cdata >= 0) {
            if (text.indexOf(CDATA_START, cdata + 1) >= 0) return null
            val end = text.lastIndexOf(CDATA_END)
            if (end < cdata) return null
            if (text.substring(0, cdata).isNotBlank() || text.substring(end + CDATA_END.length).isNotBlank()) return null
            return TextRange(cdata + CDATA_START.length, end)
        }
        if (text.contains('<')) return null
        return TextRange(0, text.length)
    }

    private class Hole(val start: Int, val end: Int, val replacement: String)

    private fun injectJava(registrar: MultiHostRegistrar, host: XmlText, tag: XmlTag, symbols: ReportSymbols, range: TextRange) {
        val text = host.text
        if (text.substring(range.startOffset, range.endOffset).isBlank()) return
        val node = PsiTagNode(tag)
        val parameters = linkedMapOf<String, String>()
        val holes = ExpressionSyntax.occurrences(text)
            .filter { it.start >= range.startOffset && it.end <= range.endOffset }
            .map { occurrence ->
                val id = "__" + occurrence.kind.prefix + "_" + identifier(occurrence.name)
                parameters.putIfAbsent(id, javaType(symbols, occurrence.kind, occurrence.name, node))
                Hole(occurrence.start, occurrence.end, id)
            } + ExpressionSyntax.subreportLiterals(text)
            .filter { it.literalStart >= range.startOffset && it.literalEnd <= range.endOffset }
            // "Detail.jasper" reste du XML : sa référence vers Detail.jrxml (EF-36) passe avant celles de Java.
            .map { Hole(it.literalStart, it.literalEnd, "\"\"") }
        val evaluator = evaluatorAvailable(host)
        val functions = ModuleUtilCore.findModuleForPsiElement(host)?.let { FunctionLibrary.of(it).classes }.orEmpty()
        val prefix = buildString {
            if (evaluator) append("import net.sf.jasperreports.engine.*;\nimport net.sf.jasperreports.engine.fill.*;\n")
            append("import java.util.*;\nimport java.math.*;\nimport java.text.*;\nimport java.io.*;\nimport java.net.*;\n")
            functions.forEach { append("import static ").append(it).append(".*;\n") }
            symbols.root.subTags.filter { it.tagName == "import" }.map { it.textContent.trim() }.filter { it.isNotEmpty() }
                .forEach { append("import ").append(it).append(";\n") }
            append("abstract class __JasperExpression")
            if (evaluator) append(" extends JREvaluator")
            append(" {\n  Object __evaluate(")
            parameters.entries.joinTo(this, ", ") { (id, type) -> "$type $id" }
            append(") {\n    return (")
        }
        inject(registrar, JavaLanguage.INSTANCE, host, range, holes, prefix, "\n    );\n  }\n}\n")
    }

    private fun injectSql(registrar: MultiHostRegistrar, host: XmlText, tag: XmlTag, range: TextRange) {
        val sql = Language.findLanguageByID("SQL") ?: return
        val language = tag.getAttributeValue("language")?.lowercase() ?: "sql"
        if (language != "sql" && language != "plsql") return
        val text = host.text
        val holes = QUERY_HOLE.findAll(text)
            .filter { it.range.first >= range.startOffset && it.range.last + 1 <= range.endOffset }
            .map {
                val value = it.value
                val replacement = when {
                    value.startsWith("\$X") -> "1 = 1"
                    value.startsWith("\$P!") -> "__param"
                    else -> "?"
                }
                Hole(it.range.first, it.range.last + 1, replacement)
            }.toList()
        inject(registrar, sql, host, range, holes, "", "")
    }

    /**
     * Les morceaux de texte entre les trous forment un seul fragment ; chaque trou est remplacé par son texte de
     * substitution, ajouté en suffixe du morceau qui le précède (ou en préfixe du premier morceau).
     */
    private fun inject(registrar: MultiHostRegistrar, language: Language, host: XmlText, range: TextRange,
                       holes: List<Hole>, prefix: String, suffix: String) {
        val segments = mutableListOf<TextRange>()
        val after = mutableListOf<String>()
        var cursor = range.startOffset
        for (hole in holes.sortedBy { it.start }) {
            if (hole.start < cursor) continue
            segments += TextRange(cursor, hole.start)
            after += hole.replacement
            cursor = hole.end
        }
        segments += TextRange(cursor, range.endOffset)
        after += ""
        val places = segments.indices.filter { !segments[it].isEmpty }
        if (places.isEmpty()) return

        registrar.startInjecting(language)
        var pending = prefix + (0 until places.first()).joinToString("") { after[it] }
        for ((index, place) in places.withIndex()) {
            val next = places.getOrNull(index + 1) ?: segments.size
            var placeSuffix = (place until next).joinToString("") { after[it] }
            if (index == places.lastIndex) placeSuffix += suffix
            registrar.addPlace(pending, placeSuffix, host as PsiLanguageInjectionHost, segments[place])
            pending = ""
        }
        registrar.doneInjecting()
    }

    private fun javaType(symbols: ReportSymbols, kind: SymbolKind, name: String, at: PsiTagNode): String {
        if (kind == SymbolKind.RESOURCE) return "String"
        val declared = symbols.resolve(kind, name, at)?.valueClass ?: symbols.completionNames(kind, at)[name]
            ?: symbols.allScopes().firstNotNullOfOrNull { it.generated[kind]?.get(name) }
        return declared?.replace('$', '.')?.takeIf { it.isNotBlank() } ?: "Object"
    }

    private fun identifier(name: String): String {
        val clean = name.replace(NOT_IDENTIFIER, "_")
        return if (clean == name) name else clean + "_" + Integer.toHexString(name.hashCode())
    }

    private fun evaluatorAvailable(host: XmlText): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(host) ?: return false
        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)
        return JavaPsiFacade.getInstance(host.project).findClass(EVALUATOR, scope) != null
    }

    private companion object {
        const val CDATA_START = "<![CDATA["
        const val CDATA_END = "]]>"
        const val EVALUATOR = "net.sf.jasperreports.engine.fill.JREvaluator"
        val NOT_IDENTIFIER = Regex("[^A-Za-z0-9_]")
        val QUERY_HOLE = Regex("\\$" + "P!?\\{[^}\r\n]*}|\\$" + "X\\{[^}]*}")
    }
}

/**
 * Les fonctions d'instance (FunctionSupport, comme ReportFunctions ou celles du projet) sont recopiées par le
 * moteur dans la classe compilée : leur appel ne doit pas apparaître en erreur dans une expression.
 */
class JasperFunctionCallFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || highlightInfo.severity != HighlightSeverity.ERROR) return true
        val description = highlightInfo.description ?: return true
        val method = UNRESOLVED_METHOD.find(description)?.groupValues?.get(1) ?: return true
        val top = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file
        if (!top.name.endsWith(".jrxml", ignoreCase = true)) return true
        val module = ModuleUtilCore.findModuleForPsiElement(top) ?: return true
        return method !in FunctionLibrary.of(module).methodNames
    }

    private companion object {
        val UNRESOLVED_METHOD = Regex("""Cannot resolve method '(\w+)'""")
    }
}
