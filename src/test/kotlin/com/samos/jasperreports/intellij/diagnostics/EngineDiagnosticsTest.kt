package com.samos.jasperreports.intellij.diagnostics

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.samos.jasperreports.intellij.runner.RunnerDiagnostic
import com.samos.jasperreports.intellij.runner.Severity

/** EF-50, EF-51 : diagnostics du moteur placés sur la balise concernée et affichés dans l'éditeur. */
class EngineDiagnosticsTest : BasePlatformTestCase() {

    private val report = """
        <jasperReport name="r">
        	<parameter name="Title" class="java.lang.String"/>
        	<title height="20">
        		<element kind="textField" x="0" y="0" width="100" height="20">
        			<expression><![CDATA[${'$'}P{Title}.noSuchMethod()]]></expression>
        		</element>
        		<element kind="chart" x="0" y="0" width="100" height="20"/>
        	</title>
        </jasperReport>
    """.trimIndent()

    fun testExpressionCodeKeepsTheDistinctivePart() {
        assertEquals(".noSuchMethod()", EngineDiagnostics.expressionCode(
            "value = ((java.lang.String)parameter_Title.getValue()).noSuchMethod(); //\$JR_EXPR_ID=8\$"))
        assertEquals("com.bar.SomeBarClass.isToPrint()", EngineDiagnostics.expressionCode("com.bar.SomeBarClass.isToPrint()"))
        assertNull(EngineDiagnostics.expressionCode(null))
    }

    fun testDiagnosticsAreHighlightedOnTheirTag() {
        myFixture.configureByText("report.jrxml", report)
        val file = myFixture.file.virtualFile
        EngineDiagnostics.getInstance(project).set(file, listOf(
            RunnerDiagnostic(Severity.ERROR, "cannot find symbol", null, "((java.lang.String)parameter_Title.getValue()).noSuchMethod()"),
            RunnerDiagnostic(Severity.ERROR, "Element kind \"chart\" needs jasperreports-charts", null, null, line = 7),
            RunnerDiagnostic(Severity.WARNING, "Data adapter not found", null, null),
        ))
        val highlights = myFixture.doHighlighting().filter { it.description?.startsWith("JasperReports: ") == true }
        assertEquals(highlights.toString(), 3, highlights.size)
        val text = myFixture.editor.document.text
        val expression = highlights.first { it.description!!.contains("cannot find symbol") }
        assertEquals("\$P{Title}.noSuchMethod()", text.substring(expression.startOffset, expression.endOffset).removePrefix("<![CDATA[").removeSuffix("]]>"))
        val chart = highlights.first { it.description!!.contains("chart") }
        assertTrue(text.substring(chart.startOffset).startsWith("<element kind=\"chart\""))
        val root = highlights.first { it.description!!.contains("adapter") }
        assertEquals((myFixture.file as XmlFile).rootTag!!.textRange.startOffset, root.startOffset)

        EngineDiagnostics.getInstance(project).set(file, emptyList())
        assertTrue(myFixture.doHighlighting().none { it.description?.startsWith("JasperReports: ") == true })
    }
}
