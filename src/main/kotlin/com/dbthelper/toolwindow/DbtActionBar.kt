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
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Shared settings/action bar shown above the Lineage and Runner tabs. Always
 * visible — it is part of the tool window content, not the auto-hiding header.
 *
 * Two rows:
 *  1. target combo · verb combo · flags · extra-args | Clear · [RUN]
 *  2. selector field | read-only command preview
 *
 * Holds no execution logic — it exposes callbacks the coordinator wires up.
 */
class DbtActionBar(private val project: Project) : JPanel(BorderLayout()) {

    // --- callbacks set by DbtMainPanel ---
    var onGo: ((DbtCommandSpec) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onSelectorChanged: ((String) -> Unit)? = null
    var onSelectorEnter: ((String) -> Unit)? = null

    private val flagDiscovery = DbtFlagDiscovery(project)

    private val selectorField = JBTextField().apply {
        emptyText.text = "dbt selector (e.g. my_model or 1+my_model+2)"
        preferredSize = Dimension(360, preferredSize.height)
    }
    private val extraArgsField = JBTextField().apply {
        emptyText.text = "--threads 8 --vars '{k: v}'"
        toolTipText = "Extra dbt args, appended verbatim to the command"
        preferredSize = Dimension(200, preferredSize.height)
    }
    private val commandPreview = JBTextField().apply {
        isEditable = false
        toolTipText = "The exact dbt command [RUN] will run"
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
    private val goButton = JButton("[RUN]")

    private var running = false
    private var suppressSelectorEvents = false

    init {
        border = JBUI.Borders.empty(4)

        // Row 1: the run controls (previously hidden in the auto-hiding tool window
        // header). Left = configuration, right = the action buttons.
        val controlsLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("Target:"))
            add(targetCombo)
            add(JLabel("Verb:"))
            add(verbCombo)
            add(flagsButton)
            add(JLabel("Extra args:"))
            add(extraArgsField)
        }
        val controlsRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(clearButton)
            add(goButton)
        }
        val controlsRow = JPanel(BorderLayout()).apply {
            add(controlsLeft, BorderLayout.WEST)
            add(controlsRight, BorderLayout.EAST)
        }

        // Row 2: selector field + read-only command preview.
        val selectorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("dbt Select:"))
            add(selectorField)
        }
        val selectorRow = JPanel(BorderLayout(8, 0)).apply {
            add(selectorPanel, BorderLayout.WEST)
            add(commandPreview, BorderLayout.CENTER)
        }

        val rows = JPanel(BorderLayout(0, 4)).apply {
            add(controlsRow, BorderLayout.NORTH)
            add(selectorRow, BorderLayout.CENTER)
        }
        add(rows, BorderLayout.CENTER)

        initTargetCombo()
        initListeners()
        verbCombo.selectedItem = DbtVerb.RUN
        updateForVerb()
    }

    // --- public API used by DbtMainPanel ---

    fun setRunning(value: Boolean) {
        running = value
        goButton.text = if (value) "Stop" else "[RUN]"
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

    fun isRunning(): Boolean = running

    fun currentTarget(): String = (targetCombo.selectedItem as? String).orEmpty()

    /**
     * Pre-fill the selector field with [selector] and trigger [RUN] using the
     * currently configured verb, target, flags, and extra-args. No-op if a run
     * is already in progress (caller should use [promptToCancelAndRetry]).
     */
    fun goWithSelector(selector: String) {
        if (running) return
        setSelector(selector)
        goButton.doClick()
    }

    /**
     * Run an arbitrary [DbtCommandSpec], bypassing the verb dropdown. Used by
     * "Show preview rows" so the user does not lose their configured verb.
     */
    fun runSpec(spec: com.dbthelper.actions.DbtCommandSpec) {
        if (running) return
        onGo?.invoke(spec)
    }

    /**
     * If no run is in progress, behave like [goWithSelector]. Otherwise, prompt
     * the user to cancel the current run and start a new one with [selector].
     */
    fun promptToCancelAndRetry(parent: java.awt.Component, selector: String) {
        if (!running) { goWithSelector(selector); return }
        val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
            parent,
            "A run is in progress. Cancel current run and start a new one?",
            "Run In Progress",
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (choice == com.intellij.openapi.ui.Messages.YES) {
            onStop?.invoke()
            val timer = javax.swing.Timer(200, null)
            timer.addActionListener {
                if (!running) {
                    timer.stop()
                    goWithSelector(selector)
                }
            }
            timer.start()
        }
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
        // Enter in the selector field triggers the authoritative dbt ls resolution.
        selectorField.addActionListener {
            if (!suppressSelectorEvents && !running) {
                onSelectorEnter?.invoke(selectorField.text.trim())
            }
        }
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

    /**
     * Query discovery for the current verb (async) and repopulate the flags state.
     * The verb at request time is snapshotted; if the user switches verbs again
     * before the background thread returns, the stale result is dropped so the
     * bar never shows flags that don't belong to the currently-selected verb.
     */
    private fun refreshFlagsForVerb() {
        val verbAtRequest = selectedVerb()
        flagsButton.isEnabled = false
        flagsButton.text = "Flags …"
        flagDiscovery.flagsForAsync(verbAtRequest) { opts ->
            if (selectedVerb() != verbAtRequest) return@flagsForAsync
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
        // GENERATE_DOCS is intentionally absent — the verb still exists in the
        // enum because the Lineage tab's "Regenerate docs" button triggers it
        // programmatically (LineageTab.handleRegenerateDocs), but it has no
        // place in the user-facing dropdown.
        private val DROPDOWN_VERBS = listOf(
            DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.TEST, DbtVerb.COMPILE, DbtVerb.PREVIEW
        )
        private const val FLAGS_LABEL = "Flags ▾"
    }
}
