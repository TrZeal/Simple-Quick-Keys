# SimpleQuickKeys

**English** | [中文](README_zh.md)

> A client-side Minecraft mod that opens a card-style quick panel with a single key, keeping your most-used items, keybinds and commands in one place.
> Running out of keyboard keys? Every panel slot has its own **virtual key**, so you can put any feature on the panel (including features from other mods) and trigger it from there.

![Panel preview](docs/preview.png)

## Features

- **One key to open**: press `G` to open or close the panel, `Esc` to close it
- **Card panel**: 9 columns × 3 rows, 27 slots per page; turn pages with the arrows or the mouse wheel, 3 pages in total (81 slots); the layout scales down automatically in small windows
- **Item icons**: use any item as an icon, with a built-in keyword search picker (16×9 grid, draggable scrollbar)
- **Trigger anything**: click a slot to trigger it, no keybind required; hold-type actions such as sneaking or sprinting become toggles (the card turns cyan and shows an indicator bar)
- **Commands**: each slot can hold one command (for example `home` or `gamemode creative`); it takes priority when set, with chat-style command completion while typing
- **Independent virtual keys**: every slot has its own virtual key, so any feature (including ones from other mods) can be triggered from the panel — the answer to running out of keyboard keys
- **Move slots**: the "Move slot" button at the bottom swaps the contents of two slots; keep clicking to move more, then click it again to exit
- **Tooltips**: hover a card to see its full name and how it is triggered
- **Client-side only**: works in singleplayer and on servers, nothing to install on the server

## Installation

1. Download the jar matching your Minecraft version from the [Releases page](https://github.com/TrZeal/Simple-Quick-Keys/releases)
   (Forge 1.19.2 / Forge 1.20.1 / NeoForge 1.21.1 — use only one of them)
2. Put it into `.minecraft/mods/`
3. Start the game and press **`G`** to open the panel
4. **Left-click** a slot to trigger it; **right-click** a slot to edit it

In the edit dialog you can change the **item ID**, **name**, **keybind** and **command**:

- "Choose icon" = search for an item by keyword
- "Choose key" = pick one of the game's features
- "Clear slot" = empty the slot

Use **`◀ 1/3 ▶`** at the bottom to turn pages, and **"Move slot"** to reorder slots.

## Configuration

Stored in `config/key_panel/slots.json` (created automatically the first time you open the panel). You can edit or back it up directly.

## FAQ

**Q: I clicked a slot and nothing happened.**
A: That slot is not configured yet — right-click it and choose an icon / key / command.

**Q: A feature does nothing when I click it.**
A: A very small number of features cannot be simulated (for example vanilla "screenshot", which only reacts to real key presses). Please report the mod or feature in the issue tracker.

**Q: My command did not run.**
A: On multiplayer servers commands are limited by permissions (for example `gamemode` requires OP).

**Q: Will this break my vanilla keybinds?**
A: No. The mod binds a key only while the panel triggers something, and restores it immediately, so your own keys keep working.

## License

[MIT License](LICENSE) — free to use, modify and redistribute (just keep the copyright notice).

