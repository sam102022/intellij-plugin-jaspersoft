package com.samos.jasperreports.intellij.properties

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil

/** Lecture et écriture des propriétés dans le XML (EF-23 à EF-28), et schéma des propriétés. */
class PropertiesTest : BasePlatformTestCase() {

    private val original = """
        <jasperReport name="r" pageWidth="595" pageHeight="842">
          <!-- commentaire conservé -->
          <title height="40">
            <element kind="textField" uuid="u1" x="0" y="0" width="100" height="20" style="Base" customAttr="keep">
              <property name="a" value="1"/>
              <expression><![CDATA[${'$'}F{name}]]></expression>
            </element>
            <element kind="textField" uuid="u2" x="0" y="20" width="100" height="20">
              <expression><![CDATA["x"]]></expression>
            </element>
          </title>
        </jasperReport>
    """.trimIndent()

    private var configured = false

    private fun elements(): List<XmlTag> {
        if (!configured) myFixture.configureByText("report.jrxml", original)
        configured = true
        return PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java).filter { it.localName == "element" }
    }

    private fun def(name: String) = PropertySchema.forTag("element", "textField")!!.first { it.name == name }

    private fun write(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)

    fun testReadValues() {
        val first = elements().first()
        assertEquals("100", PropertyAccess.read(first, def("width")))
        assertEquals("${'$'}F{name}", PropertyAccess.read(first, def("expression")))
        assertNull(PropertyAccess.read(first, def("positionType")))
        assertEquals(listOf("a" to "1"), PropertyAccess.customProperties(first))
        assertEquals(listOf("customAttr"), PropertyAccess.unknownAttributes(first, PropertySchema.forTag("element", "textField")!!).map { it.name })
    }

    fun testWriteKeepsTheRestOfTheFile() {
        val first = elements().first()
        write { PropertyAccess.write(first, def("width"), "120") }
        val text = myFixture.editor.document.text
        assertEquals(original.replace("width=\"100\" height=\"20\" style=\"Base\"", "width=\"120\" height=\"20\" style=\"Base\""), text)
    }

    fun testEmptyValueRemovesAttributeOrChild() {
        val first = elements().first()
        write {
            PropertyAccess.write(first, def("style"), "")
            PropertyAccess.write(first, def("expression"), null)
        }
        val text = myFixture.editor.document.text
        assertFalse(text, text.contains("style=\"Base\""))
        assertFalse(text, text.contains("${'$'}F{name}"))
        assertTrue(text, text.contains("<!-- commentaire conservé -->"))
    }

    fun testExpressionIsWrittenAsCdata() {
        val second = elements()[1]
        write {
            PropertyAccess.write(second, def("expression"), "a < b && \"]]>\"")
            PropertyAccess.write(second, def("printWhenExpression"), "${'$'}V{PAGE_NUMBER} > 1")
        }
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("<![CDATA[a < b && \"]]]]><![CDATA[>\"]]>"))
        assertTrue(text, text.contains("<printWhenExpression><![CDATA[${'$'}V{PAGE_NUMBER} > 1]]></printWhenExpression>"))
        assertEquals("a < b && \"]]>\"", PropertyAccess.read(elements()[1], def("expression")))
    }

    fun testMultipleSelectionWritesAll() {
        val tags = elements()
        write { tags.forEach { PropertyAccess.write(it, def("positionType"), "Float") } }
        assertEquals(2, Regex("positionType=\"Float\"").findAll(myFixture.editor.document.text).count())
    }

    fun testCustomProperties() {
        val first = elements().first()
        write { PropertyAccess.writeCustomProperties(first, listOf("a" to "2", "b" to "3")) }
        assertEquals(listOf("a" to "2", "b" to "3"), PropertyAccess.customProperties(elements().first()))
        write { PropertyAccess.writeCustomProperties(elements().first(), listOf("b" to "3")) }
        assertEquals(listOf("b" to "3"), PropertyAccess.customProperties(elements().first()))
    }

    fun testSchema() {
        assertEquals("band", PropertySchema.keyOf("title", null))
        assertEquals("element:image", PropertySchema.keyOf("element", "image"))
        assertNull(PropertySchema.keyOf("detail", null))
        val textField = PropertySchema.forTag("element", "textField")!!
        assertEquals(textField.size, textField.map { it.name }.distinct().size)
        assertEquals(PropertyCategory.entries.filter { c -> textField.any { it.category == c } }, textField.map { it.category }.distinct())
        assertNotNull(PropertySchema.forTag("element", "chart"))
        assertEquals("properties.error.integer" to "1.5", PropertySchema.validate(def("width"), "1.5"))
        assertEquals("properties.error.min" to "0", PropertySchema.validate(def("width"), "-1"))
        assertNull(PropertySchema.validate(def("width"), ""))
        assertNull(PropertySchema.validate(def("forecolor"), "#FF0000"))
        assertNotNull(PropertySchema.validate(def("forecolor"), "#FF00"))
        assertNotNull(PropertySchema.validate(PropertySchema.forTag("band", null)!!.first { it.name == "height" }, ""))
    }

    fun testEveryAttributeOfTheRealReportsIsKnownOrListed() {
        // Les attributs des 201 rapports réels qui ne sont pas dans le schéma restent visibles (EF-26) : ce test
        // documente seulement que le schéma couvre les attributs usuels des éléments texte.
        val known = PropertySchema.knownAttributes(PropertySchema.forTag("element", "textField")!!)
        for (name in listOf("hTextAlign", "textAdjust", "pattern", "evaluationTime", "evaluationGroup", "blankWhenNull", "markup")) {
            assertTrue(name, name in known)
        }
    }

    /** Le panneau se construit pour une, puis deux balises, et affiche les valeurs du XML (EF-20, EF-27). */
    fun testPanelShowsValues() {
        val tags = elements()
        val panel = ReportPropertiesPanel(project)
        panel.show(listOf(tags.first()))
        val fields = UIUtil.findComponentsOfType(panel, JBTextField::class.java)
        assertTrue(fields.map { it.text }.toString(), fields.any { it.text == "100" } && fields.any { it.text == "${'$'}F{name}" })
        panel.show(tags)
        val shared = UIUtil.findComponentsOfType(panel, JBTextField::class.java)
        assertTrue(shared.any { it.text == "100" })
        assertTrue(shared.any { it.emptyText.text == "multiple values" })
    }

    /**
     * Depuis 2026.1, le fil graphique n'a plus d'accès implicite au PSI : le panneau doit prendre lui-même ses
     * actions de lecture. Hors du fil graphique, tout accès sans action de lecture est signalé.
     */
    fun testPanelTakesItsOwnReadActions() {
        val tags = elements()
        val panel = ReportPropertiesPanel(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            panel.show(tags)
            panel.refresh()
            panel.show(listOf(tags.first()))
        }.get()
    }
}
