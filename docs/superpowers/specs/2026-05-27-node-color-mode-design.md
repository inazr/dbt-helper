# Node Color Mode — Design

**Date:** 2026-05-27
**Status:** Approved (design)

## Problem

Lineage nodes are colored by a fixed scheme tied to **resource type** (and, for
models, materialization): models are blue/purple, sources green, seeds orange,
etc. The color (a vertical bar on the left of each card) is computed in
`resources/js/lineage.js` (`NODE_BAR_COLORS` + `pickBarColor`).

Users who think in terms of **warehouse layout** want to instead color nodes by
their **schema name**, so that all nodes living in the same schema share a color.
We want a setting to switch between the two modes.

## Agreed behavior

- A new setting **Node color** with two modes:
  - `resource` (default, current behavior) — color by resource type / materialization.
  - `schema` — color by the node's schema name.
- In `schema` mode:
  - **All nodes that have a schema** (models, sources, seeds, snapshots) are
    colored by their schema name.
  - Nodes **without** a schema (e.g. exposures) fall back to a neutral gray.
  - Colors are **auto-assigned from a fixed palette** via a deterministic hash of
    the schema name, so a given schema always maps to the same color across
    renders and any number of schemas is supported.
- **Icons are unchanged** — they remain resource-type / materialization based in
  both modes. Only the left bar color changes. So in `schema` mode the icon still
  conveys the type while the bar conveys the schema.
- **No legend** (consistent with the current UI, which has no color legend; the
  schema name is already shown on each card).
- Switching the setting and clicking Apply recolors the graph live.

## Architecture

Coloring stays **client-side**, where all existing color logic lives. The mode is
carried to the page on the graph payload, exactly like the existing
`edgeCurveStyle` and `layoutDirection` settings. The schema→color mapping is
computed in `lineage.js`. No node color is computed in Kotlin.

Data flow (all pieces already exist except the new field):

```
DbtHelperSettings.state.nodeColorMode
   └─ DbtHelperConfigurable combo (Apply → SettingsChangeListener.TOPIC)
        └─ LineageTab (subscribed) → refreshGraph()
             └─ LineageGraph.copy(nodeColorMode = ...)  → JSON → JS
                  └─ lineage.js renderGraph → pickBarColor(node, colorMode)
```

## Components

### `DbtHelperSettings` (modify)
Add to `State`:
```kotlin
var nodeColorMode: String = "resource"   // "resource" | "schema"
```
No migration needed — absent in old saved files, deserializes to the default
`"resource"` (current behavior preserved).

### `DbtHelperConfigurable` (modify)
Add a row near the other lineage appearance settings (Edge style / Layout
direction), a combo labeled **"Node color:"** with display values
*"Resource type"* and *"Schema name"*, mapped to the stored `"resource"` /
`"schema"` strings — mirroring the existing `layoutDirection` `bindItem(getter,
setter)` mapping pattern.

### `LineageGraph` (modify)
Add a field, defaulted so existing construction is unaffected:
```kotlin
val nodeColorMode: String = "resource"
```

### `LineageTab` (modify)
Extend the existing `.copy(...)` in `refreshGraph` (currently sets
`edgeCurveStyle`, `layoutDirection`) to also set
`nodeColorMode = settings.state.nodeColorMode`. No other change — the
`SettingsChangeListener` subscription already calls `refreshGraph` on Apply.

### `lineage.js` (modify)
- Add a categorical palette and neutral fallback:
  ```js
  const SCHEMA_PALETTE = [ /* ~12 well-separated hex colors */ ];
  const NEUTRAL_BAR_COLOR = '#9E9E9E';
  ```
- Add a deterministic mapping:
  ```js
  function schemaColor(schema) {
      // stable string hash → palette index
  }
  ```
- Change `pickBarColor(node)` → `pickBarColor(node, colorMode)`:
  - `colorMode === 'schema'` → `node.schema ? schemaColor(node.schema) : NEUTRAL_BAR_COLOR`
  - otherwise → existing resource/materialization logic, unchanged.
- In `renderGraph`, pass the graph's mode into the call:
  `barColor: pickBarColor(node, graph.nodeColorMode)`.
- `pickIconKey` and all icon logic are untouched.

### Palette
A fixed array of ~12 categorical, visually-separated hex colors (distinct hues,
readable on both light and dark canvas). Exact values chosen at implementation
time; they need only be stable and well-separated. The neutral gray `#9E9E9E`
(already used for `stub`) is reused for schema-less nodes.

### Hash
A small deterministic string hash (e.g. a 32-bit rolling hash) mod
`SCHEMA_PALETTE.length`. Collisions (two schemas → same color) are acceptable —
there is no legend and color is a grouping aid, not an identifier.

## Error handling

- Unknown or missing `nodeColorMode` → JS treats anything other than `"schema"`
  as `"resource"` (the `=== 'schema'` check naturally defaults safely).
- `node.schema` null/empty in schema mode → `NEUTRAL_BAR_COLOR`.
- Old saved settings without `nodeColorMode` → default `"resource"`.

## Out of scope

- A color legend.
- User-configurable schema→color assignments (colors are auto-hashed).
- Coloring anything other than the card's left bar (icons, edges unchanged).
- Grouping/compound nodes.

## Verification

No test source set exists; none added. Manual verification via `./gradlew runIde`:

1. Default install: nodes colored by resource type as before.
2. Settings → dbt Helper → Node color → "Schema name" → Apply: bars recolor so
   nodes in the same schema share a color; exposures (no schema) go gray; icons
   unchanged.
3. Switch back to "Resource type" → Apply: original colors return.
4. The same schema keeps the same color across re-layouts / model changes.
