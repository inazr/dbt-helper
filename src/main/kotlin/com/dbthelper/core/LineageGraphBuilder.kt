package com.dbthelper.core

import com.dbthelper.core.model.*
import java.util.LinkedList

class LineageGraphBuilder(
    private val index: ManifestIndex,
    private val project: com.intellij.openapi.project.Project? = null,
    private val catalogAvailable: Boolean = false,
    private val freshnessByUniqueId: Map<String, com.dbthelper.core.SourceFreshness> = emptyMap()
) {

    fun build(
        currentNodeId: String,
        upstreamDepth: Int = 5,
        downstreamDepth: Int = 5,
        showExposures: Boolean = true,
        expandedBoundaryNodes: Set<String> = emptySet()
    ): LineageGraph {
        val visitedNodes = mutableMapOf<String, Int>() // id -> depth
        val edges = mutableListOf<LineageEdge>()

        // BFS upstream (negative depth)
        val upstreamResult = bfs(
            startId = currentNodeId,
            maxDepth = upstreamDepth,
            direction = Direction.UPSTREAM,
            visitedNodes = visitedNodes,
            edges = edges
        )

        // BFS downstream (positive depth)
        val downstreamResult = bfs(
            startId = currentNodeId,
            maxDepth = downstreamDepth,
            direction = Direction.DOWNSTREAM,
            visitedNodes = visitedNodes,
            edges = edges
        )

        // Expand specific boundary nodes with extra depth
        val expandStep = 5
        for (boundaryId in expandedBoundaryNodes) {
            if (boundaryId in upstreamResult.boundaryHiddenCounts) {
                bfs(
                    startId = boundaryId,
                    maxDepth = expandStep,
                    direction = Direction.UPSTREAM,
                    visitedNodes = visitedNodes,
                    edges = edges
                )
            }
            if (boundaryId in downstreamResult.boundaryHiddenCounts) {
                bfs(
                    startId = boundaryId,
                    maxDepth = expandStep,
                    direction = Direction.DOWNSTREAM,
                    visitedNodes = visitedNodes,
                    edges = edges
                )
            }
        }

        // Ensure current node is included
        visitedNodes[currentNodeId] = 0

        var lineageNodes = visitedNodes.mapNotNull { (id, depth) ->
            toLineageNode(id, depth, id == currentNodeId)
        }.toMutableList()

        // Tests are never shown as separate cards
        lineageNodes = lineageNodes.filter { it.resourceType != "test" }.toMutableList()
        if (!showExposures) {
            lineageNodes = lineageNodes.filter { it.resourceType != "exposure" }.toMutableList()
        }

        // Cluster nodes into compound parent groups based on the active cluster mode
        val clusterMode = project?.let {
            com.dbthelper.settings.DbtHelperSettings.getInstance(it).state.defaultClusterMode
        } ?: "none"

        if (clusterMode != "none") {
            val parentNodesById = mutableMapOf<String, LineageNode>()
            val withParents = lineageNodes.map { n ->
                if (n.resourceType == "stub" || n.resourceType == "cluster") return@map n
                val pid = parentIdFor(n, clusterMode)
                if (pid == null) {
                    n
                } else {
                    if (!parentNodesById.containsKey(pid)) {
                        parentNodesById[pid] = LineageNode(
                            id = pid,
                            name = parentLabelFor(pid),
                            resourceType = "cluster",
                            schema = null, database = null, materialization = null,
                            filePath = null, description = null, columns = emptyList(),
                            depth = 0,
                            isCurrent = false,
                            isParent = true
                        )
                    }
                    n.copy(parent = pid)
                }
            }.toMutableList()
            withParents.addAll(0, parentNodesById.values.toList())
            lineageNodes.clear()
            lineageNodes.addAll(withParents)
        }

        // Recalculate stub counts — exclude nodes already visible after expands
        val visibleNodeIds = visitedNodes.keys
        val stubEdges = mutableListOf<LineageEdge>()
        for ((boundaryId, _) in upstreamResult.boundaryHiddenCounts) {
            if (boundaryId in expandedBoundaryNodes) continue
            // Count upstream neighbors of this boundary that are NOT in the visible graph
            val hiddenNeighbors = getNeighbors(boundaryId, Direction.UPSTREAM).count { it !in visibleNodeIds }
            if (hiddenNeighbors == 0) continue
            val stubId = "__stub_upstream_$boundaryId"
            lineageNodes.add(LineageNode(
                id = stubId,
                name = "+ $hiddenNeighbors more",
                resourceType = "stub",
                schema = null, database = null, materialization = null,
                filePath = null, description = null, columns = emptyList(),
                depth = -(upstreamDepth + 1),
                isCurrent = false,
                stubDirection = "upstream",
                boundaryNodeId = boundaryId
            ))
            stubEdges.add(LineageEdge(fromNodeId = stubId, toNodeId = boundaryId))
        }
        for ((boundaryId, _) in downstreamResult.boundaryHiddenCounts) {
            if (boundaryId in expandedBoundaryNodes) continue
            val hiddenNeighbors = getNeighbors(boundaryId, Direction.DOWNSTREAM).count { it !in visibleNodeIds }
            if (hiddenNeighbors == 0) continue
            val stubId = "__stub_downstream_$boundaryId"
            lineageNodes.add(LineageNode(
                id = stubId,
                name = "+ $hiddenNeighbors more",
                resourceType = "stub",
                schema = null, database = null, materialization = null,
                filePath = null, description = null, columns = emptyList(),
                depth = downstreamDepth + 1,
                isCurrent = false,
                stubDirection = "downstream",
                boundaryNodeId = boundaryId
            ))
            stubEdges.add(LineageEdge(fromNodeId = boundaryId, toNodeId = stubId))
        }

        // Filter edges — only keep edges where both endpoints are in the graph
        val nodeIds = lineageNodes.map { it.id }.toSet()
        val validEdges = edges.distinct().filter { it.fromNodeId in nodeIds && it.toNodeId in nodeIds } + stubEdges

        // --- Long-jump stub insertion ---
        val skipThreshold = project?.let {
            com.dbthelper.settings.DbtHelperSettings.getInstance(it).state.maxLayerSkipBeforeStub
        } ?: 3

        val realEdges = validEdges.filter { e ->
            !e.fromNodeId.startsWith("__stub_") && !e.toNodeId.startsWith("__stub_")
        }
        val layerMap = computeLayers(
            realEdges.flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet(),
            realEdges
        )

        val skipEdges = realEdges.filter { e ->
            val fl = layerMap[e.fromNodeId] ?: return@filter false
            val tl = layerMap[e.toNodeId] ?: return@filter false
            (tl - fl) > skipThreshold
        }

        if (skipEdges.isEmpty()) {
            return LineageGraph(
                currentNodeId = currentNodeId,
                nodes = lineageNodes,
                edges = validEdges,
                hiddenUpstreamCount = upstreamResult.hiddenCount,
                hiddenDownstreamCount = downstreamResult.hiddenCount,
                catalogAvailable = catalogAvailable
            )
        }

        val keptEdges = validEdges - skipEdges.toSet()
        val newStubNodes = mutableListOf<LineageNode>()
        val newStubEdges = mutableListOf<LineageEdge>()
        for (e in skipEdges) {
            val gap = (layerMap[e.toNodeId]!! - layerMap[e.fromNodeId]!! - 1)
            val stubId = "__stub_skip_${e.fromNodeId}__to__${e.toNodeId}"
            newStubNodes.add(LineageNode(
                id = stubId,
                name = "$gap hidden hops",
                resourceType = "stub",
                schema = null, database = null, materialization = null,
                filePath = null, description = null, columns = emptyList(),
                depth = 0,
                isCurrent = false,
                stubDirection = "skip",
                boundaryNodeId = e.fromNodeId
            ))
            newStubEdges.add(LineageEdge(fromNodeId = e.fromNodeId, toNodeId = stubId))
            newStubEdges.add(LineageEdge(fromNodeId = stubId, toNodeId = e.toNodeId))
        }

        return LineageGraph(
            currentNodeId = currentNodeId,
            nodes = lineageNodes + newStubNodes,
            edges = keptEdges + newStubEdges,
            hiddenUpstreamCount = upstreamResult.hiddenCount,
            hiddenDownstreamCount = downstreamResult.hiddenCount,
            catalogAvailable = catalogAvailable
        )
    }

    data class BfsResult(val hiddenCount: Int, val boundaryHiddenCounts: Map<String, Int>)

    private fun bfs(
        startId: String,
        maxDepth: Int,
        direction: Direction,
        visitedNodes: MutableMap<String, Int>,
        edges: MutableList<LineageEdge>
    ): BfsResult {
        val queue = LinkedList<Pair<String, Int>>() // (nodeId, currentDepth)
        val visited = mutableSetOf(startId)
        var hiddenCount = 0
        val boundaryHiddenCounts = mutableMapOf<String, Int>() // boundaryNodeId -> count of hidden beyond it

        // Seed with immediate neighbors
        val neighbors = getNeighbors(startId, direction)
        for (neighborId in neighbors) {
            queue.add(neighborId to 1)
            addEdge(edges, startId, neighborId, direction)
        }

        while (queue.isNotEmpty()) {
            val (nodeId, depth) = queue.poll()

            if (nodeId in visited) continue
            visited.add(nodeId)

            if (depth > maxDepth) {
                hiddenCount++
                continue
            }

            val signedDepth = if (direction == Direction.UPSTREAM) -depth else depth
            // Keep the depth closest to current node
            val existing = visitedNodes[nodeId]
            if (existing == null || kotlin.math.abs(signedDepth) < kotlin.math.abs(existing)) {
                visitedNodes[nodeId] = signedDepth
            }

            val nextNeighbors = getNeighbors(nodeId, direction)
            // Track boundary nodes — at maxDepth with hidden neighbors beyond
            if (depth == maxDepth) {
                val hiddenNeighbors = nextNeighbors.count { it !in visited }
                if (hiddenNeighbors > 0) {
                    boundaryHiddenCounts[nodeId] = hiddenNeighbors
                }
            }
            for (nextId in nextNeighbors) {
                addEdge(edges, nodeId, nextId, direction)
                if (nextId !in visited) {
                    queue.add(nextId to depth + 1)
                }
            }
        }

        return BfsResult(hiddenCount, boundaryHiddenCounts)
    }

    private fun getNeighbors(nodeId: String, direction: Direction): List<String> {
        return when (direction) {
            Direction.UPSTREAM -> index.getUpstream(nodeId)
            Direction.DOWNSTREAM -> index.getDownstream(nodeId)
        }
    }

    private fun addEdge(
        edges: MutableList<LineageEdge>,
        fromId: String,
        toId: String,
        direction: Direction
    ) {
        when (direction) {
            Direction.UPSTREAM -> edges.add(LineageEdge(fromNodeId = toId, toNodeId = fromId))
            Direction.DOWNSTREAM -> edges.add(LineageEdge(fromNodeId = fromId, toNodeId = toId))
        }
    }

    private fun toLineageNode(id: String, depth: Int, isCurrent: Boolean): LineageNode? {
        // Try nodes first
        index.nodes[id]?.let { node ->
            val hints = SearchIndexBuilder.buildHints(
                uniqueId = id,
                name = node.name,
                schema = node.schema,
                materialization = node.config["materialized"] as? String,
                resourceType = node.resourceType,
                packageName = node.packageName,
                columnNames = node.columns.keys.toList(),
                tags = node.tags
            )
            return LineageNode(
                id = id,
                name = node.name,
                resourceType = node.resourceType,
                schema = node.schema,
                database = node.database,
                materialization = node.config["materialized"] as? String,
                filePath = node.originalFilePath,
                description = node.description.ifEmpty { null },
                columns = node.columns.values.map { col ->
                    ColumnNode(col.name, col.dataType, col.description.ifEmpty { null }, col.isPrimaryKey)
                },
                depth = depth,
                isCurrent = isCurrent,
                searchHints = hints
            )
        }

        // Try sources
        index.sources[id]?.let { source ->
            val hints = SearchIndexBuilder.buildHints(
                uniqueId = id,
                name = "${source.sourceName}.${source.name}",
                schema = source.schema,
                materialization = null,
                resourceType = "source",
                packageName = source.packageName,
                columnNames = source.columns.keys.toList(),
                tags = source.tags
            )
            return LineageNode(
                id = id,
                name = "${source.sourceName}.${source.name}",
                resourceType = "source",
                schema = source.schema,
                database = source.database,
                materialization = null,
                filePath = source.originalFilePath,
                description = source.description.ifEmpty { null },
                columns = source.columns.values.map { col ->
                    ColumnNode(col.name, col.dataType, col.description.ifEmpty { null }, col.isPrimaryKey)
                },
                depth = depth,
                isCurrent = isCurrent,
                searchHints = hints,
                freshness = freshnessByUniqueId[id]
            )
        }

        // Try exposures
        index.exposures[id]?.let { exposure ->
            val hints = SearchIndexBuilder.buildHints(
                uniqueId = id,
                name = exposure.name,
                schema = null,
                materialization = null,
                resourceType = "exposure",
                packageName = exposure.packageName,
                columnNames = emptyList(),
                tags = exposure.tags
            )
            return LineageNode(
                id = id,
                name = exposure.name,
                resourceType = "exposure",
                schema = null,
                database = null,
                materialization = null,
                filePath = exposure.originalFilePath,
                description = exposure.description.ifEmpty { null },
                columns = emptyList(),
                depth = depth,
                isCurrent = isCurrent,
                searchHints = hints
            )
        }

        return null
    }

    /**
     * Compute a layer index per node: root upstream nodes get 0; for others,
     * layer = max(layer of upstream-neighbors) + 1.
     */
    private fun computeLayers(
        nodeIds: Set<String>,
        edges: List<LineageEdge>
    ): Map<String, Int> {
        val parents = nodeIds.associateWith { mutableListOf<String>() }
        edges.forEach { e ->
            if (e.fromNodeId in nodeIds && e.toNodeId in nodeIds) {
                parents.getValue(e.toNodeId).add(e.fromNodeId)
            }
        }
        val layer = mutableMapOf<String, Int>()
        val resolving = mutableSetOf<String>()
        fun resolve(id: String): Int {
            layer[id]?.let { return it }
            if (id in resolving) return 0 // cycle guard
            resolving.add(id)
            val ps = parents[id].orEmpty()
            val v = if (ps.isEmpty()) 0 else (ps.maxOf { resolve(it) } + 1)
            resolving.remove(id)
            layer[id] = v
            return v
        }
        nodeIds.forEach { resolve(it) }
        return layer
    }

    private fun parentIdFor(node: LineageNode, mode: String): String? = when (mode) {
        "schema" -> if (node.resourceType == "source") {
            val srcGroup = node.name.substringBefore('.', missingDelimiterValue = "_")
            "cluster_source_$srcGroup"
        } else {
            "cluster_schema_${node.schema ?: "_"}"
        }
        "folder" -> {
            val path = node.filePath?.toUnixPath()?.removePrefix("models/")
            val seg = path?.substringBefore('/', missingDelimiterValue = "")
            if (seg.isNullOrBlank()) null else "cluster_folder_$seg"
        }
        "tag" -> {
            // Check manifest index for tags, fall back to searchHints
            val tags = index.nodes[node.id]?.tags?.map { it.lowercase() }
                ?: index.sources[node.id]?.tags?.map { it.lowercase() }
                ?: node.searchHints?.tagsLower?.toList().orEmpty()
            when {
                tags.isEmpty() -> "cluster_tag_<no-tag>"
                tags.size > 1 -> "cluster_tag_<multiple-tags>"
                else -> "cluster_tag_${tags[0]}"
            }
        }
        else -> null
    }

    private fun parentLabelFor(parentId: String): String = when {
        parentId.startsWith("cluster_schema_") -> parentId.removePrefix("cluster_schema_")
        parentId.startsWith("cluster_source_") -> "source: " + parentId.removePrefix("cluster_source_")
        parentId.startsWith("cluster_folder_") -> parentId.removePrefix("cluster_folder_")
        parentId.startsWith("cluster_tag_")    -> parentId.removePrefix("cluster_tag_")
        else -> ""
    }

    private enum class Direction {
        UPSTREAM, DOWNSTREAM
    }
}
