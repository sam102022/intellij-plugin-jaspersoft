package com.samos.jasperreports.intellij.dom

import com.intellij.openapi.module.Module
import com.intellij.psi.xml.XmlFile
import com.intellij.util.xml.DomFileDescription
import com.samos.jasperreports.intellij.format.JrxmlFormat
import com.samos.jasperreports.intellij.format.JrxmlFormatDetector

/** Associe le modèle DOM aux seuls fichiers .jrxml au format 7 (les fichiers 6 restent du XML simple). */
class JrxmlDomFileDescription : DomFileDescription<JasperReport>(JasperReport::class.java, "jasperReport") {
    override fun isMyFile(file: XmlFile, module: Module?): Boolean =
        file.name.endsWith(".jrxml", ignoreCase = true) &&
            super.isMyFile(file, module) &&
            JrxmlFormatDetector.detect(file) == JrxmlFormat.V7
}
