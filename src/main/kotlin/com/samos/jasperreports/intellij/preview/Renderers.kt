package com.samos.jasperreports.intellij.preview

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/** Rendu texte d'une liste déroulante (remplace SimpleListCellRenderer.create, dépréciée en 2026.2). */
fun <T> textRenderer(nullText: String = "", text: (T) -> String): SimpleListCellRenderer<T> =
    object : SimpleListCellRenderer<T>() {
        override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
            this.text = if (value == null) nullText else text(value)
        }
    }
