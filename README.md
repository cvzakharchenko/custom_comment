# Custom Comment

![Build](https://github.com/cvzakharchenko/custom_comment/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
A JetBrains IDE plugin for toggling custom comment strings on selected lines.

## Features

- **Toggle Custom Comments**: Add or remove custom comment strings from lines with a single action
- **Multi-line Support**: Works on selected text across multiple lines
- **Multiple Cursors**: Fully supports multiple cursors/carets
- **Configurable per Language/Extension**: Define different comment styles for different file types
- **Multiple Comment Strings**: Configure multiple comment strings - all are checked for removal, but only the primary one is added
- **Flexible Positioning**: Three positioning modes for inserting comments
- **Auto-advance Cursor**: After toggling, cursor automatically moves to the next line

## How It Works

1. **Adding Comments**: If the first line doesn't have any configured comment prefix, the primary (first) comment string is added to all selected lines
2. **Removing Comments**: If the first line has any of the configured comment prefixes, it is removed from all selected lines
3. **Decision Based on First Line**: When multiple lines are selected, the decision to add or remove is always based on the first line's state
4. **Cursor Movement**: After the action completes, the cursor automatically moves to the next line

## Configuration

Go to **Settings** → **Editor** → **Custom Comment** to configure:

- **Comment Strings**: Enter multiple comment strings (one per line). The first one is used when adding comments, all are checked when removing.
- **Language ID**: Optionally specify a language ID (e.g., `JAVA`, `kotlin`, `C++`, `Python`, `JavaScript`)
- **File Extensions**: Alternatively, specify file extensions (comma-separated, e.g., `cpp, h, hpp`)
- **Insert Position**: Choose how comments are positioned:
  - **First column**: Insert at column 0
  - **After whitespace**: Insert after leading whitespace
  - **Align with previous**: Insert at the same column as the previous line's comment (or earlier if the line content starts before that column)

### Common Language IDs

- `JAVA`
- `kotlin`
- `C++` / `ObjectiveC`
- `Python`
- `JavaScript` / `TypeScript`
- `TEXT`
- `HTML` / `XML`

## Usage Example

For C++ debugging with custom markers:

1. Add a configuration with:
   - Comment Strings: `// DEBUG: `, `// TEMP: `, `// REMOVE: `
   - Language ID: `C++` (or extensions: `cpp, h, hpp, c`)
   
2. Use the keyboard shortcut `Ctrl+Alt+;` (or find "Toggle Custom Comment" in the editor context menu)

3. First trigger: Adds `// DEBUG: ` to lines without any marker
4. Subsequent triggers on marked lines: Removes the marker
5. After removal, triggering again: Adds `// DEBUG: ` again (always the first configured string)

<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Custom Comment"</kbd> >
  <kbd>Install</kbd>

- Manually:

  Download the [latest release](https://github.com/cvzakharchenko/custom_comment/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Default Keyboard Shortcut

- **Windows/Linux**: `Ctrl+Alt+;`
- **macOS**: `Ctrl+Alt+;`

You can customize this in **Settings** → **Keymap** → search for "Toggle Custom Comment"

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
