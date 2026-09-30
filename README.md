# SimpleQuickKeys

**English** | [中文](README_zh.md)

> A client-side Minecraft mod that opens a card-style quick panel with a single key, keeping your most-used items, keybinds and commands in one place.
> Running out of keyboard keys? Every panel slot has its own **virtual key**, and the panel can also fire features from other mods directly — including hotkeys that never had a key of their own.

## Features

- **One key to open**: `G` opens and closes the panel, `Esc` closes it; the key can be changed in Controls
- **Card panel**: 9 columns × 3 rows, 27 slots per page, 3 pages (81 slots in total); turn pages with the arrows or the mouse wheel; a 16:9 panel with cut corners and a translucent body, which steps down to smaller layouts in small windows
- **Trigger anything**: click a slot to fire it, no keybind required; hold-type actions such as sneaking or sprinting become toggles (the card lights up and shows an indicator bar)
- **Hotkeys from other mods**: if you run malilib-based mods such as Tweakeroo, MiniHUD or Litematica, their hotkeys appear in the key picker and the panel triggers the feature itself, so a slot does not need a key of its own; with none of them installed the picker simply shows what the game has
- **Key picker sorted by source**: a **Source** column on the left lists vanilla key bindings first and then one entry per mod (JEI, FTB, ...), with unclassifiable ones under "Other"; the source column has its own search box that matches mod names, category names and feature names
- **Grouped by category**: inside a source, functions are listed under category headings
- **Bound hotkeys are visible**: the card, its tooltip and the edit dialog all show the name of the hotkey's feature; if it can no longer be resolved (the mod was removed, the hotkey was renamed), the slot shows "unknown hotkey" together with the original identifier instead of going blank
- **Commands**: each slot can hold one command (for example `home` or `gamemode creative`); a command takes priority when set, and typing gives the same completion as chat
- **Item icons**: use any item as an icon, with a built-in keyword search picker
- **Colours everywhere**: tint the whole panel (R / G / B plus opacity) and tint each slot's card on its own (opacity included)
- **Your own background image**: drop a png or jpg into `config/key_panel/backgrounds/` and pick it in game; 16:9 is recommended and other ratios are centre-cropped to fill the panel; the picker has "Open folder" / "Refresh" buttons and a thumbnail grid — click a thumbnail to use it, click it again to clear
- **Tooltips**: hover a card to see its full name and how it is triggered
- **Client-side only**: works in singleplayer and on any server, nothing to install on the server

## Usage

### Opening the panel

Press `G` in game. You can change the key in **Options → Controls → Key Binds**, under the **Key Panel** category. `Esc` closes the panel.

### Using a slot

- **Left-click** a slot to trigger it. Commands, other mods' hotkeys and ordinary game functions can all be put on a slot.
- **Right-click** a slot to edit it. The dialog lets you change the **item ID**, **name**, **key binding** and **command**:
  - "Pick icon" — search for an item by keyword
  - "Pick key" — choose a function in the key picker (see below)
  - "Clear slot" — empty the slot
- A slot bound to another mod's hotkey shows that hotkey's feature name in a read-only field of the dialog.

### Colours, swapping and background image

Click the **`edit`** button at the bottom of the panel to open the edit strip below it (right-click a slot to leave).

- **Left side** — `Slot` / `Panel` / `Image` choose what you are editing: the selected slot's card, the whole panel, or the background image picker.
- **Right side** — four **R / G / B / A** sliders set that target's colour and opacity; the square swatch on the far right shows the result, and clicking it resets the colour.
- In `Slot` mode you can also click two slots to swap their contents.

### Using hotkeys from other mods

Open a slot for editing, click **"Pick key"**, then pick the mod's entry in the **Source** column on the left (or type in its search box). The list on the right shows that mod's functions grouped by category; click one to fill the slot being edited. When the source is a malilib-based mod (Tweakeroo, MiniHUD, Litematica ...), clicking that slot later runs the feature directly — no key has to be assigned to it.

### Files

Everything lives in `config/key_panel/`:

- `slots.json` — what each slot holds (created automatically the first time you open the panel); safe to edit or back up
- `background.json` — the panel's colour and the background image in use
- `backgrounds/` — your background images (png / jpg)

## Known limitations

- **Hold-type hotkeys are tapped once.** Hotkeys of malilib-based mods run their own action when triggered from the panel, but a hold-type hotkey is only pressed and released — it will not stay held.
- **Mouse-bound hotkeys may not work.** If the direct call is unavailable, the panel falls back to pressing the bound keys, and mouse buttons or mouse/key combinations may not go through.
- A very small number of game functions cannot be simulated at all. If you find one, please report the mod or feature in the [issue tracker](https://github.com/TrZeal/Simple-Quick-Keys/issues).
- **Commands on servers** are limited by your permissions (for example `gamemode` needs OP).
- **Your own keybinds are not touched.** The panel borrows a key only for the moment it triggers something and restores it immediately.

## Versions and requirements

Current version: **1.2.0**

| Minecraft | Loader | Java |
|---|---|---|
| 1.19.2 | Forge | 17 |
| 1.20.1 | Forge | 17 |
| 1.21.1 | NeoForge | 21 |

Install only one of them: put the jar that matches your Minecraft version into `.minecraft/mods/`. The mod is client-side only: it works in singleplayer and on servers, and the server does not need it.

## License

[MIT License](LICENSE) — free to use, modify and redistribute (just keep the copyright notice).
