package com.samos.jasperreports.intellij.preview

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.xml.DomManager
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.dom.JasperReport
import com.samos.jasperreports.intellij.runner.ParameterValue
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/** Paramètre `$P{}` déclaré par le rapport. */
data class ReportParameterInfo(
    val name: String,
    val type: String,
    val defaultExpression: String?,
    val description: String?,
    val prompting: Boolean,
)

/** Ce que l'aperçu lit dans le rapport : paramètres et adaptateur Studio déclaré. */
data class PreviewReportInfo(val parameters: List<ReportParameterInfo>, val declaredAdapter: String?)

object PreviewReportInfoReader {
    const val STUDIO_ADAPTER_PROPERTY = "com.jaspersoft.studio.data.defaultdataadapter"

    /** À appeler dans une action de lecture. */
    fun read(project: Project, file: VirtualFile): PreviewReportInfo? {
        val xml = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return null
        val report = DomManager.getDomManager(project).getFileElement(xml, JasperReport::class.java)?.rootElement ?: return null
        val parameters = report.getParameters().mapNotNull { p ->
            val name = p.getName().stringValue?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReportParameterInfo(
                name = name,
                type = p.getValueClass().stringValue ?: "java.lang.String",
                defaultExpression = p.getDefaultValueExpression().stringValue?.trim()?.takeIf { it.isNotEmpty() },
                description = p.getDescription().stringValue?.trim()?.takeIf { it.isNotEmpty() },
                prompting = p.getForPrompting().value ?: true,
            )
        }
        val adapter = report.getProperties().firstOrNull { it.getName().stringValue == STUDIO_ADAPTER_PROPERTY }
            ?.getValue()?.stringValue
        return PreviewReportInfo(parameters, adapter)
    }
}

/**
 * Panneau de gauche de l'aperçu, comme dans Jaspersoft Studio :
 * - « Input Parameters » : un champ par `$P{}` à saisir (forPrompting), vide = defaultValueExpression du rapport ;
 * - « Report Parameters » : paramètres intégrés du moteur, dont REPORT_LOCALE qui choisit la langue des `$R{}`.
 */
class PreviewParametersPanel : JPanel(BorderLayout()) {

    private interface Field {
        val component: JComponent
        fun text(): String?
        fun setText(value: String?)
    }

    private class TextField(hint: String?, enabled: Boolean = true) : Field {
        private val textField = JBTextField().apply {
            emptyText.text = hint.orEmpty()
            isEnabled = enabled
        }
        override val component: JComponent get() = textField
        override fun text() = textField.text.takeIf { it.isNotBlank() }
        override fun setText(value: String?) {
            textField.text = value.orEmpty()
        }
    }

    /** Liste à choix, éditable ou non ; l'entrée vide signifie « valeur par défaut ». */
    private class ChoiceField(values: List<String>, editable: Boolean, defaultLabel: String) : Field {
        private val combo = ComboBox((listOf("") + values).toTypedArray()).apply {
            isEditable = editable
            renderer = SimpleListCellRenderer.create<String>(defaultLabel) { it.ifEmpty { defaultLabel } }
        }
        override val component: JComponent get() = combo
        override fun text(): String? =
            (if (combo.isEditable) combo.editor.item?.toString() else combo.selectedItem?.toString())?.trim()?.takeIf { it.isNotEmpty() }
        override fun setText(value: String?) {
            combo.selectedItem = value.orEmpty()
        }
    }

    private data class Row(val name: String, val type: String, val field: Field?)

    private val inputRows = mutableListOf<Row>()
    private val reportRows = mutableListOf<Row>()
    private val inputTab = JPanel(BorderLayout())
    private var parameters: List<ReportParameterInfo> = emptyList()

    init {
        // Largeur minimale utilisable : le séparateur de l'aperçu ne peut pas rétrécir le panneau en dessous.
        minimumSize = Dimension(JBUI.scale(MIN_WIDTH), 0)
        val tabs = JBTabbedPane().apply {
            addTab(JasperBundle.message("preview.params.input"), JBScrollPane(inputTab).apply { border = JBUI.Borders.empty() })
            addTab(JasperBundle.message("preview.params.report"), JBScrollPane(buildReportTab()).apply { border = JBUI.Borders.empty() })
        }
        add(tabs, BorderLayout.CENTER)
        setParameters(emptyList())
    }

    private companion object {
        /** Libellé au-dessus du champ : le champ prend toute la largeur du panneau. */
        const val LABEL_ON_TOP = true
        const val MIN_WIDTH = 240
    }

    /** Reconstruit l'onglet des `$P{}` en gardant les valeurs déjà saisies pour les paramètres conservés. */
    fun setParameters(newParameters: List<ReportParameterInfo>) {
        val prompted = newParameters.filter { it.prompting }
        if (prompted == parameters.filter { it.prompting } && inputRows.isNotEmpty()) {
            parameters = newParameters
            return
        }
        val previous = inputRows.associate { it.name to it.field?.text() }
        parameters = newParameters
        inputRows.clear()

        val form = FormBuilder.createFormBuilder()
        if (prompted.isEmpty()) {
            form.addComponent(JBLabel(JasperBundle.message("preview.params.none")).apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        for (p in prompted) {
            val field = fieldFor(p)
            field?.setText(previous[p.name])
            inputRows += Row(p.name, p.type, field)
            val label = JBLabel(p.name).apply {
                toolTipText = buildString {
                    append(p.type)
                    p.description?.let { append(" — ").append(it) }
                    p.defaultExpression?.let { append(" — default: ").append(it) }
                }
            }
            form.addLabeledComponent(label, field?.component ?: unsupported(p.type), LABEL_ON_TOP)
        }
        inputTab.removeAll()
        inputTab.add(form.addComponentFillVertically(JPanel(), 0).panel.apply { border = JBUI.Borders.empty(8) }, BorderLayout.CENTER)
        inputTab.revalidate()
        inputTab.repaint()
    }

    /** Valeurs saisies (paramètres d'entrée et du rapport) ; les champs vides ne sont pas envoyés. */
    fun values(): Map<String, ParameterValue> =
        (inputRows + reportRows).mapNotNull { row ->
            row.field?.text()?.let { row.name to ParameterValue(row.type, it) }
        }.toMap()

    /** Reset : tous les champs reviennent à « valeur par défaut ». */
    fun reset() {
        (inputRows + reportRows).forEach { it.field?.setText(null) }
    }

    private fun fieldFor(p: ReportParameterInfo): Field? {
        val hint = p.defaultExpression ?: "null"
        return when (p.type) {
            "java.lang.Boolean", "boolean" -> ChoiceField(listOf("true", "false"), editable = false,
                defaultLabel = JasperBundle.message("preview.params.default", hint))
            "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Double",
            "java.lang.Float", "java.math.BigDecimal", "java.util.Locale", "java.util.TimeZone" -> TextField(hint)
            "java.util.Date", "java.time.LocalDate" -> TextField("yyyy-MM-dd · $hint")
            "java.time.LocalDateTime", "java.sql.Timestamp" -> TextField("yyyy-MM-ddTHH:mm:ss · $hint")
            else -> null
        }
    }

    private fun unsupported(type: String): JComponent =
        TextField(JasperBundle.message("preview.params.unsupported", type.substringAfterLast('.')), enabled = false).component

    private fun buildReportTab(): JComponent {
        val default = JasperBundle.message("preview.params.default.plain")
        val locale = ChoiceField(listOf("fr_FR", "en_US", "en_GB", "de_DE", "es_ES", "it_IT"), editable = true, defaultLabel = default)
        val timeZone = ChoiceField(listOf("Europe/Paris", "UTC", "Europe/London", "America/New_York"), editable = true, defaultLabel = default)
        val maxCount = TextField(JasperBundle.message("preview.params.maxCount.hint"))
        val ignorePagination = ChoiceField(listOf("true", "false"), editable = false, defaultLabel = default)
        reportRows += Row("REPORT_LOCALE", "java.util.Locale", locale)
        reportRows += Row("REPORT_TIME_ZONE", "java.util.TimeZone", timeZone)
        reportRows += Row("REPORT_MAX_COUNT", "java.lang.Integer", maxCount)
        reportRows += Row("IS_IGNORE_PAGINATION", "java.lang.Boolean", ignorePagination)
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("REPORT_LOCALE").apply { toolTipText = JasperBundle.message("preview.params.locale.tip") }, locale.component, LABEL_ON_TOP)
            .addLabeledComponent(JBLabel("REPORT_TIME_ZONE"), timeZone.component, LABEL_ON_TOP)
            .addLabeledComponent(JBLabel("REPORT_MAX_COUNT"), maxCount.component, LABEL_ON_TOP)
            .addLabeledComponent(JBLabel("IS_IGNORE_PAGINATION"), ignorePagination.component, LABEL_ON_TOP)
            .addComponent(JBLabel(JasperBundle.message("preview.params.locale.tip")).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyTop(8)
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(8) }
    }
}
