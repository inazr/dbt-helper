// dbt Lineage Graph — Cytoscape.js + Dagre
(function () {
    'use strict';

    // Color is applied as the left side bar on each card.
    const NODE_BAR_COLORS = {
        model_view:   '#3F7BD9',
        model_table:  '#7C5CE6',
        model:        '#3F7BD9',
        source:       '#3FB950',
        seed:         '#FF9800',
        snapshot:     '#9C27B0',
        exposure:     '#E91E63',
        test:         '#B39DDB',
        stub:         '#9E9E9E'
    };
    // Categorical palette for schema-based coloring (stable, well-separated hues).
    const SCHEMA_PALETTE = [
        '#4E79A7', '#F28E2B', '#59A14F', '#E15759', '#B07AA1', '#76B7B2',
        '#EDC948', '#FF9DA7', '#9C755F', '#499894', '#D37295', '#8CD17D'
    ];
    // Fallback for nodes without a schema (e.g. exposures) in schema mode.
    const NEUTRAL_BAR_COLOR = '#9E9E9E';

    // Run-status colors (used when nodeColorMode === 'status'). Set live from Kotlin.
    const STATUS_BAR_COLORS = {
        queued:  '#3F7BD9',
        running: '#5AC8FA',
        success: '#3FB950',
        warn:    '#E3B341',
        error:   '#F85149',
        skipped: '#C9CED6'
    };

    const ELK_LAYOUT_OPTIONS = {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.edgeRouting': 'ORTHOGONAL',
        'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
        'elk.layered.spacing.nodeNodeBetweenLayers': '80',
        'elk.spacing.nodeNode': '40',
        'elk.layered.crossingMinimization.semiInteractive': 'true',
        'elk.hierarchyHandling': 'INCLUDE_CHILDREN'
    };

    function elkDirectionFor(layoutDir) {
        return layoutDir === 'TB' ? 'DOWN' : 'RIGHT';
    }

    var KNOWN_PREFIXES = new Set(['col', 'tag', 'mat', 'schema', 'type', 'pkg']);

    function tokenize(query) {
        if (!query || !query.trim()) return [];
        return query.trim().split(/\s+/).map(function (raw) {
            var colon = raw.indexOf(':');
            if (colon <= 0 || colon === raw.length - 1) return { kind: 'bare', value: raw };
            var key = raw.substring(0, colon).toLowerCase();
            var value = raw.substring(colon + 1);
            if (KNOWN_PREFIXES.has(key)) return { kind: key, value: value.toLowerCase() };
            return { kind: 'bare', value: raw };
        });
    }

    var layoutCache = (function () {
        var MAX = 20;
        var map = new Map();
        function hash(key) {
            var h = 0;
            for (var i = 0; i < key.length; i++) { h = (h * 31 + key.charCodeAt(i)) | 0; }
            return String(h);
        }
        return {
            keyFor: function (currentNodeId, nodes, edges, clusterMode, expandedIds) {
                var nIds = nodes.map(function (n) { return n.data.id + ':' + n.data.w + 'x' + n.data.h; }).sort().join('|');
                var eIds = edges.map(function (e) { return e.data.id; }).sort().join('|');
                var ex = (expandedIds || []).slice().sort().join(',');
                return hash(currentNodeId + '##' + (clusterMode || '') + '##' + ex + '##' + nIds + '##' + eIds);
            },
            get: function (k) {
                if (!map.has(k)) return null;
                var v = map.get(k);
                map.delete(k); map.set(k, v); // LRU bump
                return v;
            },
            put: function (k, positions) {
                if (map.has(k)) map.delete(k);
                else if (map.size >= MAX) { map.delete(map.keys().next().value); }
                map.set(k, positions);
            },
            clear: function () { map.clear(); }
        };
    })();

    function schemaColor(schema) {
        var h = 0;
        for (var i = 0; i < schema.length; i++) {
            h = (h * 31 + schema.charCodeAt(i)) | 0;
        }
        return SCHEMA_PALETTE[Math.abs(h) % SCHEMA_PALETTE.length];
    }

    const CARD_WIDTH = 220;
    const CARD_HEIGHT = 56;
    const STUB_WIDTH = 110;
    const STUB_HEIGHT = 40;

    function cardHeightFor(data) {
        if (!expandedIds.has(data.id)) return CARD_HEIGHT;
        var rows = (data.columns && data.columns.length) || 1;
        return CARD_HEIGHT + Math.min(rows * 22, 240);
    }

    // Inline SVG icons keyed by visual variant.
    const ICONS = {
        source: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3.5" y="3" width="9" height="10" rx="1.5"/><path d="M7 8h3.5M9 6.5L10.5 8 9 9.5"/></svg>',
        view: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="3" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11"/><text x="8" y="11.5" font-size="5" font-weight="700" fill="currentColor" stroke="none" text-anchor="middle" font-family="-apple-system, sans-serif">V</text></svg>',
        table: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="3" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M2.5 10h11M6 6.5V13M10 6.5V13"/></svg>',
        seed: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 13V7M5 9c0-1.5 1.3-3 3-3s3 1.5 3 3M4.5 13h7"/></svg>',
        snapshot: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="4.5" width="11" height="8" rx="1.5"/><circle cx="8" cy="8.5" r="2"/><path d="M6 4.5l1-1h2l1 1"/></svg>',
        exposure: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2.5 13V8.5M6 13V5.5M9.5 13V7M13 13V3.5"/></svg>',
        test: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3.5 8.5l3 3 6-7"/></svg>'
    };

    function pickIconKey(node) {
        if (node.resourceType === 'source') return 'source';
        if (node.resourceType === 'seed') return 'seed';
        if (node.resourceType === 'snapshot') return 'snapshot';
        if (node.resourceType === 'exposure') return 'exposure';
        // model: pick by materialization
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return 'table';
        return 'view';
    }

    function pickBarColor(node, colorMode) {
        if (colorMode === 'status') {
            var st = nodeStatus[node.id];
            return (st && STATUS_BAR_COLORS[st]) || NEUTRAL_BAR_COLOR;
        }
        if (colorMode === 'schema') {
            return node.schema ? schemaColor(node.schema) : NEUTRAL_BAR_COLOR;
        }
        if (node.resourceType !== 'model') return NODE_BAR_COLORS[node.resourceType] || '#888';
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return NODE_BAR_COLORS.model_table;
        return NODE_BAR_COLORS.model_view;
    }

    let cy = null;
    let lastCurrentNodeId = null;
    let currentLayoutDir = 'LR';
    let previousNodeIds = new Set();
    let nodeCards = {};
    var nodeSearchHints = {};
    let currentColorMode = 'resource';
    let nodeStatus = {}; // uniqueId -> status string (see STATUS_BAR_COLORS keys)
    var nodeFailures = {}; // uniqueId -> failure count (integer)
    var nodeFailureMessages = {}; // uniqueId -> failure message string
    var selectedIds = new Set();
    let activeDrag = null;

    var expandedIds = new Set();

    function projectStorageKey(suffix) {
        return 'dbtHelper.' + suffix + '.' + (window.__projectRootHash || 'default');
    }

    function loadExpandedFromStorage() {
        try {
            var raw = localStorage.getItem(projectStorageKey('expandedNodes'));
            if (!raw) return;
            var arr = JSON.parse(raw);
            if (Array.isArray(arr)) expandedIds = new Set(arr);
        } catch (e) {}
    }
    function saveExpandedToStorage() {
        try {
            localStorage.setItem(projectStorageKey('expandedNodes'), JSON.stringify(Array.from(expandedIds)));
        } catch (e) {}
    }

    window.addEventListener('mousemove', function (e) {
        if (!activeDrag || !cy) return;
        var dx = e.clientX - activeDrag.startX;
        var dy = e.clientY - activeDrag.startY;
        if (!activeDrag.moved && Math.hypot(dx, dy) < 4) return;
        activeDrag.moved = true;
        var node = cy.getElementById(activeDrag.id);
        if (!node || !node.length) return;
        var rect = cy.container().getBoundingClientRect();
        var pan = cy.pan();
        var zoom = cy.zoom();
        var modelX = (e.clientX - rect.left - pan.x) / zoom;
        var modelY = (e.clientY - rect.top - pan.y) / zoom;
        node.position({ x: modelX, y: modelY });
    });
    var pendingClickTimer = null;
    var lastClickedId = null;

    var hoverActive = false;
    function applyHoverHighlight(nodeId) {
        if (!cy) return;
        // Disable hover-highlight when other state is active.
        if (lastClickedId === nodeId) return; // single-click neighborhood dim
        var hasSearchDim = false;
        cy.edges().some(function (e) {
            if (e.hasClass('dimmed')) { hasSearchDim = true; return true; }
            return false;
        });
        if (hasSearchDim) return;
        var node = cy.getElementById(nodeId);
        if (!node.length) return;
        var incident = node.connectedEdges();
        incident.addClass('hot');
        cy.edges().not(incident).addClass('cold');
        hoverActive = true;
    }
    function clearHoverHighlight() {
        if (!cy || !hoverActive) return;
        cy.edges().removeClass('hot').removeClass('cold');
        hoverActive = false;
    }

    function toggleMultiSelect(id) {
        if (selectedIds.has(id)) selectedIds.delete(id);
        else selectedIds.add(id);
        Object.keys(nodeCards).forEach(function (cid) {
            var c = nodeCards[cid];
            if (c) c.classList.toggle('selected', selectedIds.has(cid));
        });
        notifyMultiSelectChanged();
    }
    function clearMultiSelect() {
        if (selectedIds.size === 0) return;
        selectedIds.clear();
        Object.keys(nodeCards).forEach(function (cid) {
            var c = nodeCards[cid];
            if (c) c.classList.remove('selected');
        });
        notifyMultiSelectChanged();
    }
    function notifyMultiSelectChanged() {
        sendToKotlin('multiSelectChanged', { count: selectedIds.size });
    }

    function dimToNeighborhood(nodeId) {
        if (!cy) return;
        var center = cy.getElementById(nodeId);
        if (!center.length) return;
        var keep = center.union(center.predecessors()).union(center.successors());
        cy.elements().forEach(function (el) {
            var card = nodeCards[el.id()];
            if (keep.contains(el)) {
                el.removeClass('dimmed');
                if (card) card.classList.remove('dimmed');
            } else {
                el.addClass('dimmed');
                if (card) card.classList.add('dimmed');
            }
        });
        // Highlight the clicked card
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('selected'); });
        var clickedCard = nodeCards[nodeId];
        if (clickedCard) clickedCard.classList.add('selected');
    }

    function clearNeighborhoodDim() {
        if (!cy) return;
        cy.elements().removeClass('dimmed');
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('dimmed'); });
    }

    // Click on empty graph area clears dim
    document.getElementById('cy').addEventListener('click', function () {
        clearNeighborhoodDim();
        clearMultiSelect();
    });

    window.addEventListener('mouseup', function () {
        if (!activeDrag) return;
        var d = activeDrag;
        activeDrag = null;
        if (d.card) d.card.style.cursor = '';
        if (d.moved) return;

        if (d.data.resourceType === 'stub') {
            sendToKotlin('expandRequest', { direction: d.data.stubDirection, boundaryNodeId: d.data.boundaryNodeId });
            return;
        }

        // Distinguish single vs double click manually
        if (pendingClickTimer && lastClickedId === d.data.id) {
            // Double click — focus (refresh graph + open file)
            clearTimeout(pendingClickTimer);
            pendingClickTimer = null;
            lastClickedId = null;
            clearNeighborhoodDim();
            sendToKotlin('nodeClick', { nodeId: d.data.id, resourceType: d.data.resourceType });
            return;
        }

        // Single click — dim everything except this node's ancestors/descendants,
        // and push docs for it without re-focusing the graph
        if (pendingClickTimer) clearTimeout(pendingClickTimer);
        lastClickedId = d.data.id;
        var nodeId = d.data.id;
        var resourceType = d.data.resourceType;
        pendingClickTimer = setTimeout(function () {
            pendingClickTimer = null;
            lastClickedId = null;
            dimToNeighborhood(nodeId);
            sendToKotlin('previewNode', { nodeId: nodeId, resourceType: resourceType });
        }, 260);
    });
    const tooltipEl = document.getElementById('tooltip');
    const loadingEl = document.getElementById('loading');
    const overlayEl = document.getElementById('node-overlay');

    function renderColumnsInto(card, data) {
        if (!data.columns || !data.columns.length) {
            var banner = document.createElement('div');
            banner.className = 'card-banner';
            banner.textContent = 'Run `dbt docs generate` for column types';
            card.appendChild(banner);
            return;
        }
        var wrap = document.createElement('div');
        wrap.className = 'card-columns';
        wrap.addEventListener('wheel', function (e) {
            e.stopPropagation();
        }, { passive: true });
        data.columns.forEach(function (c) {
            var row = document.createElement('div');
            row.className = 'col-row';
            var name = document.createElement('span');
            name.className = 'col-name';
            if (c.isPrimaryKey) {
                var pk = document.createElement('span');
                pk.className = 'pk-badge';
                pk.textContent = 'PK';
                name.appendChild(pk);
            }
            name.appendChild(document.createTextNode(c.name));
            var type = document.createElement('span');
            type.className = 'col-type';
            type.textContent = c.type || '';
            row.appendChild(name);
            row.appendChild(type);
            wrap.appendChild(row);
        });
        card.appendChild(wrap);
    }

    function toggleExpand(nodeId) {
        if (expandedIds.has(nodeId)) expandedIds.delete(nodeId);
        else expandedIds.add(nodeId);
        saveExpandedToStorage();
        rerenderCardAndRelayout(nodeId);
    }

    function rerenderCardAndRelayout(nodeId) {
        if (!cy) return;
        var node = cy.getElementById(nodeId);
        if (!node.length) return;
        var oldRendered = node.renderedPosition();
        var data = node.data();
        var newHeight = cardHeightFor(data);
        node.data('h', newHeight);

        var elkOpts = Object.assign({}, ELK_LAYOUT_OPTIONS, {
            'elk.direction': elkDirectionFor(currentLayoutDir)
        });
        cy.layout({ name: 'elk', fit: false, elk: elkOpts, workerUrl: 'elk.worker.js' })
            .run()
            .promiseOn('layoutstop').then(function () {
                // Anchor: pan by difference so the toggled node stays under the cursor
                var newRendered = node.renderedPosition();
                var pan = cy.pan();
                cy.pan({ x: pan.x + (oldRendered.x - newRendered.x), y: pan.y + (oldRendered.y - newRendered.y) });
                buildNodeCards();
            });
    }

    function buildNodeCards() {
        // Clear existing cards
        Object.keys(nodeCards).forEach(function (id) {
            var el = nodeCards[id];
            if (el && el.parentNode) el.parentNode.removeChild(el);
        });
        nodeCards = {};

        cy.nodes().forEach(function (node) {
            var data = node.data();
            if (data.isParent) return;

            var card = document.createElement('div');
            card.className = 'card-node';
            card.dataset.id = data.id;

            if (data.resourceType === 'stub') {
                card.classList.add('stub');
                var name = document.createElement('div');
                name.className = 'card-name';
                name.textContent = data.name || '+ more';
                card.appendChild(name);
            } else {
                card.style.setProperty('--card-bar-color', data.barColor);
                if (currentColorMode === 'status' && nodeStatus[data.id] === 'running') {
                    card.classList.add('running');
                }

                // Wrap main row content
                var mainRow = document.createElement('div');
                mainRow.className = 'card-main-row';

                var bar = document.createElement('div');
                bar.className = 'card-bar';
                card.appendChild(bar);

                var icon = document.createElement('div');
                icon.className = 'card-icon';
                icon.innerHTML = ICONS[data.iconKey] || ICONS.view;
                mainRow.appendChild(icon);

                var text = document.createElement('div');
                text.className = 'card-text';
                var name2 = document.createElement('div');
                name2.className = 'card-name';
                name2.textContent = data.name;
                text.appendChild(name2);
                if (data.schema) {
                    var schema = document.createElement('div');
                    schema.className = 'card-schema';
                    schema.textContent = data.schema;
                    text.appendChild(schema);
                }
                if (data.resourceType === 'source' && data.freshness && data.freshness.status !== 'pass') {
                    var fresh = document.createElement('div');
                    fresh.className = 'card-freshness fresh-' + data.freshness.status;
                    fresh.textContent = data.freshness.status === 'error' ? '⬤' : '●';
                    fresh.title = 'Freshness: ' + data.freshness.status + (data.freshness.message ? ' — ' + data.freshness.message : '');
                    fresh.addEventListener('click', function (e) {
                        e.stopPropagation();
                        sendToKotlin('openFreshnessDetail', { nodeId: data.id });
                    });
                    text.appendChild(fresh);
                }
                mainRow.appendChild(text);

                var badge = document.createElement('div');
                badge.className = 'card-failure-badge';
                badge.textContent = '';
                card.appendChild(badge);
                card.classList.add('no-failure-badge');

                var canExpand = !!(data.columns && data.columns.length) || data.resourceType === 'model' || data.resourceType === 'source' || data.resourceType === 'seed' || data.resourceType === 'snapshot';
                if (canExpand) {
                    var toggle = document.createElement('div');
                    toggle.className = 'card-toggle';
                    toggle.textContent = expandedIds.has(data.id) ? '▾' : '▸';
                    toggle.addEventListener('mousedown', function (ev) { ev.stopPropagation(); });
                    toggle.addEventListener('click', function (ev) {
                        ev.stopPropagation();
                        toggleExpand(data.id);
                    });
                    mainRow.appendChild(toggle);
                }

                card.appendChild(mainRow);

                if (canExpand && expandedIds.has(data.id)) {
                    renderColumnsInto(card, data);
                }
            }

            if (data.isCurrent) card.classList.add('selected');

            // Drag + click handling — actual drag/up listeners are global (see below).
            card.addEventListener('mousedown', function (e) {
                if (e.button !== 0) {
                    // right-click (button 2) → context menu handled by separate listener
                    return;
                }
                e.preventDefault();
                e.stopPropagation();
                hideTooltip();
                if (e.shiftKey || e.metaKey || e.ctrlKey) {
                    // Toggle multi-select; do NOT begin drag
                    toggleMultiSelect(data.id);
                    return;
                }
                activeDrag = { id: data.id, data: data, card: card, startX: e.clientX, startY: e.clientY, moved: false };
                card.style.cursor = 'grabbing';
            });
            card.addEventListener('mouseenter', function (e) {
                if (activeDrag) return;
                applyHoverHighlight(data.id);
                showTooltip({ x: e.clientX, y: e.clientY }, data);
            });
            card.addEventListener('mousemove', function (e) {
                if (activeDrag) return;
                moveTooltip({ x: e.clientX, y: e.clientY });
            });
            card.addEventListener('mouseleave', function () {
                clearHoverHighlight();
                hideTooltip();
            });

            overlayEl.appendChild(card);
            card.addEventListener('contextmenu', function (e) {
                e.preventDefault();
                e.stopPropagation();
                var ids;
                if (selectedIds.size > 0 && selectedIds.has(data.id)) {
                    ids = Array.from(selectedIds);
                } else {
                    // Right-click on an unselected card: act on that card only
                    clearMultiSelect();
                    selectedIds.add(data.id);
                    var c = nodeCards[data.id];
                    if (c) c.classList.add('selected');
                    ids = [data.id];
                }
                var types = ids.map(function (id) {
                    var n = cy.getElementById(id);
                    return n.length ? n.data('resourceType') : null;
                });
                var names = ids.map(function (id) {
                    var n = cy.getElementById(id);
                    return n.length ? n.data('name') : id;
                });
                sendToKotlin('contextMenuRequest', {
                    nodeIds: ids,
                    names: names,
                    resourceTypes: types,
                    screenX: e.screenX,
                    screenY: e.screenY
                });
            });
            nodeCards[data.id] = card;
        });
        syncNodeCards();
    }

    var syncRaf = null;
    function syncNodeCards() {
        if (syncRaf) return;
        syncRaf = requestAnimationFrame(function () {
            syncRaf = null;
            if (!cy) return;
            var zoom = cy.zoom();
            cy.nodes().forEach(function (node) {
                var card = nodeCards[node.id()];
                if (!card) return;
                var pos = node.renderedPosition();
                var w = node.width();
                var h = node.height();
                var renderedW = w * zoom;
                var renderedH = h * zoom;
                var left = pos.x - renderedW / 2;
                var top = pos.y - renderedH / 2;
                card.style.width = w + 'px';
                card.style.height = h + 'px';
                card.style.transform = 'translate(' + left + 'px, ' + top + 'px) scale(' + zoom + ')';
            });
        });
    }

    function initCytoscape(elements, currentNodeId, edgeCurveStyle, layoutDirection) {
        currentLayoutDir = layoutDirection || 'LR';
        // Save viewport if re-rendering
        var savedZoom = null;
        var savedPan = null;
        var isRerender = cy && lastCurrentNodeId === currentNodeId;
        if (isRerender) {
            savedZoom = cy.zoom();
            savedPan = cy.pan();
        }
        if (cy) {
            previousNodeIds = new Set();
            cy.nodes().forEach(function (n) { previousNodeIds.add(n.id()); });
            cy.destroy();
        }
        lastCurrentNodeId = currentNodeId;

        cy = cytoscape({
            container: document.getElementById('cy'),
            elements: elements,
            layout: { name: 'preset', fit: false },
            style: [
                {
                    selector: 'node',
                    style: {
                        // Native rendering hidden — HTML overlay does the drawing.
                        'label': '',
                        'background-opacity': 0,
                        'border-width': 0,
                        'width': 'data(w)',
                        'height': 'data(h)',
                        'shape': 'round-rectangle'
                    }
                },
                {
                    selector: 'node[?isParent]',
                    style: {
                        'background-opacity': 0.04,
                        'border-width': 1,
                        'border-color': '#888',
                        'border-opacity': 0.4,
                        'shape': 'round-rectangle',
                        'label': 'data(name)',
                        'text-valign': 'top',
                        'text-halign': 'left',
                        'font-size': 11,
                        'color': '#888',
                        'padding': 16
                    }
                },
                {
                    selector: 'edge',
                    style: {
                        'width': 1.5,
                        'line-color': '#999',
                        'target-arrow-color': '#999',
                        'target-arrow-shape': 'triangle',
                        'curve-style': edgeCurveStyle || 'bezier',
                        'arrow-scale': 0.8
                    }
                },
                {
                    selector: 'edge.hot',
                    style: {
                        'opacity': 1,
                        'width': 2.5,
                        'line-color': '#4E79A7',
                        'target-arrow-color': '#4E79A7'
                    }
                },
                {
                    selector: 'edge.cold',
                    style: {
                        'opacity': 0.1
                    }
                },
                {
                    selector: 'node.dimmed',
                    style: { 'opacity': 0.25 }
                },
                {
                    selector: 'edge.dimmed',
                    style: { 'opacity': 0.15 }
                },
                {
                    selector: 'edge.stub-edge',
                    style: {
                        'width': 1,
                        'line-style': 'dashed',
                        'line-color': '#aaa',
                        'target-arrow-color': '#aaa'
                    }
                }
            ],
            minZoom: 0.2,
            maxZoom: 3,
            zoomingEnabled: true,
            userZoomingEnabled: false
        });

        // Node click
        cy.on('tap', 'node', function (evt) {
            const data = evt.target.data();
            if (data.resourceType === 'stub') {
                sendToKotlin('expandRequest', { direction: data.stubDirection, boundaryNodeId: data.boundaryNodeId });
            } else {
                sendToKotlin('nodeClick', { nodeId: data.id, resourceType: data.resourceType });
            }
        });

        // Hover tooltip
        cy.on('mouseover', 'node', function (evt) {
            const data = evt.target.data();
            showTooltip(evt.renderedPosition, data);
        });

        cy.on('mouseout', 'node', function () {
            hideTooltip();
        });

        cy.on('mousemove', 'node', function (evt) {
            moveTooltip(evt.renderedPosition);
        });

        function finalizeLayout() {
            loadingEl.style.display = 'none';

            // Restore viewport or center on current node
            if (isRerender && savedZoom && savedPan) {
                cy.zoom(savedZoom);
                cy.pan(savedPan);
            } else {
                // First render — fit graph, then center on current node
                cy.fit(undefined, 30);
                var currentNode = cy.getElementById(currentNodeId);
                if (currentNode.length) {
                    cy.center(currentNode);
                }
            }

            // Fade in new nodes and edges
            if (isRerender && previousNodeIds.size > 0) {
                cy.nodes().forEach(function (node) {
                    if (!previousNodeIds.has(node.id())) {
                        node.style('opacity', 0);
                        node.animate({ style: { opacity: 1 } }, { duration: 300, complete: function () {
                            node.removeStyle('opacity');
                        }});
                    }
                });
                cy.edges().forEach(function (edge) {
                    var srcNew = !previousNodeIds.has(edge.source().id());
                    var tgtNew = !previousNodeIds.has(edge.target().id());
                    if (srcNew || tgtNew) {
                        edge.style('opacity', 0);
                        edge.animate({ style: { opacity: 1 } }, { duration: 300, complete: function () {
                            edge.removeStyle('opacity');
                        }});
                    }
                });
            }

            // Build HTML cards over cytoscape
            buildNodeCards();
            cy.on('pan zoom position layoutstop', syncNodeCards);
            cy.on('pan zoom layoutstop', drawMinimap);

            // Manual wheel/pinch zoom for JCEF trackpad compatibility
            var cyContainer = document.getElementById('cy');

            function applyZoom(factor, x, y) {
                var zoom = cy.zoom() * factor;
                zoom = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), zoom));
                cy.zoom({ level: zoom, renderedPosition: { x: x, y: y } });
            }

            // Wheel event — handles scroll and ctrl+scroll (pinch on some systems)
            cyContainer.addEventListener('wheel', function (e) {
                if (!cy) return;
                e.preventDefault();
                var delta = e.deltaY;
                var sensitivity = e.ctrlKey ? 0.01 : 0.001;
                applyZoom(1 - delta * sensitivity, e.offsetX, e.offsetY);
            }, { passive: false });

            // Zoom control buttons — 5% step
            document.getElementById('zoom-in').addEventListener('click', function () {
                if (!cy) return;
                applyZoom(1.02, cy.width() / 2, cy.height() / 2);
            });
            document.getElementById('zoom-out').addEventListener('click', function () {
                if (!cy) return;
                applyZoom(1 / 1.02, cy.width() / 2, cy.height() / 2);
            });
            document.getElementById('zoom-fit').addEventListener('click', function () {
                if (!cy) return;
                cy.fit(undefined, 30);
            });

            // Keyboard zoom: +/- and =/- keys (skip when typing in search)
            document.addEventListener('keydown', function (e) {
                if (!cy) return;
                if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
                var cx = cy.width() / 2;
                var cy2 = cy.height() / 2;
                if (e.key === '+' || e.key === '=' || (e.key === '=' && e.metaKey)) {
                    e.preventDefault();
                    applyZoom(1.02, cx, cy2);
                } else if (e.key === '-' || e.key === '_') {
                    e.preventDefault();
                    applyZoom(1 / 1.02, cx, cy2);
                } else if (e.key === '0') {
                    e.preventDefault();
                    cy.fit(undefined, 30);
                }
            });
        }

        var elkOpts = Object.assign({}, ELK_LAYOUT_OPTIONS, {
            'elk.direction': elkDirectionFor(layoutDirection)
        });
        var cacheKey = layoutCache.keyFor(
            currentNodeId,
            elements.filter(function (e) { return e.data && !e.data.source; }),
            elements.filter(function (e) { return e.data && e.data.source; }),
            null,
            []
        );
        var cached = layoutCache.get(cacheKey);
        if (cached) {
            cy.nodes().forEach(function (n) {
                var p = cached[n.id()];
                if (p) n.position({ x: p.x, y: p.y });
            });
            finalizeLayout();
        } else {
            cy.layout({ name: 'elk', fit: false, elk: elkOpts, workerUrl: 'elk.worker.js' })
                .run()
                .promiseOn('layoutstop').then(function () {
                    var pos = {};
                    cy.nodes().forEach(function (n) { var p = n.position(); pos[n.id()] = { x: p.x, y: p.y }; });
                    layoutCache.put(cacheKey, pos);
                    finalizeLayout();
                });
        }
    }


    function showTooltip(pos, data) {
        var html = '<div class="tt-name">' + escapeHtml(data.label) + '</div>';
        html += '<div class="tt-detail">';
        html += 'Type: ' + data.resourceType;
        if (data.schema) html += '<br>Schema: ' + escapeHtml(data.schema);
        if (data.database) html += '<br>Database: ' + escapeHtml(data.database);
        if (data.materialization) html += '<br>Materialization: ' + data.materialization;
        if (data.description) html += '<br>' + escapeHtml(data.description.substring(0, 150));
        html += '</div>';
        tooltipEl.innerHTML = html;
        tooltipEl.style.display = 'block';
        moveTooltip(pos);
    }

    function moveTooltip(pos) {
        tooltipEl.style.left = (pos.x + 15) + 'px';
        tooltipEl.style.top = (pos.y + 15) + 'px';
    }

    function hideTooltip() {
        tooltipEl.style.display = 'none';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // === Public API (called from Kotlin) ===

    // Recolor a single card from its current nodeStatus entry (status mode only).
    function applyStatusToCard(id) {
        var card = nodeCards[id];
        if (!card || card.classList.contains('stub')) return;
        var st = nodeStatus[id];
        card.style.setProperty('--card-bar-color', (st && STATUS_BAR_COLORS[st]) || NEUTRAL_BAR_COLOR);
        card.classList.toggle('running', st === 'running');
    }

    // Repaint every card from nodeStatus (status mode only). Not a graph re-render.
    function repaintAllStatusCards() {
        Object.keys(nodeCards).forEach(applyStatusToCard);
    }

    function repaintAllFailureBadges() {
        var showBadges = window.__showFailureBadges !== false; // default true
        Object.keys(nodeCards).forEach(function (id) {
            var card = nodeCards[id];
            if (!card || card.classList.contains('stub')) return;
            var n = nodeFailures[id] || 0;
            var badge = card.querySelector('.card-failure-badge');
            if (badge) badge.textContent = n > 0 ? String(n) : '';
            card.classList.toggle('no-failure-badge', !(showBadges && n > 0));
        });
    }
    window.repaintAllFailureBadges = repaintAllFailureBadges;

    // Merge {uniqueId: status} into the store; live-update cards if in status mode.
    window.setNodeStatuses = function (jsonStr) {
        try {
            var map = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            Object.keys(map).forEach(function (id) { nodeStatus[id] = map[id]; });
            if (currentColorMode === 'status') {
                Object.keys(map).forEach(applyStatusToCard);
            }
        } catch (e) { console.error('setNodeStatuses error:', e); }
    };

    // Replace the store wholesale (authoritative final state). Absent ids -> neutral.
    window.applyRunResults = function (jsonStr) {
        try {
            nodeStatus = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            if (currentColorMode === 'status') repaintAllStatusCards();
        } catch (e) { console.error('applyRunResults error:', e); }
    };

    // Clear all statuses (called at GO before seeding queued).
    window.clearNodeStatuses = function () {
        nodeStatus = {};
        if (currentColorMode === 'status') repaintAllStatusCards();
    };

    window.seedQueuedStatuses = function (idsJson) {
        try {
            var ids = typeof idsJson === 'string' ? JSON.parse(idsJson) : idsJson;
            ids.forEach(function (id) { nodeStatus[id] = 'queued'; });
            repaintAllStatusCards();
        } catch (e) { console.error('seedQueuedStatuses error:', e); }
    };

    window.setRunResults = function (payloadOrJson) {
        try {
            var map = typeof payloadOrJson === 'string' ? JSON.parse(payloadOrJson) : payloadOrJson;
            nodeStatus = {};
            nodeFailures = {};
            nodeFailureMessages = {};
            Object.keys(map).forEach(function (id) {
                nodeStatus[id] = map[id].status;
                if (map[id].failures && map[id].failures > 0) {
                    nodeFailures[id] = map[id].failures;
                    if (map[id].message) {
                        nodeFailureMessages[id] = map[id].message;
                    }
                }
            });
            repaintAllStatusCards();
            if (typeof repaintAllFailureBadges === 'function') repaintAllFailureBadges();
            renderRunResultsHint(map);
        } catch (e) { console.error('setRunResults error:', e); }
    };

    function renderRunResultsHint(map) {
        var hintEl = document.getElementById('run-results-hint');
        if (!hintEl) return;
        var count = map ? Object.keys(map).length : 0;
        if (count === 0) {
            hintEl.style.display = 'none';
            return;
        }
        var startedSeconds = 0;
        var startedAtIso = null;
        Object.keys(map).forEach(function (id) {
            if (map[id].startedAt) {
                var t = Date.parse(map[id].startedAt);
                if (t > startedSeconds) { startedSeconds = t; startedAtIso = map[id].startedAt; }
            }
        });
        var label = startedAtIso
            ? new Date(startedAtIso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
            : 'recent run';
        hintEl.textContent = 'Showing results from last run at ' + label + ' (' + count + ' models).';
        hintEl.style.display = 'block';
    }

    window.renderGraph = function (jsonStr) {
        loadExpandedFromStorage();
        loadMinimapPref();
        layoutCache.clear();
        try {
            const graph = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            window.__catalogAvailable = !!graph.catalogAvailable;
            currentColorMode = graph.nodeColorMode || 'resource';

            // Prune expanded ids that are no longer in the graph
            var liveIds = new Set();
            graph.nodes.forEach(function (n) { liveIds.add(n.id); });
            Array.from(expandedIds).forEach(function (id) {
                if (!liveIds.has(id)) expandedIds.delete(id);
            });
            saveExpandedToStorage();

            const elements = [];

            for (const node of graph.nodes) {
                if (node.isParent) {
                    elements.push({
                        data: {
                            id: node.id,
                            name: node.name,
                            isParent: true,
                            resourceType: 'cluster'
                        }
                    });
                    continue;
                }

                var name = node.name;
                var w = CARD_WIDTH;
                var h = (node.resourceType === 'stub') ? STUB_HEIGHT : cardHeightFor(node);
                if (node.resourceType === 'stub') { w = STUB_WIDTH; }

                elements.push({
                    data: {
                        id: node.id,
                        name: name,
                        resourceType: node.resourceType,
                        schema: node.schema,
                        database: node.database,
                        materialization: node.materialization,
                        description: node.description,
                        filePath: node.filePath,
                        depth: node.depth,
                        isCurrent: node.isCurrent,
                        stubDirection: node.stubDirection,
                        boundaryNodeId: node.boundaryNodeId,
                        columns: node.columns || [],
                        iconKey: pickIconKey(node),
                        barColor: pickBarColor(node, graph.nodeColorMode),
                        parent: node.parent || undefined,
                        w: w,
                        h: h
                    }
                });
            }

            nodeSearchHints = {};
            for (const node of graph.nodes) {
                if (node.searchHints) nodeSearchHints[node.id] = node.searchHints;
            }

            for (const edge of graph.edges) {
                var isStub = edge.fromNodeId.indexOf('__stub_') === 0 || edge.toNodeId.indexOf('__stub_') === 0;
                elements.push({
                    data: {
                        id: edge.fromNodeId + '->' + edge.toNodeId,
                        source: edge.fromNodeId,
                        target: edge.toNodeId
                    },
                    classes: isStub ? 'stub-edge' : ''
                });
            }

            initCytoscape(elements, graph.currentNodeId, graph.edgeCurveStyle, graph.layoutDirection);
        } catch (e) {
            console.error('renderGraph error:', e);
        }
    };

    window.highlightNode = function (nodeId) {
        if (!cy) return;
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('selected'); });
        const node = cy.getElementById(nodeId);
        if (node.length) {
            var card = nodeCards[nodeId];
            if (card) card.classList.add('selected');
            cy.animate({ center: { eles: node }, duration: 300 });
        }
    };

    function matchesQuery(nodeId, tokens) {
        if (!tokens.length) return true;
        var h = nodeSearchHints[nodeId];
        if (!h) return false;

        var bareTokens = [];
        var byKind = {};
        tokens.forEach(function (t) {
            if (t.kind === 'bare') bareTokens.push(t.value.toLowerCase());
            else { byKind[t.kind] = byKind[t.kind] || []; byKind[t.kind].push(t.value); }
        });

        // bare tokens AND across; each must match name or id
        for (var i = 0; i < bareTokens.length; i++) {
            var v = bareTokens[i];
            if ((h.lowerName || '').indexOf(v) < 0 && (h.lowerId || '').indexOf(v) < 0) return false;
        }
        // for each prefix kind, ANY token in that kind must match (OR within kind, AND across kinds)
        for (var key in byKind) {
            var vals = byKind[key];
            var anyHit = vals.some(function (v) { return matchKind(h, key, v); });
            if (!anyHit) return false;
        }
        return true;
    }

    function matchKind(h, kind, value) {
        switch (kind) {
            case 'col':    return (h.columnNamesLower || []).some(function (c) { return c.indexOf(value) >= 0; });
            case 'tag':    return (h.tagsLower || []).indexOf(value) >= 0;
            case 'schema': return (h.schemaLower || '').indexOf(value) >= 0;
            case 'mat':    return (h.materialization || '') === value;
            case 'type':   return (h.resourceType || '') === value;
            case 'pkg':    return (h.packageLower || '').indexOf(value) >= 0;
            default:       return false;
        }
    }

    var catalogToastShown = false;
    function maybeShowCatalogMissingToast(tokens) {
        if (catalogToastShown) return;
        if (window.__catalogAvailable) return;
        var usesCol = tokens.some(function (t) { return t.kind === 'col'; });
        if (!usesCol) return;
        catalogToastShown = true;
        var toast = document.createElement('div');
        toast.textContent = 'Column search uses schema.yml columns. For complete results, run `dbt docs generate`.';
        toast.style.cssText =
            'position: absolute; top: 8px; left: 50%; transform: translateX(-50%); ' +
            'background: var(--tooltip-bg); color: var(--tooltip-text); ' +
            'border: 1px solid var(--tooltip-border); padding: 6px 12px; ' +
            'border-radius: 6px; z-index: 2000; font-size: 11px;';
        document.body.appendChild(toast);
        setTimeout(function () { toast.remove(); }, 6000);
    }

    window.filterNodes = function (query) {
        if (!cy) return;
        if (!query || query.trim() === '') {
            resetFilter();
            return;
        }
        var tokens = tokenize(query);

        cy.nodes().forEach(function (node) {
            var match = matchesQuery(node.id(), tokens);
            if (match) {
                node.removeClass('dimmed');
                var c = nodeCards[node.id()]; if (c) c.classList.remove('dimmed');
            } else {
                node.addClass('dimmed');
                var c2 = nodeCards[node.id()]; if (c2) c2.classList.add('dimmed');
            }
        });
        cy.edges().forEach(function (edge) {
            var src = edge.source();
            var tgt = edge.target();
            if (src.hasClass('dimmed') && tgt.hasClass('dimmed')) edge.addClass('dimmed');
            else edge.removeClass('dimmed');
        });

        maybeShowCatalogMissingToast(tokens);
    };

    window.resetFilter = function () {
        if (!cy) return;
        cy.elements().removeClass('dimmed');
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('dimmed'); });
    };

    // === Docs sidebar ===

    var sidebarEl = document.getElementById('docs-sidebar');
    var sidebarToggleBtn = document.getElementById('toggle-sidebar');
    var sidebarCloseBtn = document.getElementById('docs-close');
    var lastDocsPayload = null;

    function setSidebarOpen(open) {
        if (!sidebarEl) return;
        sidebarEl.classList.toggle('open', open);
        if (sidebarToggleBtn) sidebarToggleBtn.classList.toggle('active', open);
    }

    if (sidebarToggleBtn) {
        sidebarToggleBtn.addEventListener('click', function () {
            setSidebarOpen(!sidebarEl.classList.contains('open'));
        });
    }

    var regenerateBtn = document.getElementById('regenerate-docs');
    if (regenerateBtn) {
        regenerateBtn.addEventListener('click', function () {
            if (regenerateBtn.classList.contains('running')) return;
            regenerateBtn.classList.add('running');
            regenerateBtn.classList.remove('needs-attention');
            sendToKotlin('regenerateDocs', {});
        });
    }
    window.setRegenerateNeedsAttention = function (needs) {
        if (regenerateBtn) regenerateBtn.classList.toggle('needs-attention', !!needs);
    };
    window.setRegenerateRunning = function (running) {
        if (regenerateBtn) regenerateBtn.classList.toggle('running', !!running);
    };
    if (sidebarCloseBtn) {
        sidebarCloseBtn.addEventListener('click', function () { setSidebarOpen(false); });
    }

    // Restore persisted width
    try {
        var savedW = localStorage.getItem('dbtHelper.sidebarWidth');
        if (savedW && sidebarEl) sidebarEl.style.width = savedW + 'px';
    } catch (e) {}

    // Apply wheel deltas directly + stop bubbling so the cytoscape canvas
    // beneath the sidebar doesn't waste compositing time on each event.
    (function () {
        var contentEl = document.querySelector('.docs-content');
        if (!contentEl || !sidebarEl) return;

        // Stop wheel propagation at the sidebar boundary
        sidebarEl.addEventListener('wheel', function (e) {
            e.stopPropagation();
        }, { capture: true });

        contentEl.addEventListener('wheel', function (e) {
            if (e.ctrlKey) return;
            var dy = e.deltaY;
            if (e.deltaMode === 1) dy *= 16;
            else if (e.deltaMode === 2) dy *= contentEl.clientHeight;
            contentEl.scrollTop += dy;
            e.preventDefault();
        }, { passive: false });
    })();



    // Resize handle drag
    var resizeHandle = document.getElementById('docs-resize');
    var resizing = null;
    if (resizeHandle) {
        resizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            var rect = sidebarEl.getBoundingClientRect();
            resizing = { startX: e.clientX, startW: rect.width };
            document.body.style.cursor = 'ew-resize';
            document.body.style.userSelect = 'none';
        });
    }
    window.addEventListener('mousemove', function (e) {
        if (!resizing) return;
        var dx = resizing.startX - e.clientX;
        var newW = resizing.startW + dx;
        var minW = 280;
        var maxW = window.innerWidth * 0.8;
        newW = Math.max(minW, Math.min(maxW, newW));
        sidebarEl.style.width = newW + 'px';
        if (cy) cy.resize();
    });
    window.addEventListener('mouseup', function () {
        if (!resizing) return;
        try {
            var w = sidebarEl.getBoundingClientRect().width;
            localStorage.setItem('dbtHelper.sidebarWidth', String(Math.round(w)));
        } catch (e) {}
        resizing = null;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
    });

    Array.prototype.forEach.call(document.querySelectorAll('.docs-tab'), function (tab) {
        tab.addEventListener('click', function () {
            var which = tab.dataset.tab;
            Array.prototype.forEach.call(document.querySelectorAll('.docs-tab'), function (t) {
                t.classList.toggle('active', t === tab);
            });
            Array.prototype.forEach.call(document.querySelectorAll('.docs-section'), function (s) {
                s.classList.toggle('active', s.id === 'docs-section-' + which);
            });
        });
    });

    function renderDocsHeader(p) {
        var iconEl = document.getElementById('docs-icon');
        var iconKey = pickIconKey({
            resourceType: p.kind,
            materialization: p.materialization
        });
        iconEl.innerHTML = ICONS[iconKey] || ICONS.view;
        document.getElementById('docs-name').textContent = p.name || '';
        document.getElementById('docs-schema').textContent = p.schema || '';
    }

    function renderDocsPills(p) {
        var pillsEl = document.getElementById('docs-pills');
        pillsEl.innerHTML = '';
        function add(text, cls) {
            var el = document.createElement('span');
            el.className = 'docs-pill' + (cls ? ' ' + cls : '');
            el.textContent = text;
            pillsEl.appendChild(el);
        }
        if (p.materialization) add(p.materialization);
        // Skip the incremental flag pill when materialization already says "incremental"
        if (p.kind === 'model' && p.materialization !== 'incremental') {
            add('incremental: ' + (p.incremental ? 'true' : 'false'));
        }
        var total = (p.tests && p.tests.total) || 0;
        if (total > 0) add(total + '/' + total + ' tests', 'tests-ok');
        var tags = (p.metadata && p.metadata.tags) || [];
        for (var i = 0; i < tags.length; i++) add('#' + tags[i], 'tag');
    }

    function renderDocsDescription(p) {
        var wrap = document.getElementById('docs-description');
        var textEl = document.getElementById('docs-desc-text');
        var desc = (p.metadata && p.metadata.description) || '';
        wrap.classList.toggle('empty', !desc);
        textEl.textContent = desc;
        // Default: expanded
        wrap.classList.add('expanded');
        textEl.classList.remove('collapsed');
        if (!desc) return;
        // Determine whether the toggle button is needed (multi-line / overflows when collapsed)
        requestAnimationFrame(function () {
            var hasMultiline = desc.indexOf('\n') >= 0;
            // Probe collapsed width by temporarily measuring
            textEl.classList.add('collapsed');
            var hasOverflow = textEl.scrollWidth > textEl.clientWidth + 1;
            textEl.classList.remove('collapsed');
            wrap.classList.toggle('has-overflow', hasMultiline || hasOverflow);
        });
    }

    var descToggleBtn = document.getElementById('docs-desc-toggle');
    if (descToggleBtn) {
        descToggleBtn.addEventListener('click', function () {
            var wrap = document.getElementById('docs-description');
            var textEl = document.getElementById('docs-desc-text');
            var expanded = wrap.classList.toggle('expanded');
            textEl.classList.toggle('collapsed', !expanded);
            descToggleBtn.title = expanded ? 'Collapse description' : 'Expand description';
        });
    }

    function renderDocsColumns(p) {
        var el = document.getElementById('docs-section-columns');
        var cols = (p.columns || []);
        if (!cols.length) { el.innerHTML = '<div class="empty">No columns documented.</div>'; return; }
        var html = '<div class="col-header"><span>Column</span><span>Type</span></div>';
        for (var i = 0; i < cols.length; i++) {
            var c = cols[i];
            html += '<div class="col-row">';
            html += '<div class="col-main">';
            html += '<div class="col-name">';
            if (c.isPrimaryKey) html += '<span class="col-pk-badge">PK</span>';
            html += escapeHtml(c.name);
            html += '</div>';
            if (c.fk) html += '<div class="col-fk">FK → ' + escapeHtml(c.fk) + '</div>';
            if (c.description) html += '<div class="col-desc">' + escapeHtml(c.description) + '</div>';
            html += '</div>';
            html += '<div class="col-type">' + escapeHtml(c.type || '') + '</div>';
            html += '</div>';
        }
        el.innerHTML = html;
    }

    function renderDocsTests(p) {
        var el = document.getElementById('docs-section-tests');
        var list = (p.tests && p.tests.list) || [];
        if (!list.length) { el.innerHTML = '<div class="empty">No tests defined.</div>'; return; }
        var html = '<ul class="docs-list">';
        for (var i = 0; i < list.length; i++) {
            var t = list[i];
            html += '<li>' + escapeHtml(t.shortName);
            if (t.column) html += ' <span style="color: var(--card-schema)">on ' + escapeHtml(t.column) + '</span>';
            var msg = t.uniqueId ? nodeFailureMessages[t.uniqueId] : null;
            if (msg) {
                html += '<div style="color: #F85149; margin-top: 2px; font-size: 11px;">' + escapeHtml(msg) + '</div>';
            }
            html += '</li>';
        }
        html += '</ul>';
        el.innerHTML = html;
    }

    function renderDocsSql(p) {
        var el = document.getElementById('docs-section-sql');
        var sql = (p.sql && (p.sql.raw || p.sql.compiled)) || '';
        if (!sql) { el.innerHTML = '<div class="empty">No SQL available.</div>'; return; }
        el.innerHTML = '<pre class="code-block">' + escapeHtml(sql) + '</pre>';
    }

    function renderDocsMetadata(p) {
        var el = document.getElementById('docs-section-metadata');
        var m = p.metadata || {};
        var rows = [];
        if (m.fullName) rows.push(['Relation', m.fullName]);
        if (m.filePath) rows.push(['File', m.filePath]);
        if (m.patchPath) rows.push(['Docs', m.patchPath]);
        if (m.packageName) rows.push(['Package', m.packageName]);
        if (m.fqn && m.fqn.length) rows.push(['FQN', m.fqn.join(' → ')]);
        if (m.tags && m.tags.length) rows.push(['Tags', m.tags.join(', ')]);
        if (m.description) rows.push(['Description', m.description]);
        if (m.dependsOn && m.dependsOn.length) rows.push(['Depends on', m.dependsOn.join(', ')]);
        if (m.referencedBy && m.referencedBy.length) rows.push(['Referenced by', m.referencedBy.join(', ')]);
        if (m.config) {
            Object.keys(m.config).forEach(function (k) { rows.push([k, m.config[k]]); });
        }
        if (m.loader) rows.push(['Loader', m.loader]);
        if (m.loadedAtField) rows.push(['Loaded at', m.loadedAtField]);
        if (m.freshnessWarnAfter) rows.push(['Warn after', m.freshnessWarnAfter]);
        if (m.freshnessErrorAfter) rows.push(['Error after', m.freshnessErrorAfter]);
        if (!rows.length) { el.innerHTML = '<div class="empty">No metadata.</div>'; return; }
        var html = '<table class="meta-table">';
        for (var i = 0; i < rows.length; i++) {
            html += '<tr><td class="k">' + escapeHtml(rows[i][0]) + '</td><td class="v">' + escapeHtml(String(rows[i][1])) + '</td></tr>';
        }
        html += '</table>';
        el.innerHTML = html;
    }

    window.showDocs = function (jsonStr) {
        try {
            var p = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            lastDocsPayload = p;
            if (sidebarEl) sidebarEl.classList.remove('freshness-mode');
            renderDocsHeader(p);
            renderDocsDescription(p);
            renderDocsPills(p);
            renderDocsColumns(p);
            renderDocsTests(p);
            renderDocsSql(p);
            renderDocsMetadata(p);
        } catch (e) {
            console.error('showDocs error:', e);
        }
    };

    window.showFreshnessDetail = function (jsonStr) {
        try {
            var p = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            var nameEl = document.getElementById('docs-name');
            var schemaEl = document.getElementById('docs-schema');
            if (nameEl) nameEl.textContent = p.name || '—';
            if (schemaEl) schemaEl.textContent = p.relation || '';

            var panel = document.getElementById('docs-freshness-panel');
            if (!panel) return;

            var STATUS_META = {
                pass:      { label: 'Fresh',                                  cls: 'fresh-pass',      dot: '●' },
                warn:      { label: 'Stale (warn)',                           cls: 'fresh-warn',      dot: '●' },
                error:     { label: 'Stale (error)',                          cls: 'fresh-error',     dot: '⬤' },
                no_result: { label: 'No freshness result for this source',    cls: 'fresh-no_result', dot: '○' },
                no_data:   { label: 'sources.json not found',                 cls: 'fresh-no_data',   dot: '○' }
            };
            var meta = STATUS_META[p.status] || STATUS_META.no_result;

            var html = '';
            html += '<div class="freshness-banner ' + meta.cls + '">';
            html += '<span class="dot">' + meta.dot + '</span><span>' + escapeHtml(meta.label) + '</span>';
            html += '</div>';

            if (p.message) {
                html += '<div class="freshness-message">' + escapeHtml(p.message) + '</div>';
            }

            if (p.status === 'no_data') {
                html += '<div class="freshness-hint">Run <code>dbt source freshness</code> to populate <code>target/sources.json</code>.</div>';
            } else if (p.status === 'no_result') {
                html += '<div class="freshness-hint">No freshness criteria configured for this source, or it was excluded from the last freshness run.</div>';
            }

            var rows = [];
            if (p.loadedAtField) rows.push(['Loaded at', p.loadedAtField]);
            if (p.warnAfter) rows.push(['Warn after', p.warnAfter]);
            if (p.errorAfter) rows.push(['Error after', p.errorAfter]);
            if (p.filePath) rows.push(['Defined in', p.filePath]);
            if (rows.length) {
                html += '<table class="meta-table" style="margin-top: 8px;">';
                for (var i = 0; i < rows.length; i++) {
                    html += '<tr><td class="k">' + escapeHtml(rows[i][0]) + '</td><td class="v">' + escapeHtml(String(rows[i][1])) + '</td></tr>';
                }
                html += '</table>';
            }

            html += '<div class="freshness-back-row"><button class="freshness-back-btn" id="freshness-back-btn">View source docs</button></div>';
            panel.innerHTML = html;

            var backBtn = document.getElementById('freshness-back-btn');
            if (backBtn) {
                backBtn.addEventListener('click', function () {
                    sendToKotlin('previewNode', { nodeId: p.id });
                });
            }

            if (sidebarEl) {
                sidebarEl.classList.add('freshness-mode');
                setSidebarOpen(true);
            }
        } catch (e) {
            console.error('showFreshnessDetail error:', e);
        }
    };

    window.showMultiSelectPlaceholder = function (count) {
        // Update sidebar header
        var iconEl = document.getElementById('docs-icon');
        var nameEl = document.getElementById('docs-name');
        var schemaEl = document.getElementById('docs-schema');
        var descEl = document.getElementById('docs-description');
        var pillsEl = document.getElementById('docs-pills');
        if (iconEl) iconEl.textContent = '';
        if (nameEl) nameEl.textContent = count + ' nodes selected';
        if (schemaEl) schemaEl.textContent = '';
        if (descEl) { descEl.classList.add('empty'); document.getElementById('docs-desc-text').textContent = ''; }
        if (pillsEl) pillsEl.textContent = '';
        // Clear all section content and show a message in the columns section
        var sections = document.querySelectorAll('.docs-section');
        sections.forEach(function (s) { s.textContent = ''; s.classList.remove('active'); });
        var colSection = document.getElementById('docs-section-columns');
        if (colSection) {
            colSection.classList.add('active');
            var hint = document.createElement('p');
            hint.className = 'empty';
            hint.textContent = 'Click one node to see its details.';
            colSection.appendChild(hint);
        }
        // Ensure sidebar is open
        var sidebarEl = document.getElementById('docs-sidebar');
        if (sidebarEl) sidebarEl.classList.add('open');
        var toggleBtn = document.getElementById('toggle-sidebar');
        if (toggleBtn) toggleBtn.classList.add('active');
    };

    window.applyTheme = function (isDark) {
        var root = document.documentElement;
        document.body.classList.toggle('theme-light', !isDark);
        if (isDark) {
            root.style.setProperty('--bg-color', '#1e1e1e');
            root.style.setProperty('--text-color', '#ccc');
            root.style.setProperty('--tooltip-bg', '#2d2d2d');
            root.style.setProperty('--tooltip-border', '#555');
            root.style.setProperty('--tooltip-text', '#ddd');
            root.style.setProperty('--tooltip-name', '#fff');
            root.style.setProperty('--tooltip-detail', '#aaa');
            root.style.setProperty('--btn-bg', '#2d2d2d');
            root.style.setProperty('--btn-border', '#555');
            root.style.setProperty('--btn-text', '#ccc');
            root.style.setProperty('--btn-hover', '#3d3d3d');
            root.style.setProperty('--edge-color', '#555');
            root.style.setProperty('--loading-color', '#888');
        } else {
            root.style.setProperty('--bg-color', '#f5f5f5');
            root.style.setProperty('--text-color', '#333');
            root.style.setProperty('--tooltip-bg', '#fff');
            root.style.setProperty('--tooltip-border', '#ccc');
            root.style.setProperty('--tooltip-text', '#333');
            root.style.setProperty('--tooltip-name', '#111');
            root.style.setProperty('--tooltip-detail', '#666');
            root.style.setProperty('--btn-bg', '#fff');
            root.style.setProperty('--btn-border', '#ccc');
            root.style.setProperty('--btn-text', '#333');
            root.style.setProperty('--btn-hover', '#e8e8e8');
            root.style.setProperty('--edge-color', '#999');
            root.style.setProperty('--loading-color', '#666');
        }
        // Update edge colors in cytoscape if graph exists
        if (cy) {
            var edgeColor = isDark ? '#555' : '#999';
            cy.edges().style({ 'line-color': edgeColor, 'target-arrow-color': edgeColor });
        }
    };

    // === Bridge to Kotlin ===

    function sendToKotlin(type, payload) {
        const request = JSON.stringify({ type: type, payload: payload });
        if (window.__cefQueryBridge) {
            window.__cefQueryBridge(request);
        }
    }

    // Cluster mode dropdown
    var clusterModeSelect = document.getElementById('cluster-mode');
    if (clusterModeSelect) {
        clusterModeSelect.addEventListener('change', function () {
            sendToKotlin('clusterModeChanged', { mode: clusterModeSelect.value });
        });
    }
    window.setClusterMode = function (mode) {
        if (clusterModeSelect) clusterModeSelect.value = mode;
    };

    // Search input
    var searchInput = document.getElementById('search-input');
    var searchClear = document.getElementById('search-clear');
    var searchDebounce = null;

    function updateClearButton() {
        searchClear.style.display = searchInput.value.length > 0 ? 'block' : 'none';
    }

    searchInput.addEventListener('input', function (e) {
        updateClearButton();
        clearTimeout(searchDebounce);
        searchDebounce = setTimeout(function () {
            window.filterNodes(e.target.value);
        }, 150);
    });

    searchClear.addEventListener('click', function () {
        searchInput.value = '';
        updateClearButton();
        window.resetFilter();
        searchInput.focus();
    });

    (function () {
        var btn = document.getElementById('search-help');
        if (!btn) return;
        var popup = null;
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            if (popup) { popup.remove(); popup = null; return; }
            popup = document.createElement('div');
            popup.className = 'help-popup';
            // Note: the HTML here is built from constant strings (no user input).
            popup.innerHTML = '' +
                '<div style="margin-bottom: 4px;"><b>Search syntax</b></div>' +
                '<table>' +
                '<tr><td class="k">name</td><td>Match name/id (default)</td></tr>' +
                '<tr><td class="k">col:name</td><td>Models with column matching name</td></tr>' +
                '<tr><td class="k">tag:value</td><td>Models with tag</td></tr>' +
                '<tr><td class="k">schema:name</td><td>Models in schema</td></tr>' +
                '<tr><td class="k">mat:type</td><td>Materialization (table, view, incremental, …)</td></tr>' +
                '<tr><td class="k">type:kind</td><td>Resource type (model, source, seed, …)</td></tr>' +
                '<tr><td class="k">pkg:name</td><td>Package name</td></tr>' +
                '</table>';
            document.body.appendChild(popup);
            var r = btn.getBoundingClientRect();
            popup.style.left = (r.left) + 'px';
            popup.style.top = (r.bottom + 4) + 'px';
        });
        document.addEventListener('click', function () {
            if (popup) { popup.remove(); popup = null; }
        });
    })();

    // Signal ready
    window.addEventListener('DOMContentLoaded', function () {
        setTimeout(function () {
            sendToKotlin('ready', {});
        }, 100);
    });

    // === Mini-map ===

    var minimapVisible = false;
    var minimapBtn = document.getElementById('minimap-toggle');
    var minimapWrap = document.getElementById('minimap-wrap');
    var minimapCanvas = document.getElementById('minimap');
    var minimapCtx = minimapCanvas ? minimapCanvas.getContext('2d') : null;
    var minimapTransform = null;

    function loadMinimapPref() {
        try {
            var saved = localStorage.getItem(projectStorageKey('minimap'));
            if (saved === '1') setMinimapVisible(true);
        } catch (e) {}
    }
    function setMinimapVisible(v) {
        minimapVisible = !!v;
        if (minimapWrap) minimapWrap.style.display = v ? 'block' : 'none';
        if (minimapBtn) minimapBtn.classList.toggle('active', v);
        try { localStorage.setItem(projectStorageKey('minimap'), v ? '1' : '0'); } catch (e) {}
        if (v) drawMinimap();
    }
    if (minimapBtn) minimapBtn.addEventListener('click', function () { setMinimapVisible(!minimapVisible); });

    function drawMinimap() {
        if (!minimapVisible || !cy || !minimapCtx) return;
        var ctx = minimapCtx;
        var w = minimapCanvas.width;
        var h = minimapCanvas.height;
        ctx.clearRect(0, 0, w, h);

        var nodes = cy.nodes();
        if (nodes.length === 0) return;
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        nodes.forEach(function (n) {
            var p = n.position();
            var nw = n.width(), nh = n.height();
            minX = Math.min(minX, p.x - nw/2);
            minY = Math.min(minY, p.y - nh/2);
            maxX = Math.max(maxX, p.x + nw/2);
            maxY = Math.max(maxY, p.y + nh/2);
        });
        var pad = 20;
        var bw = (maxX - minX) || 1;
        var bh = (maxY - minY) || 1;
        var scale = Math.min((w - pad*2) / bw, (h - pad*2) / bh);
        function tx(x) { return pad + (x - minX) * scale; }
        function ty(y) { return pad + (y - minY) * scale; }

        ctx.fillStyle = '#666';
        nodes.forEach(function (n) {
            var p = n.position();
            var nw = n.width() * scale;
            var nh = n.height() * scale;
            ctx.fillRect(tx(p.x) - nw/2, ty(p.y) - nh/2, Math.max(2, nw), Math.max(2, nh));
        });

        var pan = cy.pan();
        var zoom = cy.zoom();
        var vp = cy.container().getBoundingClientRect();
        var vpModelW = vp.width / zoom;
        var vpModelH = vp.height / zoom;
        var vpModelX = -pan.x / zoom;
        var vpModelY = -pan.y / zoom;
        ctx.strokeStyle = '#4E79A7';
        ctx.lineWidth = 1;
        ctx.strokeRect(tx(vpModelX), ty(vpModelY), vpModelW * scale, vpModelH * scale);

        minimapTransform = { tx: tx, ty: ty, scale: scale, minX: minX, minY: minY, pad: pad };
    }

    if (minimapCanvas) {
        var miniDragging = false;
        function modelFromMinimap(e) {
            var rect = minimapCanvas.getBoundingClientRect();
            var x = e.clientX - rect.left;
            var y = e.clientY - rect.top;
            if (!minimapTransform) return null;
            var t = minimapTransform;
            var modelX = t.minX + (x - t.pad) / t.scale;
            var modelY = t.minY + (y - t.pad) / t.scale;
            return { x: modelX, y: modelY };
        }
        minimapCanvas.addEventListener('mousedown', function (e) {
            e.preventDefault();
            miniDragging = true;
            if (minimapWrap) minimapWrap.classList.add('cy-minimap-pan');
            var p = modelFromMinimap(e);
            if (p && cy) centerCyOn(p);
        });
        window.addEventListener('mousemove', function (e) {
            if (!miniDragging) return;
            var p = modelFromMinimap(e);
            if (p && cy) centerCyOn(p);
        });
        window.addEventListener('mouseup', function () {
            if (!miniDragging) return;
            miniDragging = false;
            if (minimapWrap) minimapWrap.classList.remove('cy-minimap-pan');
        });
    }
    function centerCyOn(model) {
        if (!cy) return;
        var zoom = cy.zoom();
        var w = cy.container().clientWidth;
        var h = cy.container().clientHeight;
        cy.pan({ x: -model.x * zoom + w / 2, y: -model.y * zoom + h / 2 });
    }
})();
