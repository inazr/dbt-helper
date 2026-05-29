package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtRunStatusParser
import com.dbthelper.actions.DbtVerb
import com.dbthelper.actions.RunResultsReconciler
import com.dbthelper.core.DbtSelectorParser
import com.dbthelper.core.DocsPayloadBuilder
import com.dbthelper.core.FreshnessDetailBuilder
import com.dbthelper.core.LineageGraphBuilder
import com.dbthelper.core.ManifestService
import com.dbthelper.core.SourcesFreshnessParser
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
    // used as a fallback to seed "queued" at [RUN]. Updated on every refreshGraph().
    @Volatile
    private var lastBuildableNodeIds: List<String> = emptyList()

    // All non-stub node ids currently visible; used to find the first hidden hop
    // when the user clicks a "skip" stub.
    @Volatile
    private var lastVisibleNodeIds: Set<String> = emptySet()

    // Relation-key -> uniqueId lookup, built once at [RUN] and cleared at run end.
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
                        val direction = payload.path("direction").asText()
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
                        val clientX = payloadObj.get("clientX")?.asInt() ?: return@addHandler JBCefJSQuery.Response("ok")
                        val clientY = payloadObj.get("clientY")?.asInt() ?: return@addHandler JBCefJSQuery.Response("ok")
                        if (nodeIds.isEmpty()) return@addHandler JBCefJSQuery.Response("ok")
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed) showContextPopup(nodeIds, names, resourceTypes, clientX, clientY)
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
                    "clusterModeChanged" -> {
                        val mode = payload?.get("mode")?.asText() ?: "none"
                        com.dbthelper.settings.DbtHelperSettings.getInstance(project).state.defaultClusterMode = mode
                        refreshGraph()
                    }
                    "openFreshnessDetail" -> {
                        val nodeId = payload?.get("nodeId")?.asText() ?: return@addHandler JBCefJSQuery.Response("ok")
                        pushFreshnessDetailToSidebar(nodeId)
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

                    // Inject default cluster mode from settings
                    val clusterMode = com.dbthelper.settings.DbtHelperSettings.getInstance(project).state.defaultClusterMode
                    cefBrowser?.executeJavaScript(
                        "window.setClusterMode && window.setClusterMode('$clusterMode');",
                        cefBrowser.url, 0
                    )

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
        // Inline JS files into the HTML — JBCefBrowser.loadHTML uses about:blank as the
        // base URL, so any <script src="..."> in the page cannot be resolved at runtime.
        val cytoscape = readResource("/js/cytoscape.min.js") ?: ""
        val elkBundled = readResource("/js/elk.bundled.js") ?: ""
        val cytoscapeElk = readResource("/js/cytoscape-elk.js") ?: ""
        val lineageJs = readResource("/js/lineage.js") ?: ""

        val fullHtml = html
            .replace("<script src=\"cytoscape.min.js\"></script>", "<script>$cytoscape</script>")
            .replace("<script src=\"elk.bundled.js\"></script>", "<script>$elkBundled</script>")
            .replace("<script src=\"cytoscape-elk.js\"></script>", "<script>$cytoscapeElk</script>")
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
                val locator = service.getLocator()
                val catalogAvailable = locator.getCatalogFile() != null
                val sourcesFile = locator.getTargetDir()?.let { target ->
                    java.nio.file.Paths.get(target.path, "sources.json")
                }
                val freshness = sourcesFile?.let { SourcesFreshnessParser().parseFile(it) } ?: emptyMap()
                val builder = LineageGraphBuilder(index, project, catalogAvailable, freshness)
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
                lastVisibleNodeIds = graph.nodes
                    .filter { it.resourceType != "stub" && it.resourceType != "cluster" }
                    .map { it.id }
                    .toSet()

                val graphJson = mapper.writeValueAsString(graph)
                val escaped = escapeJsJson(graphJson)

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
        val payload = escapeJsJson(mapper.writeValueAsString(buildThemeVars()))
        executeJs("applyThemeColors('$payload')")
    }

    /**
     * Resolve the webview's CSS palette from the live IDE theme so the graph blends
     * with whatever Look-and-Feel is configured (e.g. a dark-blue theme), instead of
     * the old two hard-coded black/white palettes. Only two keys are read directly —
     * `Panel.background` and `Label.foreground`, which every L&F provides — and the
     * remaining surfaces/borders/muted text are derived from them by lightening,
     * darkening, or blending so the result can never break on a missing theme key.
     * Semantic node colors (status/resource bars) are deliberately not themed.
     */
    private fun buildThemeVars(): Map<String, Any> {
        val bg = UIManager.getColor("Panel.background") ?: java.awt.Color(0x1e, 0x1e, 0x1e)
        val fg = UIManager.getColor("Label.foreground") ?: java.awt.Color(0xcc, 0xcc, 0xcc)
        val isDark = luminance(bg) < 0.5
        val dir = if (isDark) 1.0 else -1.0
        val accent = UIManager.getColor("Component.focusColor")
            ?: UIManager.getColor("Link.activeForeground")
            ?: java.awt.Color(0x21, 0x96, 0xF3)

        val cardBg = shift(bg, 0.06 * dir)
        val cardBorder = shift(bg, 0.20 * dir)
        val cardIconBg = shift(bg, -0.03 * dir)
        val muted = blend(fg, bg, 0.45)
        val edge = blend(fg, bg, 0.62)

        return mapOf(
            "isDark" to isDark,
            "vars" to mapOf(
                "--bg-color" to hex(bg),
                "--text-color" to hex(fg),
                "--card-name" to hex(fg),
                "--card-schema" to hex(muted),
                "--card-bg" to hex(cardBg),
                "--card-border" to hex(cardBorder),
                "--card-icon-bg" to hex(cardIconBg),
                "--card-icon-fg" to hex(muted),
                "--card-selected-border" to hex(accent),
                "--card-selected-bg" to hex(cardBg),
                "--tooltip-bg" to hex(cardBg),
                "--tooltip-border" to hex(cardBorder),
                "--tooltip-text" to hex(fg),
                "--tooltip-name" to hex(fg),
                "--tooltip-detail" to hex(muted),
                "--btn-bg" to hex(cardBg),
                "--btn-border" to hex(cardBorder),
                "--btn-text" to hex(fg),
                "--btn-hover" to hex(shift(cardBg, 0.08 * dir)),
                "--edge-color" to hex(edge),
                "--loading-color" to hex(muted),
                "--stub-bg" to hex(shift(bg, 0.03 * dir)),
                "--stub-border" to hex(cardBorder),
                "--stub-text" to hex(muted)
            )
        )
    }

    private fun luminance(c: java.awt.Color): Double =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    /** Lighten ([amount] > 0, toward white) or darken ([amount] < 0, toward black) [c]. */
    private fun shift(c: java.awt.Color, amount: Double): java.awt.Color =
        if (amount >= 0) blend(java.awt.Color.WHITE, c, amount) else blend(java.awt.Color.BLACK, c, -amount)

    /** Linear interpolation: [t] of [a] mixed with (1-[t]) of [b]. */
    private fun blend(a: java.awt.Color, b: java.awt.Color, t: Double): java.awt.Color {
        val tt = t.coerceIn(0.0, 1.0)
        fun mix(x: Int, y: Int) = Math.round(x * tt + y * (1 - tt)).toInt().coerceIn(0, 255)
        return java.awt.Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue))
    }

    private fun hex(c: java.awt.Color): String = "#%06x".format(0xFFFFFF and c.rgb)

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
                val escaped = escapeJsJson(json)
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) executeJs("showDocs('$escaped')")
                }
            } catch (e: Exception) {
                logger.warn("Error building docs payload", e)
            }
        }
    }

    private fun pushFreshnessDetailToSidebar(nodeId: String) {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (isDisposed) return@executeOnPooledThread
                val service = ManifestService.getInstance(project)
                val index = service.getIndex()
                val sourcesFile = service.getLocator().getTargetDir()?.let { target ->
                    java.nio.file.Paths.get(target.path, "sources.json")
                }
                val available = sourcesFile?.let { java.nio.file.Files.exists(it) } ?: false
                val freshness = sourcesFile?.let { SourcesFreshnessParser().parseFile(it) } ?: emptyMap()
                val payload = FreshnessDetailBuilder.build(nodeId, index, freshness, available)
                    ?: return@executeOnPooledThread
                val json = mapper.writeValueAsString(payload)
                val escaped = escapeJsJson(json)
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) executeJs("showFreshnessDetail('$escaped')")
                }
            } catch (e: Exception) {
                logger.warn("Error building freshness detail payload", e)
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
            val index = ManifestService.getInstance(project).getIndex()
            val firstHidden = index.getDownstream(boundaryNodeId)
                .firstOrNull { it !in lastVisibleNodeIds }
            val target = firstHidden ?: boundaryNodeId
            val resourceType = index.nodes[target]?.resourceType
                ?: index.sources[target]?.let { "source" }
                ?: index.exposures[target]?.let { "exposure" }
                ?: "model"
            handleNodeClick(target, resourceType)
            return
        }
        expandedBoundaryNodes.add(boundaryNodeId)
        refreshGraph()
    }

    private fun showContextPopup(
        nodeIds: List<String>,
        names: List<String>,
        resourceTypes: List<String?>,
        clientX: Int,
        clientY: Int
    ) {
        val bar = actionBar ?: return
        val group = com.dbthelper.actions.context.LineageContextActionGroup.build(
            project, this, bar, nodeIds, names, resourceTypes
        )
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("DbtLineageContext", group)
        // JS clientX/Y are viewport-relative; JCEF fills its Swing component, so they
        // map directly to browser.component coordinates (no screen conversion needed).
        popup.component.show(browser.component, clientX, clientY)
    }

    /**
     * Called at [RUN]: build the relation index and seed as queued exactly the nodes
     * that `dbt build --select <selector>` will build (without clearing unrelated
     * statuses). Falls back to the rendered graph's buildable nodes when [selector]
     * is outside the graph-operator grammar we can resolve.
     */
    fun beginRunStatus(selector: String) {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            val index = ManifestService.getInstance(project).getIndex()
            runRelationKeyIndex = buildRelationKeyIndex(index)
            val targetedIds = resolveSelectorPathIds(index, selector) ?: lastBuildableNodeIds
            val idsJson = escapeJsJson(mapper.writeValueAsString(targetedIds))
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                if (targetedIds.isNotEmpty()) executeJs("seedQueuedStatuses('$idsJson')")
            }
        }
    }

    /**
     * Resolve the buildable node ids that [selector] actually targets, so the queue
     * highlights exactly what `dbt build --select <selector>` builds — not the wider
     * context the graph pads around the focused model.
     *
     * Each whitespace-separated token is parsed as a graph-operator selector; a bare
     * name (no `+`) targets only that node (depth 0/0), matching dbt — unlike the graph
     * view, which expands bare names to the configured display depth. Returns null if
     * any token falls outside the grammar (wildcards, `tag:`, …) so the caller can fall
     * back to the rendered graph's buildable nodes rather than guess.
     */
    private fun resolveSelectorPathIds(index: ManifestIndex, selector: String): List<String>? {
        val tokens = selector.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val result = LinkedHashSet<String>()
        for (token in tokens) {
            val focus = DbtSelectorParser.parse(token) ?: return null
            val startId = index.nodes.entries.firstOrNull {
                it.value.name == focus.modelName &&
                    it.value.resourceType in RunResultsReconciler.BUILDABLE_TYPES
            }?.key ?: continue
            result += startId
            collectReachable(index, startId, focus.upstreamDepth ?: 0, upstream = true, into = result)
            collectReachable(index, startId, focus.downstreamDepth ?: 0, upstream = false, into = result)
        }
        return result.filter { index.nodes[it]?.resourceType in RunResultsReconciler.BUILDABLE_TYPES }
    }

    /** BFS from [startId] up to [depth] levels along parents (upstream) or children, collecting ids into [into]. */
    private fun collectReachable(
        index: ManifestIndex,
        startId: String,
        depth: Int,
        upstream: Boolean,
        into: MutableSet<String>
    ) {
        if (depth <= 0) return
        val visited = hashSetOf(startId)
        var frontier = listOf(startId)
        var remaining = depth
        while (remaining > 0 && frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (id in frontier) {
                val neighbours = if (upstream) index.getUpstream(id) else index.getDownstream(id)
                for (n in neighbours) if (visited.add(n)) { into.add(n); next += n }
            }
            frontier = next
            remaining--
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
