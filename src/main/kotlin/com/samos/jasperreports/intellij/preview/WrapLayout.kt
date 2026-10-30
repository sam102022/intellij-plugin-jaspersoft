package com.samos.jasperreports.intellij.preview

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * FlowLayout qui passe à la ligne quand la place manque ET agrandit sa hauteur en conséquence
 * (un FlowLayout simple coupe les composants qui débordent au lieu de les afficher sur une nouvelle ligne).
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).apply { width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension = synchronized(target.treeLock) {
        // Largeur disponible : celle du conteneur, ou de son parent tant qu'il n'est pas encore dimensionné.
        var container: Container = target
        while (container.size.width == 0 && container.parent != null) {
            container = container.parent
        }
        var targetWidth = container.size.width
        if (targetWidth == 0) targetWidth = Int.MAX_VALUE

        val insets = target.insets
        val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
        val size = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0

        for (i in 0 until target.componentCount) {
            val component: Component = target.getComponent(i)
            if (!component.isVisible) continue
            val d = if (preferred) component.preferredSize else component.minimumSize
            if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                addRow(size, rowWidth, rowHeight)
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth != 0) rowWidth += hgap
            rowWidth += d.width
            rowHeight = maxOf(rowHeight, d.height)
        }
        addRow(size, rowWidth, rowHeight)

        size.width += insets.left + insets.right + hgap * 2
        size.height += insets.top + insets.bottom + vgap * 2
        // Dans un JScrollPane, ne pas imposer une largeur qui empêcherait de rétrécir.
        if (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) != null && target.isValid) {
            size.width -= hgap + 1
        }
        size
    }

    private fun addRow(size: Dimension, rowWidth: Int, rowHeight: Int) {
        size.width = maxOf(size.width, rowWidth)
        if (size.height > 0) size.height += vgap
        size.height += rowHeight
    }
}
