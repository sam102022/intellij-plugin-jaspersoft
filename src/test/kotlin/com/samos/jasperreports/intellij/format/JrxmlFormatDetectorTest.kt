package com.samos.jasperreports.intellij.format

import com.samos.jasperreports.intellij.project.JasperVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tests sans IDE de la détection de format, sur des rapports réels au format 7 (propriété `jrxml.corpus.v7`,
 * liste de dossiers séparés par le séparateur de chemins) :
 * - `jasperreports-master-7.1.0/demo/samples` : 171 exemples officiels ;
 * - `rapports de tests/jasper perso` : 30 rapports métier (Studio 7.0.1 et 7.0.3).
 * Les dossiers absents sont ignorés ; s'ils manquent tous (par exemple en CI), le test est ignoré.
 */
class JrxmlFormatDetectorTest {

    private fun detect(file: File): JrxmlFormat = file.inputStream().use { JrxmlFormatDetector.detect(it) }

    @Test
    fun realReportsAreVersion7() {
        val dirs = System.getProperty("jrxml.corpus.v7").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isDirectory }
        assumeTrue("Aucun dossier de rapports de test", dirs.isNotEmpty())
        val files = dirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "jrxml" }.toList() }
        assertTrue("Aucun .jrxml dans $dirs", files.isNotEmpty())
        val wrong = files.filter { detect(it) != JrxmlFormat.V7 }
        assertTrue("${wrong.size}/${files.size} rapports non reconnus comme format 7 : $wrong", wrong.isEmpty())
    }

    @Test
    fun version6IsDetected() {
        val v6WithNamespace = """
            <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="r">
              <detail><band height="20"/></detail>
            </jasperReport>
        """.trimIndent()
        val v6WithDoctype = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE jasperReport PUBLIC "-//JasperReports//DTD Report Design//EN" "http://jasperreports.sourceforge.net/dtds/jasperreport.dtd">
            <jasperReport name="r">
              <detail><band height="20">
                <textField><reportElement x="0" y="0" width="10" height="10"/>
                  <textFieldExpression><![CDATA[${'$'}F{a}]]></textFieldExpression></textField>
              </band></detail>
            </jasperReport>
        """.trimIndent()
        assertEquals(JrxmlFormat.V6, JrxmlFormatDetector.detect(v6WithNamespace.byteInputStream()))
        assertEquals(JrxmlFormat.V6, JrxmlFormatDetector.detect(v6WithDoctype.byteInputStream()))
    }

    @Test
    fun version7IsDetected() {
        val v7 = """
            <jasperReport name="r" language="java" pageWidth="595" pageHeight="842">
              <title height="25">
                <element kind="textField" x="0" y="0" width="100" height="20">
                  <expression><![CDATA[${'$'}R{title}]]></expression>
                </element>
              </title>
            </jasperReport>
        """.trimIndent()
        assertEquals(JrxmlFormat.V7, JrxmlFormatDetector.detect(v7.byteInputStream()))
    }

    @Test
    fun otherXmlIsUnknown() {
        assertEquals(JrxmlFormat.UNKNOWN, JrxmlFormatDetector.detect("<project/>".byteInputStream()))
        assertEquals(JrxmlFormat.UNKNOWN, JrxmlFormatDetector.detect("not xml".byteInputStream()))
    }

    @Test
    fun jasperVersionIsReadFromJarName() {
        assertEquals(JasperVersion(7, 0, 8), JasperVersion.fromJarName("jasperreports-7.0.8.jar"))
        assertEquals(JasperVersion(7, 1, 0), JasperVersion.fromJarName("jasperreports-7.1.0.jar"))
        assertNull(JasperVersion.fromJarName("jasperreports-charts-7.0.8.jar"))
        assertNull(JasperVersion.fromJarName("jasperreports-fonts-7.0.8.jar"))
    }
}
