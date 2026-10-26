package com.samos.jasperreports.intellij.filetype

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.lang.xml.XMLLanguage
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.JasperIcons
import javax.swing.Icon

/** EF-01 : les .jrxml restent du XML pour l'IDE (coloration, formatage, recherche), avec leur propre type. */
object JrxmlFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = "JasperReports Report"
    override fun getDisplayName(): String = "JasperReports Report"
    override fun getDescription(): String = JasperBundle.message("filetype.jrxml.description")
    override fun getDefaultExtension(): String = "jrxml"
    override fun getIcon(): Icon = JasperIcons.REPORT
}

object JrtxFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = "JasperReports Style Template"
    override fun getDisplayName(): String = "JasperReports Style Template"
    override fun getDescription(): String = JasperBundle.message("filetype.jrtx.description")
    override fun getDefaultExtension(): String = "jrtx"
    override fun getIcon(): Icon = JasperIcons.REPORT
}
