package com.samos.jasperreports.intellij

import com.intellij.DynamicBundle
import com.intellij.openapi.util.IconLoader
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

private const val BUNDLE = "messages.JasperBundle"

/** Textes de l'interface, externalisés pour une future traduction (ENF-10). */
object JasperBundle : DynamicBundle(JasperBundle::class.java, BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}

object JasperIcons {
    @JvmField
    val REPORT: Icon = IconLoader.getIcon("/icons/jrxml.svg", JasperIcons::class.java)
}

const val PLUGIN_ID = "com.samos.jasperreports.editor"
const val NOTIFICATION_GROUP = "JasperReports"
