package com.samos.jasperreports.intellij.format

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.XmlRecursiveElementWalkingVisitor
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

enum class JrxmlFormat { V7, V6, UNKNOWN }

/**
 * Détection du format (section 3) :
 * - racine `jasperReport` sans espace de noms et éléments `<element kind>` : format 7 ;
 * - espace de noms JasperReports ou balises propres au format 6 : format 6.
 */
object JrxmlFormatDetector {

    const val V6_NAMESPACE = "http://jasperreports.sourceforge.net/jasperreports"
    private const val ROOT = "jasperReport"

    /** Balises qui n'existent plus en 7 (remplacées par `<element kind="…">` et `<expression>`). */
    private val V6_TAGS = setOf(
        "reportElement", "textField", "staticText", "textFieldExpression",
        "queryString", "groupExpression", "subreportExpression", "imageExpression"
    )

    fun detect(file: XmlFile): JrxmlFormat = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(compute(file), file)
    }

    private fun compute(file: XmlFile): JrxmlFormat {
        val root = file.rootTag ?: return JrxmlFormat.UNKNOWN
        if (root.localName != ROOT) return JrxmlFormat.UNKNOWN
        if (root.namespace == V6_NAMESPACE) return JrxmlFormat.V6
        var result = JrxmlFormat.V7
        root.accept(object : XmlRecursiveElementWalkingVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.localName in V6_TAGS) {
                    result = JrxmlFormat.V6
                    stopWalking()
                } else {
                    super.visitXmlTag(tag)
                }
            }
        })
        return result
    }

    /** Variante sans PSI, utilisée hors de l'index (fournisseur d'éditeur) et par les tests. */
    fun detect(file: VirtualFile): JrxmlFormat =
        try {
            file.inputStream.use { detect(it) }
        } catch (e: Exception) {
            JrxmlFormat.UNKNOWN
        }

    fun detect(input: InputStream): JrxmlFormat {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        }
        val reader = factory.createXMLStreamReader(input)
        try {
            var rootSeen = false
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                if (!rootSeen) {
                    if (reader.localName != ROOT) return JrxmlFormat.UNKNOWN
                    if (reader.namespaceURI == V6_NAMESPACE) return JrxmlFormat.V6
                    rootSeen = true
                } else if (reader.localName in V6_TAGS) {
                    return JrxmlFormat.V6
                }
            }
            return if (rootSeen) JrxmlFormat.V7 else JrxmlFormat.UNKNOWN
        } catch (e: XMLStreamException) {
            return JrxmlFormat.UNKNOWN
        } finally {
            reader.close()
        }
    }
}
