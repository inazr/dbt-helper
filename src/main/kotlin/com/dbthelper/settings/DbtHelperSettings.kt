package com.dbthelper.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "DbtHelperSettings",
    storages = [Storage("dbt-helper.xml")]
)
class DbtHelperSettings : PersistentStateComponent<DbtHelperSettings.State> {

    data class State(
        var dbtExecutablePath: String = "dbt",
        var dbtProjectRootOverride: String = "",
        var activeTarget: String = "",
        var upstreamDepth: Int = 2,
        var downstreamDepth: Int = 1,
        var autoOpenOnSqlFile: Boolean = true,
        var showExposures: Boolean = true,
        var edgeCurveStyle: String = "round-taxi",
        var layoutDirection: String = "LR",
        var showCompiledCode: Boolean = false,
        var previewRowLimit: Int = 10,
        var enableSystemNotifications: Boolean = true,
        var enableColoredOutput: Boolean = false,
        var autoParseOnSave: Boolean = true,
        var autoParseOnCloudCli: Boolean = false,
        // How lineage node bar colors are derived: "resource" | "schema" | "status".
        var nodeColorMode: String = "status",
        // Edges spanning more than this many layers are replaced with an inline stub node.
        var maxLayerSkipBeforeStub: Int = 3,
        // Whether to show the red failure-count badge on lineage cards.
        var showTestFailureBadge: Boolean = true,
        // Cluster mode for lineage grouping: "none" | "schema" | "folder" | "tag".
        var defaultClusterMode: String = "schema",
        // Bumped when a settings default changes so loadState can migrate old data.
        // Absent in pre-migration saved files, so it deserializes to 0 there.
        var configVersion: Int = 0
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        migrate()
    }

    /** Fresh install (no saved state): already on current defaults. */
    override fun noStateLoaded() {
        myState.configVersion = CURRENT_CONFIG_VERSION
    }

    private fun migrate() {
        if (myState.configVersion < 1) {
            // v1: lineage depth default lowered from 5/5 to 2/1. Adopt it only for
            // users still on the old default — preserve any deliberate customization.
            if (myState.upstreamDepth == 5) myState.upstreamDepth = 2
            if (myState.downstreamDepth == 5) myState.downstreamDepth = 1
        }
        if (myState.configVersion < 2) {
            // v2: default node color mode changed from "resource" to "status" so run
            // progress is visible by default. Flip users still on the old default;
            // a deliberate "schema" choice is preserved.
            if (myState.nodeColorMode == "resource") myState.nodeColorMode = "status"
        }
        myState.configVersion = CURRENT_CONFIG_VERSION
    }

    companion object {
        private const val CURRENT_CONFIG_VERSION = 2

        fun getInstance(project: Project): DbtHelperSettings =
            project.service<DbtHelperSettings>()
    }
}
