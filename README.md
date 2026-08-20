# System Toolbox

A root app (KernelSU-Next/APatch/Magisk) that bundles a set of
previously-separate device tweaks into one UI, organized into three
bottom-bar sections. Forked from
[Key2Toolbox](https://github.com/kgr-online/Key2Toolbox) (originally
built for the BlackBerry Key2 'Athena') with everything tied to that
device's physical keyboard, LED, and kernel quirks stripped out, leaving
only the modules that are portable across devices.

**Info**
- **Device status landing page**: build (model, Android, LineageOS,
  security patch, kernel), battery (level, health, temperature, voltage,
  technology, capacity-health % and charge cycles from sysfs), and root
  status

**System**
- **AdBlock** - systemless-hosts ad/tracker blocking, with search,
  add/remove, whitelist, and remote source list management. Deploys a
  Magisk-style module to `/data/adb/modules/`
- **Denylist Manager** - optional, opt-in unified control for Magisk's
  DenyList and the Zygisk-Hide module's own per-app config, plus a
  shortcut into HMA-OSS's manager app
- **Persistent wireless ADB** - on a user-chosen static port
- **Play Store Tagger** - retag (or untag) installed apps as Play
  Store-installed, so apps that check the install source stop complaining
- **ZRAM** - compression algorithm + size (Off / 2GB / 3GB / 4GB) +
  swappiness selector

**Settings**
- **Application Updater**
- **Quick access** - Check root status
- **Backup/Restore** - Backup and restore your ZRAM and AdBlock settings.
  Select which modules to include.

The UI follows Material You (Monet), in light or dark to match the
system. Most modules are stateless: they fire root commands on demand
and persist by installing a script to `/data/adb/service.d/`. AdBlock is
the exception - since `/system` can't be remounted RW on most modern
builds, it deploys a full Magisk-style module to `/data/adb/modules/`
instead of a `service.d` script, so its edits only take effect once that
module's mount is active (see below).

## Root implementation compatibility

Root access goes through `libsu` (`RootShell.kt`), which shells out to
whatever `su` is on the device - this doesn't care whether that's
KernelSU-Next, APatch, or Magisk. Two things worth knowing about
specific modules:

- **AdBlock** deploys an actual root-manager module rather than just
  running commands, so it depends on that module being mounted
  correctly by whichever root implementation is installed. Its
  `post-fs-data.sh` explicitly bind-mounts `/system/etc/hosts` to the
  module's copy on every boot if it isn't already mounted, rather than
  trusting the root implementation's own mount backend to have done it -
  some combinations (e.g. Mountify auto mode) have been observed not
  picking this up on their own.
- **Denylist Manager** only understands Magisk's DenyList and the
  Zygisk-Hide module's own config - it doesn't know about KernelSU-Next
  or APatch's own hide mechanisms. It defaults OFF and warns inline
  rather than assuming a particular root setup is present, so it's safe
  to leave alone on non-Magisk setups.

## Signing with your existing keystore

`app/build.gradle.kts` has a commented-out `signingConfigs { create("release") {...} }`
block referencing `kgr_signing.keystore` / alias `kgr`. Uncomment it, point
`storeFile` at your keystore path, and wire `signingConfig = signingConfigs.getByName("release")`
into the `release` build type. Consider passing the password via
`gradle.properties` (gitignored) or an env var rather than committing it in
the build script.

## How each module works

### AdBlock (`AdBlockController`)
Systemless-hosts ad/tracker blocking, ported from the standalone
[systemless-hosts](https://github.com/kgr-online/systemless-hosts) module
(itself based on gloeyisk/systemless-hosts) into a `k2tb_adblock`-namespaced
module driven by a bundled `hosts_ctl.sh`, with its WebUI replaced by a
native Compose screen.
- **Two path roots**: `/data/adb/modules/k2tb_adblock` is the mounted module
  itself (wiped and redeployed on every install), while
  `/data/adb/k2tb_adblock` holds the persistent sources/edits/whitelist,
  so re-installing the module never loses user edits.
- **Install**: seeds the persist dir with the bundled default blacklist
  (~273k entries, `assets/adblock_default_hosts.txt`) and empty edit files
  if not already present, stages `hosts_ctl.sh` + `post-fs-data.sh` into the
  module dir, compiles once, then writes `module.prop` last so a
  half-deployed module is never picked up mid-write.
- **Reboot requirement**: the module's overlay onto `/system/etc/hosts`
  only activates at boot. Rather than trusting the mount table (some root
  implementations show the overlay as a plain block-device mount with no
  reference to the module path at all), `requiresReboot()` checks for a
  content marker `hosts_ctl.sh`'s `rebuild()` writes on a successful
  mirror - a much more reliable signal across root implementations than
  parsing `mount` output.
- **Live edits, no reboot** (once installed): add/remove/whitelist domains
  and glob patterns, add/remove remote source URLs, trigger a source update,
  and enable/disable filtering all shell out to `hosts_ctl.sh`, which
  recompiles and mirrors straight onto the live `/system/etc/hosts`.
- **Source updates run in the background** on the shell side and can take
  longer than a couple of seconds with ~270k+ entries; the screen polls
  `hosts_ctl.sh update_status` in a loop rather than a single delayed check,
  since the status string doesn't change while still running.
- **Backup/Restore**: `sources.txt`, `user_added.txt`, `wildcard_added.txt`,
  `user_removed.txt`, and `whitelist.txt` round-trip as line arrays under an
  `"adblock"` key; restoring installs the module first if it isn't already
  present on the device.
- All user-supplied domains/URLs going into `hosts_ctl.sh` shell commands
  are escaped (`'` → `'\''`) before being wrapped in single quotes, since
  this module - unlike most others here - takes free-text user input.

### Denylist Manager (`DenylistController`)
Unified control for the two hide-lists this app can own end-to-end: Magisk's
DenyList (`magisk --denylist ls/add/rm/status`) and the Zygisk-Hide module's
own `config.json` (a flat `{ "pkg": true }` map at
`/data/adb/modules/zygisk-hide/config.json`, read fresh by its companion on
every app launch - toggling an app off *removes* its key rather than setting
it `false`).
- **Master toggle, defaults OFF**: this module only understands Magisk +
  Zygisk-Hide, not every root/hide combination (KernelSU-Next, APatch, HMA's
  own denylist, etc.), so it never touches either backend's state until
  explicitly enabled. Screens for people using those other setups
  independently are unaffected either way.
- **Adding an app denies its full process set**, not just the base package:
  `declaredProcesses()` enumerates every `android:process` an app declares
  across its activities/services/providers/receivers via `PackageManager`
  (requires `QUERY_ALL_PACKAGES`, already granted for Play Store Tagger) and
  adds all of them to Magisk's DenyList in one toggle. Trimming back to a
  subset of an app's sub-processes is left to Magisk's own DenyList UI.
- **HMA-OSS is intentionally NOT integrated directly.** Its live config
  lives at a randomized `/data/misc/hide_my_applist_<suffix>/config.json`
  path with a real nested schema (hook items, templates) and is designed to
  be written through a Binder IPC interface rather than as a stable on-disk
  format - too fragile to build against directly. Instead this screen just
  launches HMA-OSS's own manager app
  (`org.frknkrc44.hma_oss/...ui.activity.MainActivity`) for that piece.
- Warns inline if Magisk isn't detected, if Magisk's DenyList *enforcement*
  is globally off (edits would silently do nothing), or if Zygisk-Hide isn't
  installed, rather than assuming a particular root setup is present.
- Enabled apps (denied on either backend) sort to the top of the list,
  re-sorting live on toggle rather than waiting for the next full refresh.

### Play Store Tagger (`PlayStoreTaggerManager`)
Retags (or untags) already-installed apps as Play Store-installed, for apps
that check their own install source and refuse to run/update otherwise.
Extracted from a standalone app of the same name
([kgr17/PlayStoreTagger](https://github.com/kgr17/PlayStoreTagger)), with
changes mirrored back manually between the two.
- **Tag / Untag mode**: switching to Untag flips the default filter from
  "Non-Play" to "All" so already-tagged apps are visible to reverse.
- Resolves each app's APK path(s) via `pm path`; a single APK goes through
  a plain `pm install -i com.android.vending --dont-kill -r`, while
  split/multi-APK installs go through the full session flow
  (`pm install-create` → `pm install-write` per split, sized via `stat`, →
  `pm install-commit`, with `pm install-abandon` on any failed write).
  Untagging re-runs the same flow with no `-i` flag.
- Filter chips: **Non-Play** (default) / **All**, plus a **System** toggle
  to include system packages. Search box, per-app checkboxes, running app
  count, and a scrollable log panel that streams each `pm` command's output
  live during a batch operation.

### Wireless ADB (`WirelessAdbController`)
- User enters a port; **persist** installs `assets/adb_wireless_template.sh`
  (with `__PORT__` substituted) to `/data/adb/service.d/adb_wireless.sh`,
  which sets `adb_wifi_enabled` and pins `persist.adb.tcp.port` /
  `service.adb.tcp.port` at boot.
- **Live apply** sets the same properties immediately.
- The screen also shows the device's current WLAN IP (via `ip route get`,
  checked against a `wlan*`-named interface so it doesn't report a cellular
  IP when WiFi is down) and the live port, so you can confirm the
  `adb connect <ip>:<port>` target at a glance.
- If you previously had a separate static-port script, remove it once this
  module's persistence is confirmed working, so two boot scripts aren't
  racing to set the same property.

### ZRAM (`ZramController`)
- **Compression algorithm**: read dynamically from
  `/sys/block/zram0/comp_algorithm`, so the screen only offers algorithms
  this kernel actually supports (commonly: `lzo`, `lz4`, `zstd`, `deflate`).
- **Size**: Off / 2GB / 3GB / 4GB.
- **Persist**: installs `assets/zram_template.sh` (with `__ALGO__` and
  `__SIZE_MB__` substituted) to `/data/adb/service.d/zram_size.sh`. Selecting
  "Off" removes the script.
- **Live apply** (behind a confirmation dialog): `swapoff` → reset → set
  `comp_algorithm` → set `disksize` → `mkswap` → `swapon`. This briefly
  disables swap and can cause background apps to be killed - the dialog
  warns about this, default is reboot-to-apply.

## ⚠ Known risk: writing to `/data/adb/service.d/` from the app

In a previous session (on the original Key2 hardware), **every attempt to
write to `/data/adb/service.d/` from a root shell post-boot failed with
"Permission denied"** - including `su -c` over ADB using `>`, `dd`, etc.
The only thing that worked was an `install -m 755` from an **interactive
Termux `su` session**. The likely cause is a filesystem-encryption-context
mismatch between the shell session that originally created files there
(the initial `adb shell` session at setup time) and any shell spawned
afterwards.

This app's root shell (via `libsu`) is yet another shell context, spawned at
app-runtime, so it **may hit the same wall** on a given device. Each
module's screen shows a "Persisted: Yes/No" status that's read back from
disk after every write, so you'll see immediately if a persist operation
silently failed.

**If persistence writes fail from the app:**
1. The app's live-apply / enable-now actions still work (they don't touch
   `service.d`), so the toggles remain useful for testing.
2. For persistence, fall back to the Termux method: copy the relevant script
   from `app/src/main/assets/` (or pull it from the app's
   `filesDir` - the app writes a staging copy there before attempting the
   `install`), then from Termux:
   ```
   su
   install -m 755 /path/to/script.sh /data/adb/service.d/script.sh
   ```
3. If you find a write path that *does* work from an app-spawned root shell,
   it's worth updating `AssetInstaller.installFromAsset` here so the app can
   self-persist reliably.

## Extending

- For stateless root-command modules, add a `core`-style controller in
  `modules/`, following the pattern of `ZramController` /
  `WirelessAdbController` (persist via `AssetInstaller`, live-apply via
  `RootShell.run`).
- For anything that needs to overlay a read-only `/system` path rather than
  just persist a `service.d` script, follow `AdBlockController`: deploy a
  full Magisk-style module to `/data/adb/modules/`, keep persistent state
  *outside* the module dir so reinstalls don't lose it, and detect whether
  the overlay is actually active via a content check rather than the mount
  table.
- For anything that manages another root tool's own state rather than the
  device directly, follow `DenylistController`: prefer that tool's
  documented CLI (`magisk --denylist ...`) over parsing its internal storage
  format where one exists, read/write flat on-disk config directly only when
  the format is genuinely stable (Zygisk-Hide's own `config.json`, since
  it's this project's own format), and fall back to just launching the other
  app when its config is neither documented nor stable (HMA-OSS). Gate
  anything that writes to state you don't fully control behind an
  explicit, defaulted-off master toggle.
- Either way, add a corresponding screen in `ui/` (following e.g.
  `ZramScreen.kt` / `WirelessAdbScreen.kt`, all built on the shared
  `ScreenScaffold`), and wire it into `DetailHost` plus `systemScreens` in
  `ui/HomeScreen.kt` and the entry in `ui/Screen.kt`.
- Drop any new boot scripts in `app/src/main/assets/`.
