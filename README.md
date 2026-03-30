# BundleManager

BundleManager installs and removes third-party config/content bundles for other plugins while keeping the process reversible.

## Requirements

- Java 17+
- Spigot/Paper 1.18+

## What It Does

- Scans bundles from `plugins/BundleManager/bundles`
- Supports bundle sources as `.zip` files or directories
- Tracks installed state in `plugins/BundleManager/data`
- Auto-loads bundles on startup
- `/bm reload` syncs the folder instead of reinstalling everything blindly
- Uninstalls bundles that were removed from disk
- Reinstalls bundles whose SHA-1 changed
- Supports bundle and package variants
- Queues overwrite conflicts for manual approval when needed

## Supported Packages

- `Blueprints`
- `DeluxeMenus`
- `ItemsAdder`
- `MCPets`
- `MMOItems`
- `ModelEngine`
- `MythicLib`
- `MythicMobs`
- `Nexo`
- `Oraxen`
- `ResourcePack`

## Folders

Bundle input:

```text
plugins/BundleManager/bundles
```

Persistent state:

```text
plugins/BundleManager/data
```

Resource packs:

```text
plugins/BundleManager/pack
```

Non-`.zip` files in `bundles/` are ignored and reported on startup or reload.

## Commands

```text
/bm list
/bm enable <bundleId> [package]
/bm disable <bundleId> [package]
/bm reload
/bm variant <bundleId>
/bm chose <index>
/bm conflicts
/bm resolve <conflictId> <overwrite|skip>
/bm supported
```

## Variants

- BundleManager supports both bundle variants and package variants
- Package variants override bundle variants
- Use `/bm variant <bundleId>` to view choices
- Use `/bm chose <index>` to apply a choice

## Safety Rules

- Installers are additive by default
- Config mutations must be rollback-safe
- Existing files are not silently overwritten
- File names stay unchanged by default
- A file is only renamed when:
  - the target already exists
  - the installer explicitly marks that file as rename-safe
- If overwrite needs approval, the package is placed into the conflict queue

## Logging

Startup and reload warnings/errors include bundle context, and package/variant context when needed. Examples:

```text
[LostAssets.zip | DeluxeMenus] Package 'DeluxeMenus' conflicts with existing menu id 'main' ...
[BossPack.zip | MythicMobs - vanilla] Package 'MythicMobs@vanilla' contains duplicate mob id ...
```

If a bundle contains a package for a plugin that is installed on the server but not yet supported by BundleManager, BundleManager warns and points users to the GitHub issue tracker.

## Build

```text
./gradlew build
```
