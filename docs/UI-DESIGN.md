# jadxmp — UI Design & Compose Foundation

This document captures the **visual design language** of the jadxmp GUI, an **inventory** of its
screens/panels/components, how they **map to the jadx-gui feature set**, and the **engine follow-ups**
the design implies. It is paired with the `ui:app` module (design system, reusable components, app
shell) described in §7.

> **Provenance.** The source of truth is the user's design mockup,
> `reference/design/Jadx-UI.dc.html` — a self-contained HTML comp of a **dark, gradient-accented
> "decompiler workbench"** (Geist / Geist Mono, a blue→violet accent pair). The tokens, layout,
> spacing, typography, and syntax colors in §1 are taken **directly from that mockup** (its CSS
> custom properties and inline styles). The Compose theme (`ui/app/src/commonMain/kotlin/com/jadxmp/ui/theme/*`)
> mirrors them 1:1; components read tokens, so the palette lives in one place.

---

## 1. Design language

### 1.1 Concept

A reverse-engineer's **instrument**: bytecode, class trees, syntax-highlighted source, smali and hex,
read for long sessions. The mockup's direction:

- **Dark-first, gradient-accented.** Deep cool-neutral surfaces (`#15171C` base) layered by tonal
  step and 1px hairlines, with a single **blue→violet accent pair** (`--acc #7C8CF8` → `--acc2
  #A78BFA`) used for selection, primary actions, the active tab, focus, and the brand gradient (logo,
  progress bars, app-icon chips). Light is a fully-worked variant (from the mockup's own Preferences
  screen).
- **Geist + Geist Mono.** Geist for UI chrome; **Geist Mono** for code *and* the tool's own numeric
  readouts (line/col, counts, offsets, breadcrumbs, tab titles) — the identity move where the
  instrument speaks in the typeface of the thing it inspects.
- **Flat, border-separated surfaces.** Panels are divided by hairlines, not shadows; window/dialog
  frames get one soft drop shadow. Elevation across the shell is essentially zero.
- **Low, consistent rounding.** Badges/chips ~6dp, controls/cards ~8–10dp, dialogs 12dp, window
  frames 14dp. Only the settings switch track is a pill.
- **Warm hues are reserved.** `ok` green, `warn` amber, `pink`, `cyan` appear only in syntax and
  status — never as chrome — so the accent stays the one hue of interaction.

### 1.2 Color palette (from the mockup's CSS vars → `theme/Color.kt`)

**Dark (primary)** — the `--*` custom properties on the mockup's root:

| Token | Hex | Material role | Use |
|---|---|---|---|
| `--bg` | `#15171C` | background | window base + **code editor** |
| `--panel` | `#1A1D24` | surface / surfaceContainer | sidebars, toolbar, tab strip, status bar, pane headers |
| `--panel2` | `#1E2229` | surfaceVariant / …High | dialogs, split sub-headers, search popover |
| `--elev` | `#232A34` | surfaceContainerHighest | chips, active pills, badge fills, segmented selection |
| `--line` | `#2B313B` | outline | primary hairline |
| `--line2` | `#232830` | outlineVariant | subtle hairline |
| `--tx` | `#E6E9EF` | onSurface | primary text / code |
| `--tx2` | `#9AA3B2` | onSurfaceVariant | secondary text |
| `--tx3` | `#697084` | `WorkbenchColors.onSurfaceFaint` | gutter numbers, section labels, dimmed metadata |
| `--acc` | `#7C8CF8` | primary | selection, primary action, active tab, focus |
| `--acc2` | `#A78BFA` | secondary / `accentSecondary` | brand gradient partner, keyword syntax, interface badge |
| `--ok` | `#5FD1A0` | `success` | strings, "decompiled", success |
| `--warn` | `#E6B566` | `warning` | numbers, resources, warnings, smali marker |
| `--pink` | `#F08FB0` | `pink` | annotations, smali directives, field/attr syntax |
| `--cyan` | `#5EC6D6` | `cyan` | type/class syntax, class badge, Java tab dot |
| close-red | `#F0655D` | error | error state |

**Light** (mockup Preferences `1f`): `--bg #F7F8FA · --panel #FFFFFF · --elev #EEF1F5 · --line
#E4E8EE · --line2 #EEF1F5 · --tx #1A1D24 · --tx2 #59636F · --tx3 #98A0AC · --acc #5B6EF5 · --acc2
#7C5CF5 · --ok #1F9D63 · --warn #C98A2B · --cyan #2B93A6`; `--pink` derived `#C85B86`; error
`#DB5147`.

**Syntax token palette** (`SyntaxColors`) — the seam the engine's `CodeMetadata` drives; taken from
the mockup's code samples (Java, XML, smali, Frida). Same roles, tuned per theme:

| Token | Dark (`--`) | Light | Mockup source |
|---|---|---|---|
| keyword | `--acc2` `#A78BFA` | `#7C5CF5` | `package`, `public`, `class`, `extends`, `return` |
| type | `--cyan` `#5EC6D6` | `#2B93A6` | `MainActivity`, `Bundle`, `Cipher` |
| method | `--acc` `#7C8CF8` | `#5B6EF5` | `onCreate`, `getInstance`, `decrypt` |
| string | `--ok` `#5FD1A0` | `#1F9D63` | `"AES/GCM"` |
| number | `--warn` `#E6B566` | `#C98A2B` | `2`, `6` |
| comment | `--tx3` `#697084` | `#98A0AC` | `/* loaded from: classes.dex */` |
| annotation | `--pink` `#F08FB0` | `#C85B86` | `@Override`, `.method`, `.registers` |
| field | `--pink` `#F08FB0` | `#C85B86` | field/attribute markers |
| plain | `--tx` `#E6E9EF` | `#1A1D24` | identifiers, variables |
| punctuation | `--tx2` `#9AA3B2` | `#59636F` | separators |

**Chrome tints** (`WorkbenchColors`): `gutterActiveText` = accent, `currentLineBackground` = accent
@10%, `treeSelectionBackground` = accent @13% (a rounded, **inset** band — not full-bleed),
`treeHoverBackground` = onSurface @5%, `tabActiveIndicator` = accent, plus `windowTintTop/Right`
(`#1C2029`/`#191C26`) for the mockup's soft radial glow.

### 1.3 Typography (`theme/Type.kt`)

Two faces: **Geist** (`UiFontFamily`) for chrome, **Geist Mono** (`MonoFontFamily`) for code +
readouts. Scale from the mockup:

| Style | Family | Size / line | Use |
|---|---|---|---|
| titleLarge | Geist SemiBold | 22 / 28 | start page, dialog titles |
| titleMedium/Small | Geist SemiBold | 16 / 13 | section + panel titles |
| bodyMedium (**chrome default**) | Geist | 13 / 18 | tree rows, tabs, buttons |
| bodySmall | Geist | 12 / 16 | secondary/dimmed labels, breadcrumb |
| labelSmall | Geist Medium | 11 | status text, badges |
| **SectionLabelStyle** | Geist SemiBold | 11, +0.1em, UPPERCASE | "PROJECT", "RECENT", "Methods · 3" |
| **CodeTextStyle** | Geist Mono | 13 / 21 | code viewer + gutter |
| **ReadoutTextStyle** | Geist Mono | 11.5 / 14 | status-bar readouts, breadcrumb, badges |

> **Fonts status.** Geist / Geist Mono are **not yet bundled**: `UiFontFamily`/`MonoFontFamily`
> currently alias `FontFamily.Default` / `FontFamily.Monospace` (system fallback — keeps the module
> asset-free and wasm-safe). **TODO(fonts):** vendor the Geist + Geist Mono TTFs as `composeResources`
> and point the two aliases at them; every call site reads the aliases (or the derived styles), so the
> swap is a one-file change with no call-site churn. (Fetching the TTFs was not possible from this
> environment; if a build dependency is needed for the resource wiring, it is flagged for the human.)

### 1.4 Spacing, rounding, elevation (`theme/Tokens.kt`, `theme/Shape.kt`)

- **Spacing**: 4dp base — `xxs 2 · xs 4 · sm 6 · md 8 · lg 12 · xl 16 · xxl 24 · xxxl 32`, plus named
  chrome dimensions from the mockup: `toolbarHeight 54 · paneHeaderHeight 44 · tabStripHeight 42 ·
  tabHeight 33 · breadcrumbHeight 38 · treeRowHeight 27 · statusBarHeight 28 · controlHeight 34 ·
  railWidth 54 · gutterMinWidth 52 · treeIndent 16 · iconButtonSize 34 · badgeSize 16`.
- **Rounding** (`Shapes`): `extraSmall 6 · small 8 · medium 10 · large 12 · extraLarge 14`.
- **Elevation**: zero in-shell; window/dialog frames carry one soft shadow (drawn by the platform
  window, not Material elevation).

### 1.5 Iconography (`component/Icons.kt`)

Self-contained vector glyphs drawn with `Canvas` (asset-free, wasm-safe): filled disclosure triangle
(`▸`/`▾`), close ×, pin dot, **square view-marker** (per-tab file-type dot), magnifier, directional
nav caret, and an outline **folder** glyph. Leaf tree nodes use **tinted letter badges** — a rounded
square filled with its hue at 14% and a mono glyph — matching the mockup exactly: **C** cyan class,
**I** violet interface, **m** blue method, **f** pink field, **@** pink annotation-class, **`<>`**
amber file/resource, **▦** image. Packages/directories get the outline folder (the mockup does not
badge containers). The brand **`jx`** mark is a gradient rounded square (`BrandMark`).

### 1.6 Window / layout structure ("Tabbed Classic", mockup `1a`)

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ [jx] wallet.apk │ Open │ ◀ ▶ │   ⌕ Search classes, methods… ⌘K   │ ●Deobf  Light │  toolbar (54)
├──────────────────────┬─────────────────────────────────────────────────────────┤
│ PROJECT     Flatten  │  ▪MainActivity ×   ▪WalletViewModel   ▪AndroidManifest    │  tab strip (42)
│ [Classes|Resources]  ├─────────────────────────────────────────────────────────┤
│ [ Filter tree…     ] │  com.acme › ui › MainActivity › onCreate()   [Java|Smali] │  breadcrumb (38)
│ ▾ com.acme.wallet    ├─────────────────────────────────────────────────────────┤
│   ▾ ui               │   1 │ package com.acme.wallet.ui;                         │
│     C MainActivity   │   7 │ public final class MainActivity extends … {         │  code viewer
│     C LoginFragment  │  14 │   this.viewModel = new WalletViewModel(…);          │  (gutter + tokens,
│   ▸ data             │     │                                                     │   virtualized)
├──────────────────────┴─────────────────────────────────────────────────────────┤
│ ● decompiled                                    JAVA   1,284 classes   UTF-8     │  status bar (28)
└────────────────────────────────────────────────────────────────────────────────┘
       ↑ resizable HorizontalSplitPane (fraction-based, tablet-adaptive)
```

Regions: **toolbar** (brand mark · project · Open · back/forward · centered command/search box with
`⌘K` hint · deobfuscation state · theme toggle) · **left tree pane** (Classes/Resources segmented
selector, Flatten toggle, filter, virtualized tree) · **tabbed editor** (connected-card tab strip +
**breadcrumb bar** with the Java/Smali view toggle + code viewer) · **status bar** (LED + mono status
on the left, mono readouts on the right) · **search** as a floating overlay. The mockup also defines
two alternative main layouts (**Command/Split** `1b`, **Research+Inspector** `1c`) selectable at
runtime — see §3 for their status.

---

## 2. Screen / panel / component inventory

### 2.1 Screens & panels (built)

| Element | Composable | File |
|---|---|---|
| App entry (theme owner + light/dark) | `JadxWorkbenchApp` | `workbench/Workbench.kt` |
| Workbench shell | `Workbench` | `workbench/Workbench.kt` |
| Top toolbar (brand, command box, deobf, theme) | `WorkbenchToolbar` (private) | `workbench/Workbench.kt` |
| Start / open page (drop zone + formats) | `StartPage` (private) | `workbench/Workbench.kt` |
| Breadcrumb bar (path + view toggle) | `BreadcrumbBar` (private) | `workbench/Workbench.kt` |
| Tree pane | `TreePane` | `workbench/TreePane.kt` |
| Editor area | `EditorArea` (private) | `workbench/Workbench.kt` |
| Code viewer | `CodeViewer` | `workbench/CodeViewer.kt` |
| Search overlay (scope chips + results) | `SearchPanel` | `workbench/SearchPanel.kt` |

### 2.2 Reusable components

| Component | Composable | File |
|---|---|---|
| Theme + token accessors | `JadxTheme` (fun + object) | `theme/Theme.kt` |
| Brand mark (gradient `jx`) | `BrandMark` | `component/Controls.kt` |
| Toolbar/text/primary/secondary buttons | `ToolbarButton`, `ToolbarTextButton`, `PrimaryButton`, `SecondaryButton` | `component/Controls.kt` |
| Segmented toggle · chip · kbd hint · status dot | `SegmentedToggle`, `Chip`, `Kbd`, `StatusDot` | `component/Controls.kt` |
| Vertical divider / group | `VDivider`, `ToolbarGroup` | `component/Controls.kt` |
| Panel · pane header · section label · empty state | `Panel`, `PaneHeader`, `SectionLabel`, `ThinDivider`, `EmptyState` | `component/Panel.kt` |
| Search / filter field | `SearchField` | `component/SearchField.kt` |
| Tree row (inset selection band) | `TreeRow` | `component/TreeRow.kt` |
| Node kind badge / folder glyph | `NodeKindBadge`, `FolderGlyph` | `component/Icons.kt` |
| Editor tab strip (connected cards) | `EditorTabStrip` | `component/TabStrip.kt` |
| Split panes (H/V, resizable) | `HorizontalSplitPane`, `VerticalSplitPane` | `component/SplitPane.kt` |
| Status bar + readout | `WorkbenchStatusBar`, `StatusReadout` | `component/StatusBar.kt` |
| Vector glyphs | `ExpandChevron`, `CloseGlyph`, `PinDot`, `SquareDot`, `SearchGlyph`, `DirectionCaret` | `component/Icons.kt` |

### 2.3 State (pure, tested) & the client seam

| Piece | Type | File |
|---|---|---|
| Tab open/close/pin/caret model | `TabsState` (immutable) | `workbench/TabsState.kt` |
| Back/forward history | `NavHistory` (immutable) | `workbench/NavHistory.kt` |
| Tree expansion/filter/flatten + row flattening | `TreeUiState`, `buildVisibleRows` | `workbench/TreeState.kt` |
| **Breadcrumb derivation** | `breadcrumbSegments`, `BreadcrumbSegment` | `workbench/Breadcrumb.kt` |
| Workbench view-model | `WorkbenchState` (StateFlow) | `workbench/WorkbenchState.kt` |
| Engine seam | `DecompilerClient` interface | `client/DecompilerClient.kt` |
| Data model | `NodeId`, `TreeNode`, `CodeDocument`, `CodeToken`, `SessionState`, `SearchQuery`… | `client/Models.kt` |
| Stub backend | `StubDecompilerClient`, `StubData`, `StubHighlighter` | `client/*` |

---

## 3. Mapping to the jadx-gui feature set

Legend: **F** = built · **S** = stubbed/seam present, needs `core:api` · **○** = UI not built yet
(later phase) · **E** = needs a new engine capability (see §4).

### Tier 1 (must-have — Phase 5)

| Feature | Status | Notes |
|---|---|---|
| Open apk/dex | S | `DecompilerClient.open`; stub simulates load. Start page drop-zone + browse; real file access via platform shells. |
| Class/resource tree | **F** | `TreePane` + lazy `childNodes`; tinted letter badges, folder glyphs, Classes/Resources segmented selector. |
| Flatten-packages toggle | **F** | `TreeUiState.flattenPackages` + `buildVisibleRows`. |
| Code viewer w/ metadata highlighting + line numbers | **F** | `CodeViewer` renders per-token `CodeToken` (stub-highlighted now, engine-driven later); virtualized. |
| Breadcrumb (package › class › member) | **F** | `BreadcrumbBar` + pure `breadcrumbSegments`. |
| Tabs (open/close/pin, caret restore) | **F** | `TabsState`; connected-card strip with view-type dots. |
| Jump-to-definition + back/forward | **F**/S | `NavHistory` + `CodeToken.definition` seam; stub tokens don't yet carry targets — engine metadata will. |
| Find usages | S/○ | fits the `search` seam; dedicated **usages/call-hierarchy** panel (mockup `2e`) not built. |
| Search (class/method/field/code/resource, regex, ignore-case) | **F**/S | `SearchPanel` with scope chips + Aa/`.*`; stub covers name search. Code/resource content search needs engine index. |
| Smali view | **F** | `CodeView.SMALI`; Java/Smali toggle in breadcrumb; stub smali sample. |
| Resource / image / hex viewing | S/○ | resource text via code viewer; **image + hex viewers not yet built** (mockup `2c` asset preview). |
| Settings + `.jadx`-style project persistence | ○ | preferences screen (mockup `1f`/`2a`) not built; needs kotlinx.serialization models. |
| Background jobs + progress + cancel | S | `SessionState.Loading(progress)` + status spinner; progress dialog (mockup `2n`) + cancel wiring land with the real scheduler. |

### Tier 2

| Feature | Status |
|---|---|
| Rename + comments (persisted), deobfuscation toggle | ○ UI (mockup `2h`/`2i`); toolbar shows deobf state — **E** for persistence/remap |
| Split Java↔smali with caret sync | ○ (`VerticalSplitPane`/`HorizontalSplitPane` exist; the dual-editor `1b` + caret sync is **E**) |
| Bytecode/simple/fallback decompile modes | S (`DecompilerArgs.mode` later; shown in Preferences `1f`) |
| Go-to Main Activity/Application/Manifest, go-to-class palette (`⌘N`) | S/○ (palette mockup `1b`/`2g`) |
| Copy as Frida/Xposed, convert number, JSON prettify | ○ (mockup `2j`, context menu `2g`) |
| Preferences UI (themes, fonts, shortcuts, keymap) | ○ (theme toggle built; full prefs `1f`/`2p` later) |
| Start page / recent projects | **F** partial (`StartPage` drop-zone + formats; recents list later) |
| Export gradle/java/smali/json project | ○ (mockup `2m`) |
| Plugin manager, script console | ○ (mockup `2k`/`2l`) |
| APK summary + signature, issues/log | ○ (mockup `2d`/`2o`) |

### Tier 3 (defer)

Graphs (call/inheritance/CFG), ADB smali debugger + logcat, marketplace, live-reload, mobile layout
(`1g`) — **○**, Phase 8+. Several are **E** (see §4).

### Alternative main layouts (mockup Turn 1)

The mockup ships **three** runtime-selectable workspaces. **`1a` Tabbed Classic is built** (default,
and the closest match to the tier-1 shell). **`1b` Command/Split** (activity rail + dual Java∥Smali
editor + `⌘K` command palette) and **`1c` Research+Inspector** (search-first bar + right usage/
structure inspector) are **○** — designed, not yet built; the split-pane and segmented primitives
they need already exist. Layout selection lives in Preferences (`2a`).

---

## 4. Engine follow-ups the design implies (NEW capabilities jadx lacks)

Flagged as **engine** work, not UI:

1. **Live, bidirectional smali ↔ Java editing.** jadx's smali is read-only *display*. Editing smali
   and re-deriving Java (or vice-versa) needs a **smali assembler** + incremental re-decompile path.
2. **Caret-synced Java↔smali split** (mockup `1b`). Needs an **offset correspondence map** between
   emitted Java and the underlying smali/bytecode — new metadata from `core:codegen`.
3. **Kotlin source view.** `CodeView.KOTLIN` is wired; idiomatic Kotlin output is `core:codegen-kotlin`
   (Roadmap Phase 7) — a differentiator jadx does not offer well.
4. **Persistent rename/comment + deobfuscation remap** (mockup `2h`/`2i`). Editable, importable/
   exportable mapping model; the tree/code viewer must re-render on remap → engine **change/event
   stream** (invalidate + re-emit affected nodes).
5. **Cross-reference index** for find-usages, call hierarchy (`2e`), and code/resource content search.
   A whole-program **usage graph** + searchable code index in `core:api` (stub search is name-only).
6. **Cancelable, chunked, yielding decompilation with progress** (`2n`). The UI exposes progress/
   cancel; the engine `DecompilerScheduler` drives it (critical on single-threaded wasm).
7. **Jump-to-definition targets in code metadata.** Each reference token needs a resolved
   `definition: NodeId`; the viewer already consumes `CodeToken.definition`.
8. **Structure outline + gutter modes** (`2f`): per-class member outline and source/bytecode-offset/
   debug line-number modes — metadata from `core:codegen`.
9. **APK summary, signature verification, decompile issues/log** (`2d`/`2o`) — input + report data
   surfaced through `core:api`.
10. **Graph views** (call/inheritance/CFG) — a non-Graphviz renderer + engine graph extraction (Tier 3).

---

## 5. The `DecompilerClient` seam

`ui:app` depends on the engine **only** through the `DecompilerClient` interface it defines
(`client/DecompilerClient.kt`). `core:api` (not yet created) will implement it by adapting its
`Decompiler`/`DecompilerScheduler`. Until then the shell renders against `StubDecompilerClient`. This
honors the architecture's rule (UI talks to the engine only through a facade) **and** lets the entire
GUI be built, previewed, and unit-tested with zero engine dependency. Everything that can take time is
`suspend` (cancelable); observable state is `StateFlow`; nothing assumes a thread (wasm-safe).

`CodeDocument`/`CodeToken`/`TokenKind` are the **stub shape of the engine's per-offset `CodeMetadata`**.
The code viewer is driven by this metadata, not a re-lexer — swapping the stub for the engine feed
does not change the viewer.

---

## 6. Multiplatform & quality discipline

- All UI logic is in **`commonMain`**; **no `java.*`** — verified by `:ui:app:compileKotlinWasmJs`.
- No bundled assets yet; icons are `Canvas`-drawn; fonts are platform defaults behind the
  `UiFontFamily`/`MonoFontFamily` aliases (see §1.3 TODO to bundle Geist).
- State via `StateFlow`/Compose state; async work launched on a composition scope and cancelable.
- **Theme-aware** (system light/dark + toggle) and **tablet-adaptive** (fraction-based split, relative
  sizing, dense-but-not-desktop-hardcoded chrome).
- Pure state logic (`TabsState`, `NavHistory`, `buildVisibleRows`, `breadcrumbSegments`) and the stub
  (`StubData`, `StubHighlighter`) are unit-tested in `commonTest` (**40 tests**, green on `jvmTest`).

---

## 7. Module layout (`ui:app`)

```
ui/app
├── build.gradle.kts                    # Compose MP library; jvm/js/wasmJs/android; commonMain-first
└── src
    ├── commonMain/kotlin/com/jadxmp/ui
    │   ├── theme/      Tokens · Color · Type · Shape · Theme
    │   ├── component/  Icons · Controls · Panel · SearchField · TreeRow · TabStrip · SplitPane · StatusBar
    │   ├── client/     Models · DecompilerClient · StubHighlighter · StubData · StubDecompilerClient
    │   └── workbench/  TabsState · NavHistory · TreeState · Breadcrumb · WorkbenchState · CodeViewer · TreePane · SearchPanel · Workbench
    └── commonTest/kotlin/com/jadxmp/ui
        ├── client/     StubDataTest · StubHighlighterTest
        └── workbench/  TabsStateTest · NavHistoryTest · TreeStateTest · BreadcrumbTest
```

Wired into `settings.gradle.kts` as `:ui:app`. It depends only on Compose + coroutines — **not** on
`core:api` (which does not exist yet). When `core:api` lands, add it as a dependency and provide a
`DecompilerClient` implementation; no shell/component code needs to change.

### Verified
- `./gradlew :ui:app:compileKotlinJvm` — green
- `./gradlew :ui:app:compileKotlinWasmJs` — green (wasm-safe discipline holds)
- `./gradlew :ui:app:jvmTest` — green (40 tests)
