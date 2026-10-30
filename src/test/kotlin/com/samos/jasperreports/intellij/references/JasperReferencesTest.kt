package com.samos.jasperreports.intellij.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.samos.jasperreports.intellij.inspections.JasperDuplicateDeclarationInspection
import com.samos.jasperreports.intellij.inspections.JasperLayoutInspection
import com.samos.jasperreports.intellij.inspections.JasperUnknownClassInspection
import com.samos.jasperreports.intellij.inspections.JasperUnresolvedSymbolInspection
import com.samos.jasperreports.intellij.inspections.JasperUnusedDeclarationInspection

/** Références, complétion, renommage et inspections dans un vrai éditeur IntelliJ (EF-31 à EF-35, EF-38, EF-40 à EF-45). */
class JasperReferencesTest : BasePlatformTestCase() {

    private fun report(body: String, attributes: String = "") = """
        <jasperReport name="r" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20"$attributes>
        $body
        </jasperReport>
    """.trimIndent()

    private fun configure(body: String, attributes: String = "") {
        myFixture.configureByText("report.jrxml", report(body, attributes))
    }

    private fun highlights(): List<String> {
        myFixture.enableInspections(
            JasperUnresolvedSymbolInspection::class.java,
            JasperDuplicateDeclarationInspection::class.java,
            JasperUnusedDeclarationInspection::class.java,
            JasperLayoutInspection::class.java,
            JasperUnknownClassInspection::class.java,
        )
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun testFieldReferenceResolves() {
        configure("""
            <field name="amount" class="java.lang.Integer"/>
            <title height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}F{am<caret>ount}]]></expression></element></title>
        """)
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("no reference at caret", reference)
        assertNotNull("reference not resolved", reference!!.resolve())
    }

    fun testRenameFieldUpdatesExpressionsAndKeepsCdata() {
        configure("""
            <field name="amount" class="java.lang.Integer"/>
            <title height="40">
              <element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}F{am<caret>ount} > 0 ? "+" : ""]]></expression></element>
              <element kind="textField" x="0" y="20" width="100" height="20"><expression><![CDATA[${'$'}F{amount}]]></expression></element>
            </title>
        """)
        myFixture.renameElementAtCaret("total")
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("<field name=\"total\""))
        assertTrue(text, text.contains("<![CDATA[${'$'}F{total} > 0 ? \"+\" : \"\"]]>"))
        assertTrue(text, text.contains("<![CDATA[${'$'}F{total}]]>"))
        assertFalse(text, text.contains("amount"))
    }

    fun testCompletionAfterOpeningBrace() {
        configure("""
            <field name="amount" class="java.lang.Integer"/>
            <field name="label" class="java.lang.String"/>
            <title height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}F{<caret>]]></expression></element></title>
        """)
        myFixture.completeBasic()
        val names = myFixture.lookupElementStrings.orEmpty()
        assertTrue(names.toString(), names.containsAll(listOf("amount", "label")))
    }

    fun testCompletionAddsClosingBrace() {
        configure("""
            <variable name="Total" class="java.lang.Integer"/>
            <title height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}V{Tot<caret>]]></expression></element></title>
        """)
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("<![CDATA[${'$'}V{Total}]]>"))
    }

    fun testTableCellCompletesSubDatasetFields() {
        configure("""
            <dataset name="TableData"><field name="city" class="java.lang.String"/></dataset>
            <field name="country" class="java.lang.String"/>
            <title height="50"><element kind="component" x="0" y="0" width="100" height="50"><component kind="table">
              <datasetRun subDataset="TableData"/>
              <column kind="single" width="100"><detailCell height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}F{<caret>]]></expression></element></detailCell></column>
            </component></element></title>
        """)
        myFixture.completeBasic()
        val names = myFixture.lookupElementStrings.orEmpty()
        assertEquals("city", names.firstOrNull())
    }

    fun testUnresolvedFieldIsReportedAndCanBeCreated() {
        configure("""
            <parameter name="Title" class="java.lang.String"/>
            <field name="amount" class="java.lang.Integer"/>
            <title height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}F{amount} + ${'$'}F{mis<caret>sing} + ${'$'}P{Title} + ${'$'}V{PAGE_NUMBER} + ${'$'}P{REPORT_LOCALE}]]></expression></element></title>
        """)
        val problems = highlights()
        assertTrue(problems.toString(), problems.contains("Field \"missing\" is not declared in this report"))
        assertEquals(problems.toString(), 1, problems.count { it.contains("is not declared") })

        myFixture.launchAction(myFixture.findSingleIntention("Create field \"missing\""))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("<field name=\"amount\" class=\"java.lang.Integer\"/>\n<field name=\"missing\" class=\"java.lang.String\"/>")
            || Regex("""<field name="amount"[^>]*/>\s*<field name="missing" class="java.lang.String"/>""").containsMatchIn(text))
    }

    fun testStyleAndGroupAttributesAreReferences() {
        configure("""
            <style name="Bold" bold="true"/>
            <variable name="Count" class="java.lang.Integer" calculation="Count" resetType="Group" resetGroup="byCity"><expression><![CDATA[1]]></expression></variable>
            <group name="byCity"><expression><![CDATA[${'$'}V{Count}]]></expression></group>
            <title height="20"><element kind="staticText" x="0" y="0" width="100" height="20" style="Bo<caret>ld"><text><![CDATA[x]]></text></element>
            <element kind="staticText" x="0" y="0" width="100" height="20" style="Missing"><text><![CDATA[x]]></text></element></title>
        """)
        assertNotNull(myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve())
        val problems = highlights()
        assertTrue(problems.toString(), problems.contains("Style \"Missing\" is not declared in this report"))
        assertFalse(problems.toString(), problems.any { it.contains("byCity") })
    }

    fun testDuplicateUnusedLayoutAndClassChecks() {
        configure("""
            <field name="a" class="java.lang.String"/>
            <field name="a" class="java.lang.String"/>
            <field name="unused" class="com.example.Missing"/>
            <title height="20"><element kind="textField" x="0" y="10" width="100" height="20"><expression><![CDATA[${'$'}F{a}]]></expression></element></title>
        """)
        val problems = highlights()
        assertTrue(problems.toString(), problems.any { it.contains("Field \"a\" is declared more than once") })
        assertTrue(problems.toString(), problems.any { it.contains("Field \"unused\" is never used") })
        assertTrue(problems.toString(), problems.any { it.contains("reaches outside the title area") })
        assertTrue(problems.toString(), problems.any { it.contains("Class \"com.example.Missing\" is not found") })
        assertFalse(problems.toString(), problems.any { it.contains("java.lang.String") })
    }

    fun testResourceKeysResolveAgainstTheBundle() {
        myFixture.addFileToProject("messages.properties", "report.title=Report\n")
        configure("""
            <title height="20"><element kind="textField" x="0" y="0" width="100" height="20"><expression><![CDATA[${'$'}R{report.ti<caret>tle} + ${'$'}R{report.none}]]></expression></element></title>
        """, " resourceBundle=\"messages\"")
        assertNotNull(myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve())
        val problems = highlights()
        assertTrue(problems.toString(), problems.contains("Key \"report.none\" is missing from resource bundle \"messages\""))
    }

    fun testOtherXmlFilesAreIgnored() {
        myFixture.configureByText("other.xml", "<root><expression>${'$'}F{x}</expression></root>")
        assertTrue(highlights().isEmpty())
    }
}
