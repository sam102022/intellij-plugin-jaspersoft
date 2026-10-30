package com.samos.jasperreports.intellij.structure

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.samos.jasperreports.intellij.symbols.SymbolKind

/** Actions de l'arborescence (EF-15, EF-16) et marqueurs d'erreur (EF-17), sur le texte réel du fichier. */
class StructureEditingTest : BasePlatformTestCase() {

    private val report = """
        <jasperReport name="r" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
        	<parameter name="Title" class="java.lang.String"/>
        	<field name="amount" class="java.lang.Integer"/>
        	<group name="g">
        		<expression><![CDATA[${'$'}F{amount}]]></expression>
        	</group>
        	<title height="40">
        		<element kind="staticText" uuid="a" x="0" y="0" width="100" height="20">
        			<text><![CDATA[First]]></text>
        		</element>
        		<element kind="textField" uuid="b" x="0" y="20" width="100" height="20" evaluationGroup="g">
        			<expression><![CDATA[${'$'}P{Title} + ${'$'}F{amount}]]></expression>
        		</element>
        	</title>
        	<detail>
        		<band height="20"/>
        	</detail>
        </jasperReport>
    """.trimIndent()

    private fun configure(text: String = report) {
        myFixture.configureByText("report.jrxml", text)
    }

    private fun tags(name: String): List<XmlTag> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java).filter { it.localName == name }

    private fun text() = myFixture.editor.document.text

    private fun write(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)

    fun testAddElementToEmptyBandKeepsIndentation() {
        configure()
        val band = tags("band").single()
        write { StructureEditing.addElement(StructureEditing.elementContainer(band)!!, StructureEditing.NewElement.TEXT_FIELD) }
        assertTrue(text(), Regex("\t\t<band height=\"20\">\n\t\t\t<element kind=\"textField\" uuid=\"[^\"]+\" x=\"0\" y=\"0\" width=\"100\" height=\"20\"><expression><!\\[CDATA\\[\"\"]]></expression></element>\n\t\t</band>").containsMatchIn(text()))
    }

    fun testAddElementNextToSelectedElement() {
        configure()
        val first = tags("element").first()
        write { StructureEditing.addElement(StructureEditing.elementContainer(first)!!, StructureEditing.NewElement.LINE) }
        assertEquals(3, tags("element").size)
        assertEquals("line", tags("element").last().getAttributeValue("kind"))
        assertEquals("title", tags("element").last().parentTag!!.localName)
    }

    fun testAddBandAndDeclarations() {
        configure()
        write {
            StructureEditing.addBand(StructureEditing.bandTarget(tags("band").single())!!)
            StructureEditing.addDeclaration((myFixture.file as XmlFile).rootTag!!, SymbolKind.PARAMETER)
            StructureEditing.addDeclaration((myFixture.file as XmlFile).rootTag!!, SymbolKind.GROUP)
        }
        assertEquals(2, tags("band").size)
        assertTrue(text(), text().contains("<parameter name=\"Parameter1\" class=\"java.lang.String\"/>"))
        assertTrue(text(), text().contains("<group name=\"Group1\"/>"))
        assertTrue(text().indexOf("Parameter1") < text().indexOf("<field"))
    }

    fun testDuplicateRenamesAndChangesUuid() {
        configure()
        write {
            StructureEditing.duplicate(tags("field").single())
            StructureEditing.duplicate(tags("element").first())
        }
        assertTrue(text(), text().contains("<field name=\"amount_2\" class=\"java.lang.Integer\"/>"))
        assertEquals(3, tags("element").size)
        assertEquals(3, tags("element").map { it.getAttributeValue("uuid") }.distinct().size)
    }

    fun testMoveSwapsSiblings() {
        configure()
        write { StructureEditing.move(tags("element").first(), up = false) }
        assertEquals(listOf("textField", "staticText"), tags("element").map { it.getAttributeValue("kind") })
        assertNull(StructureEditing.sibling(tags("element").last(), up = false))
    }

    fun testDeleteRemovesTheLine() {
        configure()
        write { StructureEditing.delete(tags("parameter").single()) }
        assertFalse(text(), text().contains("Title\" class"))
        assertTrue(text(), text().contains("bottomMargin=\"20\">\n\t<field"))
    }

    fun testUsagesOfDeclarations() {
        configure()
        assertEquals(2, StructureEditing.usages(tags("field").single()).size)
        assertEquals(1, StructureEditing.usages(tags("parameter").single()).size)
        val group = StructureEditing.usages(tags("group").single())
        assertTrue(group.toString(), group.single().contains("evaluationGroup=\"g\""))
    }

    fun testTreeMarksProblems() {
        configure(report.replace("${'$'}P{Title} + ", "${'$'}P{Missing} + ").replace("y=\"20\" width=\"100\" height=\"20\"", "y=\"30\" width=\"100\" height=\"20\""))
        val root = ReportTreeBuilder.build(myFixture.file as XmlFile)!!
        val title = root.children.first { it.label == "title" }
        assertTrue(root.hasError && title.hasError)
        val textField = title.children.first { it.label == "textField" }
        assertNotNull(textField.problem)
        assertFalse(title.children.first { it.label == "staticText" }.hasError)
    }
}
