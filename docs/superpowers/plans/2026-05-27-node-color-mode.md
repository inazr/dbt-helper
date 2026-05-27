# Node Color Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Node color" setting that switches lineage node bar colors between the current resource-type scheme and a schema-name scheme.

**Architecture:** Client-side coloring stays in `lineage.js`. A new `nodeColorMode` setting flows to the page on the graph payload exactly like `edgeCurveStyle`/`layoutDirection`. In schema mode, `lineage.js` maps each node's `schema` to a color from a fixed palette via a deterministic hash; nodes without a schema get neutral gray. Icons are unchanged.

**Tech Stack:** Kotlin 2.0 / JVM 21, IntelliJ Platform Gradle Plugin 2.x, IntelliJ UI DSL (`comboBox`/`bindItem`), vendored Cytoscape.js in `resources/js/lineage.js`.

**Spec:** `docs/superpowers/specs/2026-05-27-node-color-mode-design.md`

No test source set exists (per CLAUDE.md); verification is `./gradlew buildPlugin` per task plus a manual `runIde` check at the end. Kotlin tasks are validated by compilation; the JS change is validated by the manual check (compilation can't see it).

---

## File structure

| File | Responsibility | Change |
|------|----------------|--------|
| `src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt` | Persist `nodeColorMode` | Modify |
| `src/main/kotlin/com/dbthelper/core/model/LineageGraph.kt` | Carry `nodeColorMode` in the graph payload | Modify |
| `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt` | Copy the setting into the graph before sending to JS | Modify |
| `src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt` | "Node color:" combo in settings UI | Modify |
| `src/main/resources/js/lineage.js` | Schema palette + hash + mode-aware `pickBarColor` | Modify |

---

## Task 1: Persist the setting

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt`

- [ ] **Step 1: Add the field to `State`**

In the `State` data class, add a `nodeColorMode` field after `enableColoredOutput` (and before `configVersion`). The block currently reads:

```kotlin
        var enableSystemNotifications: Boolean = true,
        var enableColoredOutput: Boolean = false,
        // Bumped when a settings default changes so loadState can migrate old data.
        // Absent in pre-migration saved files, so it deserializes to 0 there.
        var configVersion: Int = 0
    )
```

Change it to:

```kotlin
        var enableSystemNotifications: Boolean = true,
        var enableColoredOutput: Boolean = false,
        // How lineage node bar colors are derived: "resource" | "schema".
        var nodeColorMode: String = "resource",
        // Bumped when a settings default changes so loadState can migrate old data.
        // Absent in pre-migration saved files, so it deserializes to 0 there.
        var configVersion: Int = 0
    )
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt
git commit -m "Add nodeColorMode setting (resource|schema)"
```

---

## Task 2: Carry the mode in the graph payload

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/core/model/LineageGraph.kt`
- Modify: `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt`

- [ ] **Step 1: Add the field to `LineageGraph`**

In `src/main/kotlin/com/dbthelper/core/model/LineageGraph.kt`, the `LineageGraph` data class currently ends with:

```kotlin
    val edgeCurveStyle: String = "bezier",
    val layoutDirection: String = "LR"
)
```

Change it to:

```kotlin
    val edgeCurveStyle: String = "bezier",
    val layoutDirection: String = "LR",
    val nodeColorMode: String = "resource"
)
```

- [ ] **Step 2: Pass the setting into the graph in `LineageTab`**

In `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt`, find the `.copy(...)` at the end of the `builder.build(...)` chain in `refreshGraph` (around line 281). It currently reads:

```kotlin
                ).copy(edgeCurveStyle = settings.state.edgeCurveStyle, layoutDirection = settings.state.layoutDirection)
```

Change it to:

```kotlin
                ).copy(
                    edgeCurveStyle = settings.state.edgeCurveStyle,
                    layoutDirection = settings.state.layoutDirection,
                    nodeColorMode = settings.state.nodeColorMode
                )
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/dbthelper/core/model/LineageGraph.kt src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt
git commit -m "Carry nodeColorMode in the lineage graph payload"
```

---

## Task 3: Settings UI combo

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt`

- [ ] **Step 1: Add the "Node color:" row**

In `src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt`, inside the `group("Lineage Defaults")` block, insert a new `row("Node color:")` immediately after the "Layout direction:" row. To avoid the unicode-escaped arrow characters elsewhere in that row, anchor on its arrow-free tail (the setter's `else -> "LR"` and the braces that close the row). The current tail reads:

```kotlin
                            else -> "LR"
                        }}
                    )
            }
```

Change that exact block to:

```kotlin
                            else -> "LR"
                        }}
                    )
            }
            row("Node color:") {
                val nodeColorModes = listOf("Resource type", "Schema name")
                comboBox(nodeColorModes)
                    .bindItem(
                        { if (settings.state.nodeColorMode == "schema") "Schema name" else "Resource type" },
                        { settings.state.nodeColorMode = if (it == "Schema name") "schema" else "resource" }
                    )
                    .comment("How lineage node colors are derived")
            }
```

This places the new row after the closing `}` of the "Layout direction:" row and before the group's closing `}`. (The `else -> "LR"` tail belongs to the layout-direction *setter* and is unique in the file — the getter ends with `else -> "Left → Right"`.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL. (`comboBox` + `bindItem(getter, setter)` mirror the existing "Layout direction:" row in the same file, so the imports/DSL are already present.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt
git commit -m "Add 'Node color' setting combo (Resource type | Schema name)"
```

---

## Task 4: Schema coloring in lineage.js

**Files:**
- Modify: `src/main/resources/js/lineage.js`

- [ ] **Step 1: Add the palette and neutral color**

In `src/main/resources/js/lineage.js`, immediately after the `NODE_BAR_COLORS` object (which ends with `stub: '#9E9E9E'` then `};`), add:

```js
    // Categorical palette for schema-based coloring (stable, well-separated hues).
    const SCHEMA_PALETTE = [
        '#4E79A7', '#F28E2B', '#59A14F', '#E15759', '#B07AA1', '#76B7B2',
        '#EDC948', '#FF9DA7', '#9C755F', '#499894', '#D37295', '#8CD17D'
    ];
    // Fallback for nodes without a schema (e.g. exposures) in schema mode.
    const NEUTRAL_BAR_COLOR = '#9E9E9E';

    function schemaColor(schema) {
        var h = 0;
        for (var i = 0; i < schema.length; i++) {
            h = (h * 31 + schema.charCodeAt(i)) | 0;
        }
        return SCHEMA_PALETTE[Math.abs(h) % SCHEMA_PALETTE.length];
    }
```

- [ ] **Step 2: Make `pickBarColor` mode-aware**

Replace the existing `pickBarColor` function:

```js
    function pickBarColor(node) {
        if (node.resourceType !== 'model') return NODE_BAR_COLORS[node.resourceType] || '#888';
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return NODE_BAR_COLORS.model_table;
        return NODE_BAR_COLORS.model_view;
    }
```

with:

```js
    function pickBarColor(node, colorMode) {
        if (colorMode === 'schema') {
            return node.schema ? schemaColor(node.schema) : NEUTRAL_BAR_COLOR;
        }
        if (node.resourceType !== 'model') return NODE_BAR_COLORS[node.resourceType] || '#888';
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return NODE_BAR_COLORS.model_table;
        return NODE_BAR_COLORS.model_view;
    }
```

- [ ] **Step 3: Pass the mode at the call site**

In `renderGraph`, the node element data sets `barColor: pickBarColor(node),` (around line 504). The enclosing `graph` object (parsed at the top of `renderGraph`) carries `nodeColorMode`. Change:

```js
                        barColor: pickBarColor(node),
```

to:

```js
                        barColor: pickBarColor(node, graph.nodeColorMode),
```

- [ ] **Step 4: Verify the resource bundles**

Run: `./gradlew buildPlugin -q`
Expected: BUILD SUCCESSFUL (the JS is bundled as a resource; compilation can't validate JS, so the behavioral check is the manual `runIde` step in Task 5).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/js/lineage.js
git commit -m "lineage.js: color nodes by schema when nodeColorMode is 'schema'"
```

---

## Task 5: Build + manual verification

**Files:** none (verification only)

- [ ] **Step 1: Full plugin build**

Run: `./gradlew buildPlugin -q`
Expected: BUILD SUCCESSFUL; `build/distributions/dbt-helper-<version>.zip` produced.

- [ ] **Step 2: Launch sandbox IDE**

Run: `./gradlew runIde`

- [ ] **Step 3: Manual checklist** (open a dbt project with a generated `target/manifest.json`, open the dbt Helper tool window, focus a model so the lineage renders)

1. Default: nodes are colored by resource type as before (models blue/purple, sources green, etc.).
2. Settings → Tools → dbt Helper → **Node color** → choose **"Schema name"** → Apply: node bars recolor so nodes sharing a schema share a color; exposures (no schema) show neutral gray; the icons are unchanged.
3. Switch back to **"Resource type"** → Apply: original colors return.
4. Re-focus a different model / change layout: a given schema keeps the same color.

---

## Notes for the implementer

- **No test source set** (per `CLAUDE.md`): do not add `src/test/kotlin` or invent test commands. Compile + manual checklist are the gates.
- The `nodeColorMode` value uses the strings `"resource"` and `"schema"`; the combo maps these to the display labels "Resource type" / "Schema name". Keep the strings exact — the JS checks `colorMode === 'schema'`.
- Do not touch icon logic (`pickIconKey`, `ICONS`) — only the bar color changes by mode.
- `SettingsChangeListener` already calls `refreshGraph` on Apply (existing subscription in `LineageTab`), so no new wiring is needed for live recoloring.
