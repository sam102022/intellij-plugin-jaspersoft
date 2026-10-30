package com.samos.jasperreports.intellij.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Analyse des symboles sans IDE, sur des exemples ciblés et sur les rapports réels (propriété `jrxml.corpus.v7`). */
class ReportSymbolsTest {

    private val report = """
        <jasperReport name="r" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20" resourceBundle="messages">
          <style name="Base"/>
          <dataset name="TableData">
            <parameter name="Country" class="java.lang.String"/>
            <field name="city" class="java.lang.String"/>
            <group name="byCity"><expression><![CDATA[${'$'}F{city}]]></expression></group>
          </dataset>
          <parameter name="Title" class="java.lang.String"/>
          <field name="country" class="java.lang.String"/>
          <variable name="Total" class="java.lang.Integer" calculation="Count"><expression><![CDATA[1]]></expression></variable>
          <group name="main"><expression><![CDATA[${'$'}F{country}]]></expression></group>
          <title height="100">
            <element kind="component" x="0" y="0" width="100" height="50">
              <component kind="table">
                <datasetRun subDataset="TableData">
                  <parameter name="Country"><expression><![CDATA[${'$'}F{country}]]></expression></parameter>
                </datasetRun>
                <column kind="single" width="100">
                  <detailCell height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}F{city} + ${'$'}V{byCity_COUNT}]]></expression></element></detailCell>
                </column>
              </component>
            </element>
            <element kind="crosstab" x="0" y="50" width="100" height="50">
              <parameter name="CtTitle" class="java.lang.String"><expression><![CDATA[${'$'}P{Title}]]></expression></parameter>
              <dataset><datasetRun subDataset="TableData"/></dataset>
              <rowGroup name="Region"><bucket class="java.lang.String"><expression><![CDATA[${'$'}F{city}]]></expression></bucket></rowGroup>
              <measure name="Count" class="java.lang.Integer" calculation="Count"><expression><![CDATA[${'$'}F{city}]]></expression></measure>
              <crosstabCell><contents><element kind="textField" x="0" y="0" width="50" height="20"><expression><![CDATA[${'$'}V{Count_Region_ALL} + ${'$'}V{Count}]]></expression></element></contents></crosstabCell>
            </element>
          </title>
          <detail><band height="20"><element kind="textField" x="0" y="0" width="100" height="20" style="Base"><expression><![CDATA[${'$'}F{country} + ${'$'}P!{Title} + ${'$'}R{report.title}]]></expression></element></band></detail>
        </jasperReport>
    """.trimIndent()

    private val symbols = ReportSymbols.of(W3cTagNode.parse(report))!!

    private fun expressionsContaining(text: String): List<TagNode> =
        symbols.expressionOccurrences().map { it.first }.filter { it.textContent.contains(text) }

    @Test
    fun occurrencesAreFound() {
        val occurrences = ExpressionSyntax.occurrences("\$F{a b} + \$P!{p} + \$R{x.y} + \$V{} + \$F!{bad}")
        assertEquals(listOf("a b", "p", "x.y"), occurrences.map { it.name })
        assertTrue(occurrences[1].raw)
        assertEquals(3, occurrences[0].nameStart)
    }

    @Test
    fun tableCellUsesTheSubDataset() {
        val cell = expressionsContaining("byCity_COUNT").single()
        assertEquals("TableData", symbols.scopesAt(cell).first().name)
        assertEquals("dataset", symbols.resolve(SymbolKind.FIELD, "city", cell)!!.tag.parentTag!!.tagName)
        assertFalse(symbols.isUnknown(SymbolKind.VARIABLE, "byCity_COUNT", cell))
    }

    @Test
    fun datasetRunParametersUseTheEnclosingScope() {
        val passed = expressionsContaining("\$F{country}").first { it.parentTag?.tagName == "parameter" }
        assertEquals(Scope.Type.REPORT, symbols.scopesAt(passed).first().type)
        assertFalse(symbols.isUnknown(SymbolKind.FIELD, "country", passed))
    }

    @Test
    fun crosstabVariablesAndTotalsAreKnown() {
        val cell = expressionsContaining("Count_Region_ALL").single()
        assertEquals(Scope.Type.CROSSTAB, symbols.scopesAt(cell).first().type)
        assertFalse(symbols.isUnknown(SymbolKind.VARIABLE, "Count_Region_ALL", cell))
        assertNotNull(symbols.resolve(SymbolKind.VARIABLE, "Count", cell))
        assertTrue(symbols.isUnknown(SymbolKind.VARIABLE, "Missing_ALL", cell))
    }

    @Test
    fun builtInsAndStylesAreKnown() {
        val detail = expressionsContaining("\$P!{Title}").single()
        assertFalse(symbols.isUnknown(SymbolKind.PARAMETER, "REPORT_LOCALE", detail))
        assertFalse(symbols.isUnknown(SymbolKind.VARIABLE, "PAGE_NUMBER", detail))
        assertFalse(symbols.isUnknown(SymbolKind.VARIABLE, "main_COUNT", detail))
        assertTrue(symbols.isUnknown(SymbolKind.FIELD, "nothing", detail))
        assertFalse(symbols.isUnknown(SymbolKind.STYLE, "Base", detail))
        assertTrue(symbols.isUnknown(SymbolKind.STYLE, "Other", detail))
        assertEquals("messages", symbols.resourceBundle)
    }

    @Test
    fun completionListsDeclaredThenBuiltInNames() {
        val detail = expressionsContaining("\$P!{Title}").single()
        val parameters = symbols.completionNames(SymbolKind.PARAMETER, detail).keys.toList()
        assertEquals("Title", parameters.first())
        assertTrue("REPORT_LOCALE" in parameters)
    }

    @Test
    fun checksFindDuplicatesUnusedAndLayoutProblems() {
        val broken = ReportSymbols.of(W3cTagNode.parse("""
            <jasperReport name="r" pageHeight="100" topMargin="10" bottomMargin="10">
              <field name="a" class="java.lang.String"/>
              <field name="a" class="java.lang.String"/>
              <field name="unused" class="java.lang.String"/>
              <title height="20"><element kind="textField" x="0" y="10" width="10" height="20"><expression><![CDATA[${'$'}F{a}]]></expression></element></title>
              <pageHeader height="50"/>
              <pageFooter height="50"/>
            </jasperReport>
        """.trimIndent()))!!
        assertEquals(listOf("a"), ReportChecks.duplicates(broken).map { it.args[1] })
        assertEquals(listOf("unused"), ReportChecks.unused(broken).map { it.args[1] })
        assertEquals(
            listOf("inspection.geometry.headersFooters", "inspection.geometry.elementHeight"),
            ReportChecks.geometry(broken).map { it.key },
        )
    }

    @Test
    fun classNamesAreNormalised() {
        assertEquals("java.util.List", ReportChecks.classToFind("java.util.List<java.lang.String>"))
        assertEquals("a.Outer.Inner", ReportChecks.classToFind("a.Outer\$Inner[]"))
        assertEquals(null, ReportChecks.classToFind("int"))
    }

    /** Aucune fausse erreur sur les rapports réels : tous compilent avec JasperReports 7. */
    @Test
    fun realReportsHaveNoFalseErrors() {
        val dirs = System.getProperty("jrxml.corpus.v7").orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }.map(::File).filter { it.isDirectory }
        assumeTrue("Aucun dossier de rapports de test", dirs.isNotEmpty())
        val problems = mutableListOf<String>()
        for (file in dirs.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "jrxml" }.toList() }) {
            val report = ReportSymbols.of(W3cTagNode.parse(file)) ?: continue
            for ((tag, occurrences) in report.expressionOccurrences()) {
                occurrences.filter { report.isUnknown(it.kind, it.name, tag) }.forEach { problems += "${file.name}: ${it.kind} ${it.name}" }
            }
            ReportChecks.duplicates(report).forEach { problems += "${file.name}: $it" }
            ReportChecks.geometry(report).forEach { problems += "${file.name}: $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
