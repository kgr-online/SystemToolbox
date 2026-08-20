#!/system/bin/sh
# Regenerate the module's hosts file from persisted state before the
# systemless mount snapshot is taken, in case blacklist.txt or the
# enabled/disabled state changed since the last boot (e.g. edited via
# WebUI, then rebooted, without the app re-running rebuild itself).
MODDIR=${0%/*}
sh "$MODDIR/hosts_ctl.sh" rebuild >/dev/null 2>&1

# --- explicit bind mount -------------------------------------------------
# hosts_ctl.sh's rebuild() assumes /system/etc/hosts is already
# bind-mounted to $MODDIR/system/etc/hosts (so its live-edit `cp` takes
# effect without a reboot). That assumption doesn't hold on every
# metamodule/mount-backend combo (e.g. Mountify auto mode has been
# observed not picking this path up), so do the bind mount ourselves
# here rather than depending on it. Idempotent - skips if already mounted
# by the metamodule, harmless to run every boot.
MOD_HOSTS="$MODDIR/system/etc/hosts"
LIVE_HOSTS=/system/etc/hosts

if [ -f "$MOD_HOSTS" ] && ! grep -qs " $LIVE_HOSTS " /proc/mounts; then
  chcon u:object_r:system_file:s0 "$MOD_HOSTS" 2>/dev/null
  mount -o bind "$MOD_HOSTS" "$LIVE_HOSTS" 2>/dev/null
fi
