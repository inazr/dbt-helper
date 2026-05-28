package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtRunStatusParser
import com.dbthelper.actions.DbtVerb
import com.dbthelper.actions.RunResultsReconciler
import com.dbthelper.core.DocsPayloadBuilder
import com.dbthelper.core.LineageGraphBuilder
import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.LineageGraph
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ide.ui.LafManagerListener
import org.cef.browser.CefBrowser
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.UIManager

class LineageTab(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val actionBar: DbtActionBar? = null
) : JPanel(BorderLayout()), Disposable {

    private val logger = Logger.getInstance(LineageTab::class.java)
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val browser: JBCefBrowser = JBCefBrowser()
    private val jsQueryBridge: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var isPageReady = false

    @Volatile
    private var isDisposed = false

    // Depth overrides derived from a selector's graph operators (e.g. "2+model+1").
    // Null means "use the configured upstream/downstream depth from settings".
    // Set by focusModel; cleared when focus comes from the editor or a node click.
    @Volatile
    private var selectorUpstreamDepth: Int? = null

    @Volatile
    private var selectorDownstreamDepth: Int? = null

    // Expanded boundary nodes (not persisted to settings)
    private val expandedBoundaryNodes = mutableSetOf<String>()

    // Buildable node ids (model/seed/snapshot) of the most recently rendered graph;
    // used to seed "queued" at GO. Updated on every refreshGraph().
    @Volatile
    private var lastBuildableNodeIds: List<String> = emptyList()

    // Relation-key -> uniqueId lookup, built once at GO and cleared at run end.
    @Volatile
    private var runRelationKeyIndex: Map<String, String>? = null

    init {
        Disposer.register(parentDisposable, this)

        add(browser.component, BorderLayout.CENTER)

        setupJsBridge()
        setupLoadHandler()
        browser.jbCefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(
                browser: org.cef.browser.CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                params: org.cef.callback.CefContextMenuParams?,
                model: org.cef.callback.CefMenuModel?
            ) {
                model?.clear()
            }
        }, browser.cefBrowser)
        loadHtml()

        val connection = project.messageBus.connect(this)

        // Subscribe to manifest updates
        connection.subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) {
                resolveCurrentModel()
                refreshGraph()
                pushRegenerateAttention()
            }
        })

        // Subscribe to file changes
        connection.subscribe(CurrentModelListener.TOPIC, object : CurrentModelListener {
            override fun onCurrentModelChanged(file: VirtualFile) {
                onFileChanged(file)
            }
        })

        // Subscribe to settings changes
        connection.subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
            override fun onSettingsChanged() {
                refreshGraph()
                pushFailureBadgeSetting()
            }
        })

        // Subscribe to run-results updates
        connection.subscribe(
            com.dbthelper.actions.RunResultsUpdateListener.TOPIC,
            com.dbthelper.actions.RunResultsUpdateListener { results -> pushRunResultsToJs(results) }
        )
        pushRunResultsToJs(project.getService(com.dbthelper.listeners.RunResultsWatcher::class.java).current())

        // Listen for theme changes
        val appConnection = ApplicationManager.getApplication().messageBus.connect(this)
        appConnection.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyCurrentTheme() })

        // Pick up currently open file
        val currentFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (currentFile != null) {
            val service = ManifestService.getInstance(project)
            currentModelId = service.findCurrentModelId(currentFile)
        }
    }

    private fun setupJsBridge() {
        jsQueryBridge.addHandler { request ->
            try {
                val json = mapper.readTree(request)
                val type = json.path("type").asText()
                val payload = json.get("payload")

                when (type) {
                    "ready" -> {
                        isPageReady = true
                        refreshGraph()
                    }
                    "nodeClick" -> {
                        val nodeId = payload.path("nodeId").asText()
                        val resourceType = payload.path("resourceType").asText()
                        handleNodeClick(nodeId, resourceType)
                    }
                    "previewNode" -> {
                        val nodeId = payload.path("nodeId").asText()
                        pushDocsToSidebar(nodeId)
                    }
                    "expandRequest" -> {
                        val boundaryNodeId = payload.path("boundaryNodeId").asText()
                        val direction = payload.path("stubDirection").asText()
                        handleExpandRequest(boundaryNodeId, direction)
                    }
                    "regenerateDocs" -> handleRegenerateDocs()
                    "contextMenuRequest" -> {
                        val payloadObj = payload ?: return@addHandler JBCefJSQuery.Response("ok")
                        @Suppress("UNCHECKED_CAST")
                        val nodeIds = mapper.convertValue(payloadObj.get("nodeIds"), List::class.java)
                            ?.filterIsInstance<String>() ?: emptyList()
                        @Suppress("UNCHECKED_CAST")
                        val names = mapper.convertValue(payloadObj.get("names"), List::class.java)
                            ?.filterIsInstance<String>() ?: emptyList()
                        @Suppress("UNCHECKED_CAST")
                        val resourceTypes = mapper.convertValue(payloadObj.get("resourceTypes"), List::class.java)
                            ?.map { it as? String } ?: emptyList()
                        val screenX = payloadObj.get("screenX")?.asInt() ?: return@addHandler JBCefJSQuery.Response("ok")
                        val screenY = payloadObj.get("screenY")?.asInt() ?: return@addHandler JBCefJSQuery.Response("ok")
                        if (nodeIds.isEmpty()) return@addHandler JBCefJSQuery.Response("ok")
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed) showContextPopup(nodeIds, names, resourceTypes, screenX, screenY)
                        }
                    }
                    "multiSelectChanged" -> {
                        val count = payload?.get("count")?.asInt() ?: 0
                        if (count > 1) {
                            ApplicationManager.getApplication().invokeLater {
                                if (!isDisposed) executeJs("showMultiSelectPlaceholder($count)")
                            }
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                logger.warn("Error handling JS bridge message", e)
                JBCefJSQuery.Response(null, 1, e.message ?: "error")
            }
        }
    }

    private fun setupLoadHandler() {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    // Inject the bridge function into the page
                    val bridgeCode = jsQueryBridge.inject(
                        "request",
                        "function(response) {}",
                        "function(errorCode, errorMessage) { console.error('Bridge error:', errorCode, errorMessage); }"
                    )
                    val js = "window.__cefQueryBridge = function(request) { $bridgeCode };"
                    cefBrowser?.executeJavaScript(js, cefBrowser.url, 0)

                    // Inject project root hash for localStorage key namespacing
                    val rootPath = project.basePath ?: "default"
                    val hash = rootPath.hashCode().toString(16)
                    cefBrowser?.executeJavaScript("window.__projectRootHash = '$hash';", cefBrowser.url, 0)

                    // Page is loaded and bridge is injected — mark ready and render
                    isPageReady = true

                    // Apply IDE theme
                    applyCurrentTheme()

                    resolveCurrentModel()
                    refreshGraph()
                    pushRegenerateAttention()
                    pushFailureBadgeSetting()
                }
            }
        }, browser.cefBrowser)
    }

    private fun loadHtml() {
        val html = buildString {
            append(readResource("/js/lineage.html") ?: run {
                logger.error("lineage.html not found in resources")
                return
            })
        }
        // Inline JS files into the HTML
        val cytoscape = readResource("/js/cytoscape.min.js") ?: ""
        val dagre = readResource("/js/dagre.min.js") ?: ""
        val cytoscapeDagre = readResource("/js/cytoscape-dagre.js") ?: ""
        val lineageJs = readResource("/js/lineage.js") ?: ""

        val fullHtml = html
            .replace("<script src=\"cytoscape.min.js\"></script>", "<script>$cytoscape</script>")
            .replace("<script src=\"dagre.min.js\"></script>", "<script>$dagre</script>")
            .replace("<script src=\"cytoscape-dagre.js\"></script>", "<script>$cytoscapeDagre</script>")
            .replace("<script src=\"lineage.js\"></script>", "<script>$lineageJs</script>")

        browser.loadHTML(fullHtml)
    }

    private fun readResource(path: String): String? {
        return javaClass.getResourceAsStream(path)?.use { it.bufferedReader().readText() }
    }

    private fun resolveCurrentModel() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file)
        if (modelId != null) {
            currentModelId = modelId
        }
    }

    fun onFileChanged(file: VirtualFile) {
        if (isDisposed) return
        pushRegenerateAttention()
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        val modelId = service.findCurrentModelId(file)

        // If current model is a source/exposure in the same file, don't override
        // (user may have clicked a specific node in the graph)
        if (modelId != null && modelId != currentModelId) {
            val currentIsInSameFile = currentModelId?.let { curId ->
                val curPath = index.nodes[curId]?.originalFilePath
                    ?: index.sources[curId]?.originalFilePath
                    ?: index.exposures[curId]?.originalFilePath
                val newPath = index.nodes[modelId]?.originalFilePath
                    ?: index.sources[modelId]?.originalFilePath
                    ?: index.exposures[modelId]?.originalFilePath
                curPath != null && curPath == newPath
            } ?: false

            if (!currentIsInSameFile) {
                currentModelId = modelId
                expandedBoundaryNodes.clear()
                selectorUpstreamDepth = null
                selectorDownstreamDepth = null
                refreshGraph()
            }
        } else if (modelId != null && modelId == currentModelId) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) executeJs("highlightNode('${escapeJs(modelId)}')")
            }
        }
    }

    /**
     * Focus the graph on a model name (no-op if it doesn't resolve). Optional
     * depth overrides come from a selector's graph operators; null falls back to
     * the configured depth in settings. Refreshes when either the model or the
     * depth override changes, so editing "model" → "+model" re-renders.
     */
    fun focusModel(modelName: String, upstreamDepth: Int? = null, downstreamDepth: Int? = null) {
        if (isDisposed) return
        val modelId = ManifestService.getInstance(project).findModelIdByName(modelName) ?: return
        val modelChanged = modelId != currentModelId
        val depthChanged = upstreamDepth != selectorUpstreamDepth || downstreamDepth != selectorDownstreamDepth
        if (!modelChanged && !depthChanged) return
        if (modelChanged) {
            currentModelId = modelId
            expandedBoundaryNodes.clear()
        }
        selectorUpstreamDepth = upstreamDepth
        selectorDownstreamDepth = downstreamDepth
        refreshGraph()
    }

    /**
     * Refocus the lineage graph on [nodeId] without opening the file in the editor.
     * Clears any selector depth overrides and expanded boundary nodes, then re-renders.
     */
    fun refocusOnNode(nodeId: String) {
        if (isDisposed) return
        if (nodeId == currentModelId) return
        currentModelId = nodeId
        expandedBoundaryNodes.clear()
        selectorUpstreamDepth = null
        selectorDownstreamDepth = null
        refreshGraph()
    }

    /**
     * Open the source file for [nodeId] in the editor.
     * When [preferYaml] is true, prefer the node's patch_path (YAML schema file) if
     * available; otherwise fall back to [originalFilePath] (the SQL file).
     * The patch_path in the dbt manifest uses a "package://" prefix that is stripped.
     */
    fun openFileForNode(nodeId: String, preferYaml: Boolean) {
        if (isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) return@invokeLater
            val service = ManifestService.getInstance(project)
            val index = service.getIndex()
            val locator = service.getLocator()
            val dbtRoot = locator.findProjectRoot() ?: return@invokeLater

            val sqlPath: String?
            val yamlPath: String?

            when {
                index.sources.containsKey(nodeId) -> {
                    val src = index.sources[nodeId]!!
                    sqlPath = null
                    yamlPath = src.originalFilePath
                }
                index.exposures.containsKey(nodeId) -> {
                    val exp = index.exposures[nodeId]!!
                    sqlPath = null
                    yamlPath = exp.originalFilePath
                }
                else -> {
                    val node = index.nodes[nodeId]
                    sqlPath = node?.originalFilePath
                    // patchPath is stored as "package://relative/path.yml" — strip prefix
                    yamlPath = node?.patchPath?.let { pp ->
                        val sepIdx = pp.indexOf("://")
                        if (sepIdx >= 0) pp.substring(sepIdx + 3) else pp
                    }
                }
            }

            val relativePath = if (preferYaml && yamlPath != null) yamlPath else (sqlPath ?: yamlPath)
                ?: return@invokeLater

            val fullPath = "${dbtRoot.path}/$relativePath"
            val vFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    fun refreshGraph() {
        val modelId = currentModelId ?: return
        if (!isPageReady || isDisposed) return

        // Build graph off-EDT, then send to browser on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (isDisposed) return@executeOnPooledThread
                val service = ManifestService.getInstance(project)
                val index = service.getIndex()
                if (index === ManifestIndex.EMPTY) return@executeOnPooledThread

                val settings = DbtHelperSettings.getInstance(project)
                val catalogAvailable = service.getLocator().getCatalogFile() != null
                val builder = LineageGraphBuilder(index, project, catalogAvailable)
                val graph = builder.build(
                    currentNodeId = modelId,
                    upstreamDepth = selectorUpstreamDepth ?: settings.state.upstreamDepth,
                    downstreamDepth = selectorDownstreamDepth ?: settings.state.downstreamDepth,
                    showExposures = settings.state.showExposures,
                    expandedBoundaryNodes = expandedBoundaryNodes
                ).copy(
                    edgeCurveStyle = settings.state.edgeCurveStyle,
                    layoutDirection = settings.state.layoutDirection,
                    nodeColorMode = settings.state.nodeColorMode
                )

                lastBuildableNodeIds = graph.nodes
                    .filter { it.resourceType in RunResultsReconciler.BUILDABLE_TYPES }
                    .map { it.id }

                val graphJson = mapper.writeValueAsString(graph)
                val escaped = graphJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) executeJs("renderGraph('$escaped')")
                }
            } catch (e: Exception) {
                logger.warn("Error building lineage graph", e)
            }
        }

        // Update sidebar to reflect current model
        pushDocsToSidebar(modelId)
    }

    private fun applyCurrentTheme() {
        if (!isPageReady || isDisposed) return
        val bg = UIManager.getColor("Panel.background")
        val isDark = bg != null && (bg.red + bg.green + bg.blue) / 3 < 128
        executeJs("applyTheme($isDark)")
    }

    private fun handleNodeClick(nodeId: String, resourceType: String) {
        // Focus lineage on clicked node directly (don't wait for file open event)
        if (nodeId != currentModelId) {
            currentModelId = nodeId
            expandedBoundaryNodes.clear()
            selectorUpstreamDepth = null
            selectorDownstreamDepth = null
            refreshGraph()
        }

        // Push docs payload to the sidebar
        pushDocsToSidebar(nodeId)

        // Also open the file in editor
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) return@invokeLater
            val service = ManifestService.getInstance(project)
            val index = service.getIndex()
            val locator = service.getLocator()
            val dbtRoot = locator.findProjectRoot() ?: return@invokeLater

            val filePath = when (resourceType) {
                "source" -> index.sources[nodeId]?.originalFilePath
                "exposure" -> index.exposures[nodeId]?.originalFilePath
                else -> index.nodes[nodeId]?.originalFilePath
            } ?: return@invokeLater

            val fullPath = "${dbtRoot.path}/$filePath"
            val vFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun pushDocsToSidebar(nodeId: String) {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (isDisposed) return@executeOnPooledThread
                val service = ManifestService.getInstance(project)
                val index = service.getIndex()
                val payload = DocsPayloadBuilder.build(nodeId, index) ?: return@executeOnPooledThread
                val json = mapper.writeValueAsString(payload)
                val escaped = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) executeJs("showDocs('$escaped')")
                }
            } catch (e: Exception) {
                logger.warn("Error building docs payload", e)
            }
        }
    }

    /**
     * Build { "schema.identifier" / "database.schema.identifier" -> uniqueId }
     * for all buildable nodes, for resolving dbt log relations to unique ids.
     */
    private fun buildRelationKeyIndex(index: ManifestIndex): Map<String, String> {
        val map = HashMap<String, String>()
        for ((id, node) in index.nodes) {
            if (node.resourceType !in RunResultsReconciler.BUILDABLE_TYPES) continue
            val schema = node.schema ?: continue
            val identifier = node.alias ?: node.name
            map["$schema.$identifier".lowercase()] = id
            val db = node.database
            if (db != null) map["$db.$schema.$identifier".lowercase()] = id
        }
        return map
    }

    @Volatile
    private var regenerateRunning = false

    private fun handleRegenerateDocs() {
        if (regenerateRunning) return
        regenerateRunning = true
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) executeJs("setRegenerateRunning(true)")
        }
        val runner = DbtCommandRunner(project)
        val settings = DbtHelperSettings.getInstance(project)
        val spec = DbtCommandSpec(
            verb = DbtVerb.GENERATE_DOCS,
            selector = "",
            target = settings.state.activeTarget,
            previewLimit = 0
        )
        runner.run(spec, object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {}
            override fun onProcessStarted(process: Process) {}
            override fun onFinished(result: DbtCommandRunner.RunResult) {
                regenerateRunning = false
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) executeJs("setRegenerateRunning(false)")
                }
            }
        })
    }

    private fun pushRegenerateAttention() {
        if (!isPageReady || isDisposed) return
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val needs = if (file == null) false else {
            val service = ManifestService.getInstance(project)
            val locator = service.getLocator()
            val isInProject = locator.isInsideDbtProject(file)
            val ext = file.extension?.lowercase()
            val isModelFile = ext == "sql" && isInProject
            val resolvedId = service.findCurrentModelId(file)
            isModelFile && resolvedId == null
        }
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) executeJs("setRegenerateNeedsAttention($needs)")
        }
    }

    private fun handleExpandRequest(boundaryNodeId: String, direction: String = "") {
        if (direction == "skip") {
            // TODO: for "skip" stubs, ideally refocus on the first hidden downstream node.
            // For now, refocus on the boundary node so the user can navigate from there.
            handleNodeClick(boundaryNodeId, "model")
            return
        }
        expandedBoundaryNodes.add(boundaryNodeId)
        refreshGraph()
    }

    private fun showContextPopup(
        nodeIds: List<String>,
        names: List<String>,
        resourceTypes: List<String?>,
        screenX: Int,
        screenY: Int
    ) {
        val bar = actionBar ?: return
        val group = com.dbthelper.actions.context.LineageContextActionGroup.build(
            project, this, bar, nodeIds, names, resourceTypes
        )
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("DbtLineageContext", group)
        val component = browser.component
        val pt = java.awt.Point(screenX, screenY)
        javax.swing.SwingUtilities.convertPointFromScreen(pt, component)
        popup.component.show(component, pt.x, pt.y)
    }

    /** Called at GO: build the relation index and seed targeted nodes as queued (without clearing unrelated statuses). */
    fun beginRunStatus() {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            runRelationKeyIndex = buildRelationKeyIndex(ManifestService.getInstance(project).getIndex())
            val targetedIds = lastBuildableNodeIds
            val idsJson = escapeJsJson(mapper.writeValueAsString(targetedIds))
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                if (targetedIds.isNotEmpty()) executeJs("seedQueuedStatuses('$idsJson')")
            }
        }
    }

    /** Feed one runner output line; live-update the matching node's status. */
    fun onRunnerLine(line: String) {
        if (isDisposed) return
        val update = DbtRunStatusParser.parseLine(line) ?: return
        val uniqueId = runRelationKeyIndex?.get(update.relationKey) ?: return
        val escaped = escapeJsJson(mapper.writeValueAsString(mapOf(uniqueId to update.status)))
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) executeJs("setNodeStatuses('$escaped')")
        }
    }

    /** Called at run end: push authoritative statuses from target/run_results.json. */
    fun applyRunResults() {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            val service = ManifestService.getInstance(project)
            val dbtRoot = service.getLocator().findProjectRoot() ?: return@executeOnPooledThread
            val statuses = RunResultsReconciler.reconcile(java.io.File(dbtRoot.path), service.getIndex())
            runRelationKeyIndex = null
            val json = mapper.writeValueAsString(statuses)
            val escaped = escapeJsJson(json)
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) executeJs("applyRunResults('$escaped')")
            }
        }
    }

    private fun pushFailureBadgeSetting() {
        if (!isPageReady || isDisposed) return
        val show = DbtHelperSettings.getInstance(project).state.showTestFailureBadge
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) {
                browser.cefBrowser.executeJavaScript(
                    "window.__showFailureBadges = $show; if (window.repaintAllFailureBadges) window.repaintAllFailureBadges();",
                    browser.cefBrowser.url, 0
                )
            }
        }
    }

    private fun pushRunResultsToJs(results: Map<String, com.dbthelper.actions.RunResult>) {
        if (!isPageReady || isDisposed) return
        val payload = mapper.writeValueAsString(results.mapValues { (_, r) ->
            mapOf(
                "status" to r.status.wire,
                "message" to r.message,
                "failures" to (r.failures ?: 0),
                "startedAt" to r.startedAt?.toString(),
                "executionTime" to r.executionTime
            )
        })
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) {
                browser.cefBrowser.executeJavaScript(
                    "window.setRunResults && window.setRunResults(${payload});",
                    browser.cefBrowser.url, 0
                )
            }
        }
    }

    private fun escapeJsJson(json: String): String =
        json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    private fun executeJs(code: String) {
        if (!isDisposed) {
            browser.cefBrowser.executeJavaScript(code, browser.cefBrowser.url, 0)
        }
    }

    private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    override fun dispose() {
        isDisposed = true
        jsQueryBridge.dispose()
        browser.dispose()
    }
}
