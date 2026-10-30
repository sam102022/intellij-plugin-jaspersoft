package com.samos.jasperreports.intellij.preview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil

/** EF-70 : configuration d'aperçu mémorisée par rapport et relue depuis le XML de .idea/jasperreports.xml. */
class PreviewSettingsTest : BasePlatformTestCase() {

    fun testSaveAndReload() {
        val file = myFixture.addFileToProject("reports/a.jrxml", "<jasperReport name=\"a\"/>").virtualFile
        val settings = PreviewSettings.getInstance(project)
        settings.save(file, mapOf("Title" to "Hello", "REPORT_LOCALE" to "fr_FR", "Empty" to ""), "adapter:beans.xml:Beans", 150)

        val saved = settings.get(file)!!
        assertEquals(mapOf("Title" to "Hello", "REPORT_LOCALE" to "fr_FR"), saved.parameters)
        assertEquals("adapter:beans.xml:Beans", saved.dataSource)
        assertEquals(150, saved.zoom)
        assertFalse(settings.key(file), settings.key(file).startsWith("/"))

        // Aller-retour par le XML, comme à la fermeture et à la réouverture du projet.
        val xml = XmlSerializer.serialize(settings.state)
        val text = JDOMUtil.write(xml)
        assertTrue(text, text.contains("Hello") && text.contains("REPORT_LOCALE"))
        val reloaded = XmlSerializer.deserialize(xml, PreviewSettingsState::class.java)
        assertEquals(saved.parameters, reloaded.reports[settings.key(file)]!!.parameters)
        assertEquals(150, reloaded.reports[settings.key(file)]!!.zoom)
    }

    fun testDataSourceKeysArePortable() {
        val adapter = StudioAdapter("Beans", "bean", java.nio.file.Path.of("/home/me/project/adapters/beans.xml"))
        assertEquals("adapter:beans.xml:Beans", DataSourceChoice.Adapter(adapter).key)
        assertEquals("default", DataSourceChoice.ReportDefault("x.xml", null).key)
        assertEquals("empty", DataSourceChoice.OneEmptyRecord.key)
    }
}

/** L'onglet d'aperçu se construit, relit les paramètres du rapport et remet les valeurs mémorisées. */
class ReportPreviewEditorTest : BasePlatformTestCase() {
    fun testEditorRestoresSavedParameters() {
        val file = myFixture.addFileToProject("r.jrxml", """
            <jasperReport name="r"><parameter name="Title" class="java.lang.String"/></jasperReport>
        """.trimIndent()).virtualFile
        PreviewSettings.getInstance(project).save(file, mapOf("Title" to "Bonjour"), "empty", 100)
        val editor = ReportPreviewEditor(project, file)
        try {
            com.intellij.testFramework.PlatformTestUtil.waitWhileBusy { 
                com.intellij.util.ui.UIUtil.findComponentsOfType(editor.component, com.intellij.ui.components.JBTextField::class.java)
                    .none { it.text == "Bonjour" }
            }
            val combos = com.intellij.util.ui.UIUtil.findComponentsOfType(editor.component, com.intellij.openapi.ui.ComboBox::class.java)
            assertTrue(combos.any { it.selectedItem == 100 })
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }
}
