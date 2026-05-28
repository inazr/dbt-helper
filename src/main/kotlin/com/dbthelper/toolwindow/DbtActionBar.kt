package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandBuilder
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtFlagDiscovery
import com.dbthelper.actions.DbtVerb
import com.dbthelper.core.ProfilesParser
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Shared settings/action bar shown above the Lineage and Runner tabs.
 * Row 1: selector field + extra-args field | read-only command preview.
 * Row 2: target combo, verb combo, flags multi-select, Clear, GO.
 *
 * Holds no execution logic — it exposes callbacks the coordinator wires up.
 */
class DbtActionBar(private val project: Project) : JPanel(BorderLayout()) {

    // --- callbacks set by DbtMainPanel ---
    var onGo: ((DbtCommandSpec) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onSelectorChanged: ((String) -> Unit)? = null

    private val flagDiscovery = DbtFlagDiscovery(project)

    private val selectorField = JBTextField().apply {
        emptyText.text = "dbt selector (e.g. my_model or 1+my_model+2)"
        preferredSize = Dimension(220, preferredSize.height)
    }
    private val extraArgsField = JBTextField().apply {
        emptyText.text = "--threads 8 --vars '{k: v}'"
        toolTipText = "Extra dbt args, appended verbatim to the command"
        preferredSize = Dimension(200, preferredSize.height)
    }
    private val commandPreview = JBTextField().apply {
        isEditable = false
        toolTipText = "The exact dbt command GO will run"
    }

    private val targetCombo = JComboBox<String>().apply {
        toolTipText = "dbt target"
        preferredSize = Dimension(120, preferredSize.height)
    }

    private val verbCombo = JComboBox(DROPDOWN_VERBS.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.display }
        toolTipText = "dbt command to run"
    }

    private val flagsButton = JButton(FLAGS_LABEL).apply {
        toolTipText = "Toggle flags for the selected command"
    }
    private val selectedFlags = LinkedHashSet<String>()
    private var availableFlags: List<DbtFlagDiscovery.FlagOption> = emptyList()

    private val clearButton = JButton("Clear").apply { toolTipText = "Clear log output" }
    private val goButton = JButton("GO")

    private var running = false
    private var suppressSelectorEvents = false

    init {
        border = JBUI.Borders.empty(4)

        val selectorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("dbt Select:"))
            add(selectorField)
            add(JLabel("Extra args:"))
            add(extraArgsField)
        }
        val row1 = JPanel(BorderLayout(8, 0)).apply {
            add(selectorPanel, BorderLayout.WEST)
            add(commandPreview, BorderLayout.CENTER)
        }

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Target:"))
            add(targetCombo)
            add(JLabel("Verb:"))
            add(verbCombo)
            add(flagsButton)
            add(Box.createHorizontalStrut(8))
            add(clearButton)
            add(goButton)
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row1)
            add(row2)
        }
        add(stack, BorderLayout.CENTER)

        initTargetCombo()
        initListeners()
        verbCombo.selectedItem = DbtVerb.RUN
        updateForVerb()
    }

    // --- public API used by DbtMainPanel ---

    fun setRunning(value: Boolean) {
        running = value
        goButton.text = if (value) "Stop" else "GO"
        selectorField.isEnabled = !value && selectedVerb().usesSelector
        extraArgsField.isEnabled = !value
        verbCombo.isEnabled = !value
        flagsButton.isEnabled = !value && availableFlags.isNotEmpty()
        clearButton.isEnabled = !value
        if (!value) updateGoEnabled()
    }

    /** Auto-fill the selector from the active editor (does not move the graph). */
    fun setSelector(text: String) {
        if (selectorField.text == text) return
        suppressSelectorEvents = true
        try {
            selectorField.text = text
        } finally {
            suppressSelectorEvents = false
        }
        updatePreview()
        updateGoEnabled()
    }

    fun refreshTargets() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        profiles.invalidateCache()
        val targets = profiles.getTargetNames()
        val current = targetCombo.selectedItem as? String
        targetCombo.actionListeners.forEach { targetCombo.removeActionListener(it) }
        targetCombo.removeAllItems()
        targets.forEach { targetCombo.addItem(it) }
        val restore = (current ?: settings.state.activeTarget).takeIf { targets.contains(it) }
        if (restore != null) targetCombo.selectedItem = restore
        addTargetListener(settings)
        // dbt exe / target may have changed → discovered flags could differ
        flagDiscovery.invalidate()
        refreshFlagsForVerb()
    }

    // --- internals ---

    private fun initTargetCombo() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        val targets = profiles.getTargetNames()
        val defaultTarget = profiles.getDefaultTarget()
        targetCombo.removeAllItems()
        targets.forEach { targetCombo.addItem(it) }
        val current = settings.state.activeTarget.ifBlank { defaultTarget ?: "" }
        if (current.isNotBlank() && targets.contains(current)) targetCombo.selectedItem = current
        addTargetListener(settings)
    }

    private fun addTargetListener(settings: DbtHelperSettings) {
        targetCombo.addActionListener {
            val selected = targetCombo.selectedItem as? String ?: return@addActionListener
            settings.state.activeTarget = selected
            updatePreview()
            project.messageBus.syncPublisher(SettingsChangeListener.TOPIC).onSettingsChanged()
        }
    }

    private fun initListeners() {
        selectorField.document.addDocumentListener(object : DocumentListener {
            private fun changed() {
                updatePreview()
                updateGoEnabled()
                if (!suppressSelectorEvents) onSelectorChanged?.invoke(selectorField.text.trim())
            }
            override fun insertUpdate(e: DocumentEvent?) = changed()
            override fun removeUpdate(e: DocumentEvent?) = changed()
            override fun changedUpdate(e: DocumentEvent?) = changed()
        })
        extraArgsField.document.addDocumentListener(object : DocumentListener {
            private fun changed() = updatePreview()
            override fun insertUpdate(e: DocumentEvent?) = changed()
            override fun removeUpdate(e: DocumentEvent?) = changed()
            override fun changedUpdate(e: DocumentEvent?) = changed()
        })
        verbCombo.addActionListener { updateForVerb() }
        flagsButton.addActionListener { showFlagsPopup() }
        clearButton.addActionListener { onClear?.invoke() }
        goButton.addActionListener {
            if (running) onStop?.invoke() else onGo?.invoke(currentSpec())
        }
    }

    private fun selectedVerb(): DbtVerb = verbCombo.selectedItem as DbtVerb

    private fun currentSpec(): DbtCommandSpec {
        val settings = DbtHelperSettings.getInstance(project)
        return DbtCommandSpec(
            verb = selectedVerb(),
            selector = selectorField.text.trim(),
            target = (targetCombo.selectedItem as? String).orEmpty(),
            toggleFlags = selectedFlags.toList(),
            extraArgs = extraArgsField.text.trim(),
            previewLimit = settings.state.previewRowLimit
        )
    }

    private fun updateForVerb() {
        val verb = selectedVerb()
        selectorField.isEnabled = !running && verb.usesSelector
        refreshFlagsForVerb()
        updatePreview()
        updateGoEnabled()
    }

    /** Query discovery for the current verb (async) and repopulate the flags state. */
    private fun refreshFlagsForVerb() {
        flagsButton.isEnabled = false
        flagsButton.text = "Flags …"
        flagDiscovery.flagsForAsync(selectedVerb()) { opts ->
            availableFlags = opts
            // Drop any selected flag that this verb does not offer.
            selectedFlags.retainAll(opts.map { it.token }.toSet())
            flagsButton.isEnabled = !running && opts.isNotEmpty()
            updateFlagsButtonText()
            updatePreview()
        }
    }

    private fun showFlagsPopup() {
        if (availableFlags.isEmpty()) return
        val list = CheckBoxList<DbtFlagDiscovery.FlagOption>()
        availableFlags.forEach { opt ->
            list.addItem(opt, opt.label, selectedFlags.contains(opt.token))
        }
        list.setCheckBoxListListener { index, value ->
            val opt = list.getItemAt(index) ?: return@setCheckBoxListListener
            if (value) selectedFlags.add(opt.token) else selectedFlags.remove(opt.token)
            updateFlagsButtonText()
            updatePreview()
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(list), list)
            .setTitle("Flags")
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(flagsButton)
    }

    private fun updateFlagsButtonText() {
        flagsButton.text = if (selectedFlags.isEmpty()) FLAGS_LABEL else "Flags (${selectedFlags.size}) ▾"
    }

    private fun updatePreview() {
        commandPreview.text = DbtCommandBuilder.buildDisplay(currentSpec())
    }

    private fun updateGoEnabled() {
        if (running) { goButton.isEnabled = true; return }
        val verb = selectedVerb()
        goButton.isEnabled = !verb.usesSelector || selectorField.text.isNotBlank()
    }

    companion object {
        private val DROPDOWN_VERBS = listOf(
            DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.TEST, DbtVerb.COMPILE, DbtVerb.PREVIEW
        )
        private const val FLAGS_LABEL = "Flags ▾"
    }
}
