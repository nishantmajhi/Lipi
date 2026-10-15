package com.lipi.util;

import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Registers / unregisters fonts with Windows beyond just copying the file
 * into C:\Windows\Fonts.
 *
 * <p><b>Why this class exists</b> - copying a .ttf/.otf file straight into
 * C:\Windows\Fonts (what {@code FontService} used to do, and still does, on
 * its own) is necessary but NOT sufficient. The Fonts folder that File
 * Explorer / Windows Settings shows you is a special, registry-backed view:
 * it lists whatever is recorded under
 * {@code HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts},
 * not a plain directory listing of that folder. So a plain {@code Files.copy}
 * can succeed perfectly (no exception, no error dialog - which is exactly why
 * Lipi reported success) while the font still appears completely absent in
 * Explorer/Settings/other apps, because Windows itself was never told the
 * font exists. This class adds the missing registry entry so Windows
 * actually recognizes the font as installed, and best-effort notifies
 * already-running applications via WM_FONTCHANGE.</p>
 *
 * <p><b>Diagnostics</b> - all operations are logged to a persistent
 * {@code lipi-debug.log} file next to the application's fonts folder.
 * This is essential because {@code System.err} is invisible when the app
 * is launched via the jpackage native launcher or {@code javaw} (no console
 * attached), making it impossible to distinguish between the failure modes
 * (RemoveFontResourceW not running, file still locked by Font Driver Host,
 * PowerShell blocked by AV/policy, etc.) without a real log.</p>
 */
public final class WindowsFontRegistry {

    /**
     * Registry key for system-wide fonts (requires Administrator).
     * Fonts installed here are available to all users.
     */
    private static final String FONTS_REGISTRY_KEY_SYSTEM =
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts";

    /**
     * Registry key for per-user fonts (no elevation needed).
     * Used on Windows 10/11 when not running as Administrator.
     * Fonts here are only available to the current user.
     */
    private static final String FONTS_REGISTRY_KEY_USER =
            "HKCU\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts";

    private static final DateTimeFormatter LOG_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Log file path. Initialised once from the app dir so the file lands
     * next to the fonts/ folder, not in some temp directory.
     * Set by {@link #setLogFile(Path)} called from Lipi.main().
     */
    private static volatile Path logFile = null;

    /**
     * Path to a small persisted list of font files that have been scheduled
     * for deletion via {@link #scheduleDeleteOnReboot} but have not yet
     * actually disappeared from disk (i.e. the reboot hasn't happened yet).
     * Set by {@link #setPendingDeleteFile(Path)} from {@code Lipi.main()}.
     *
     * <p>This exists to fix a follow-on bug from the reboot-fallback: once
     * {@code unregister()} removes the registry entry, {@code SystemUtil
     * .getSystemFontPath()} can no longer tell the registry says "not
     * installed" - so its existence-fallback (Step 2) sees the still-present
     * bytes on disk and reports the font as installed again, even though the
     * user already removed it and Explorer/Settings agree it's gone (because
     * those read the registry, not the disk). Tracking these paths
     * ourselves lets {@code FontService.isFontInstalled()} say "not
     * installed" for them until the next real reboot actually deletes the
     * file.</p>
     */
    private static volatile Path pendingDeleteFile = null;

    private WindowsFontRegistry() {}

    /**
     * Directs diagnostic output to {@code path}. Call once at startup
     * (e.g. from {@code Lipi.main()}) before any font operations run.
     * If never called, diagnostics fall back to {@code System.err} only.
     */
    public static void setLogFile(Path path) {
        logFile = path;
        log("=== Lipi session started ===");
    }

    /**
     * Directs the persisted "pending reboot deletion" list to {@code path}.
     * Call once at startup, before any font scan runs. On the call, the file
     * is also pruned: any entry whose file no longer exists on disk is
     * dropped, since that means the reboot already happened and Windows
     * really did delete it - the entry has done its job.
     */
    public static void setPendingDeleteFile(Path path) {
        pendingDeleteFile = path;
        pruneStalePendingDeletes();
    }

    /**
     * Records that {@code file} has been scheduled for delayed deletion
     * (via {@link #scheduleDeleteOnReboot}) so that, until the next real
     * reboot, it should be treated as "not installed" even though the bytes
     * are still physically present on disk.
     */
    public static synchronized void recordPendingDelete(File file) {
        if (pendingDeleteFile == null) return;
        try {
            java.util.Set<String> current = loadPendingDeletesRaw();
            current.add(file.getAbsolutePath().toLowerCase());
            Files.write(pendingDeleteFile, current,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log("recordPendingDelete: tracked " + file.getAbsolutePath());
        } catch (IOException e) {
            log("recordPendingDelete: failed to persist for " +
                    file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Clears the pending-delete record for {@code file}, if any. Used when a
     * font is reinstalled at the same path it was previously scheduled to be
     * deleted from, so the new copy isn't mistakenly hidden as "not
     * installed".
     */
    public static synchronized void clearPendingDelete(File file) {
        if (pendingDeleteFile == null) return;
        try {
            java.util.Set<String> current = loadPendingDeletesRaw();
            if (current.remove(file.getAbsolutePath().toLowerCase())) {
                Files.write(pendingDeleteFile, current,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log("clearPendingDelete: cleared " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            log("clearPendingDelete: failed for " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Returns the set of absolute paths (lower-cased) currently believed to
     * be scheduled for deletion at next reboot but still physically present
     * on disk. Empty (never null) if no pending-delete file is configured or
     * none are tracked.
     */
    public static synchronized java.util.Set<String> loadPendingDeletes() {
        if (pendingDeleteFile == null) return java.util.Collections.emptySet();
        return loadPendingDeletesRaw();
    }

    private static java.util.Set<String> loadPendingDeletesRaw() {
        try {
            if (!Files.exists(pendingDeleteFile)) return new java.util.LinkedHashSet<>();
            java.util.List<String> lines = Files.readAllLines(pendingDeleteFile);
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            for (String line : lines) {
                if (!line.isBlank()) set.add(line.trim());
            }
            return set;
        } catch (IOException e) {
            log("loadPendingDeletes: failed to read: " + e.getMessage());
            return new java.util.LinkedHashSet<>();
        }
    }

    /**
     * Drops any pending-delete entry whose file is already gone from disk
     * (the reboot happened, Windows actually deleted it) so the tracking
     * file doesn't grow forever across sessions.
     */
    private static synchronized void pruneStalePendingDeletes() {
        // recordPendingDelete, clearPendingDelete and loadPendingDeletes all
        // check this first; this one did not, so configuring no list at all -
        // which is what happens on any platform that is not Windows - threw a
        // NullPointerException out of setPendingDeleteFile rather than doing
        // nothing.
        if (pendingDeleteFile == null) return;
        try {
            java.util.Set<String> current = loadPendingDeletesRaw();
            if (current.isEmpty()) return;
            java.util.Set<String> stillPending = new java.util.LinkedHashSet<>();
            for (String path : current) {
                if (Files.exists(Path.of(path))) {
                    stillPending.add(path);
                } else {
                    log("pruneStalePendingDeletes: " + path + " is gone - reboot deletion completed");
                }
            }
            if (stillPending.size() != current.size()) {
                Files.write(pendingDeleteFile, stillPending,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            log("pruneStalePendingDeletes: failed: " + e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Adds the registry entry that makes Windows treat {@code fontFile} (which
     * must already have been copied into the appropriate Fonts directory) as an
     * installed font, then asks already-running applications to refresh their
     * font lists.
     *
     * <p>Uses {@code HKCU} (per-user) when the font lives under
     * {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts\}, and {@code HKLM}
     * (system-wide) otherwise. Writing to the wrong hive causes the font to
     * appear installed in one view but not another, or not at all.</p>
     */
    public static void register(File fontFile) throws IOException {
        String registryKey = registryKeyForFile(fontFile);
        String valueName   = registryValueName(fontFile);
        String data        = registryDataFor(fontFile);
        log("register: adding registry entry \"" + valueName + "\" -> " +
                data + " in " + registryKey);
        runRegCommand(new String[]{
                "reg", "add", registryKey,
                "/v", valueName,
                "/t", "REG_SZ",
                "/d", data,
                "/f"
        }, "add");
        log("register: registry entry added OK");
        broadcastFontChangeBestEffort();
    }

    /**
     * Removes the registry entry for {@code fontFile}. Safe to call even if no
     * entry exists (e.g. it was installed by an older version of Lipi that
     * only copied the file) - that case is treated as a no-op, not a failure.
     *
     * <p>Tries both the system-wide ({@code HKLM}) and per-user ({@code HKCU})
     * registry hives, because the font may have been installed by any version
     * of Lipi or by Windows itself under either location.</p>
     *
     * <p>Per Microsoft's documented font-deletion order: a font that is
     * loaded in the active session must first be released with
     * {@code RemoveFontResource} before its file can be deleted or its
     * registry entry removed.</p>
     */
    public static void unregister(File fontFile) {
        log("unregister: starting for " + fontFile.getAbsolutePath());
        unloadFontResourceBestEffort(fontFile);

        String valueName = registryValueName(fontFile);
        // Try the hive that matches where the file actually lives first,
        // then try the other hive as a fallback (handles fonts installed by
        // older Lipi versions or by Windows itself under a different path).
        String primaryKey   = registryKeyForFile(fontFile);
        String secondaryKey = primaryKey.equals(FONTS_REGISTRY_KEY_SYSTEM)
                ? FONTS_REGISTRY_KEY_USER : FONTS_REGISTRY_KEY_SYSTEM;

        boolean deleted = tryRegDelete(primaryKey, valueName, fontFile.getName());
        if (!deleted) {
            tryRegDelete(secondaryKey, valueName, fontFile.getName());
        }
        broadcastFontChangeBestEffort();
    }

    /** Attempts {@code reg delete} on one hive; returns true if the entry was found and removed. */
    private static boolean tryRegDelete(String registryKey, String valueName, String fontName) {
        try {
            runRegCommand(new String[]{
                    "reg", "delete", registryKey,
                    "/v", valueName,
                    "/f"
            }, "delete");
            log("unregister: registry entry deleted OK from " + registryKey);
            return true;
        } catch (IOException e) {
            log("unregister: entry not found in " + registryKey + " for " + fontName +
                    ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Last-resort fallback for a font file Windows still won't release even
     * after {@link #unregister}. On Windows 10/11, font parsing/rendering is
     * handled by a separate system service (Font Driver Host,
     * {@code fontdrvhost.exe}) which can hold its own lock on a font file
     * independently of anything a regular process - even an elevated one -
     * does with {@code RemoveFontResourceW}. There is no supported way for an
     * ordinary application to force that service to let go without a reboot.
     *
     * <p>This mirrors what installers (MSI etc.) do for exactly this
     * situation: schedule the file for deletion at next boot via
     * {@code MoveFileEx(path, null, MOVEFILE_DELAY_UNTIL_REBOOT)}, recorded by
     * Windows itself in the registry's PendingFileRenameOperations list.
     * Since the registry Fonts entry is already gone by the time this runs,
     * the font is effectively uninstalled immediately from the user's point
     * of view (gone from Explorer/Settings/other apps) - only the leftover
     * bytes on disk wait for the next restart to actually disappear.</p>
     *
     * @return true if Windows accepted the scheduled deletion
     */
    public static boolean scheduleDeleteOnReboot(File file) {
        log("scheduleDeleteOnReboot: attempting MoveFileEx for " + file.getAbsolutePath());

        // Fix 2: Two-stage approach for scheduling a file deletion on reboot.
        //
        // Stage A — MoveFileEx(MOVEFILE_DELAY_UNTIL_REBOOT):
        //   We pass the raw path (no \\?\ prefix). The \\?\ prefix bypasses the
        //   shell namespace, but MoveFileEx with DELAY_UNTIL_REBOOT writes to
        //   PendingFileRenameOperations in HKLM\SYSTEM\...\Session Manager, and
        //   Windows Session Manager reads those entries at boot using the raw NT
        //   path it stored — so the raw Win32 path is correct here.
        //   The previous attempt with \\?\ still failed (LastError: 3) because
        //   MoveFileEx requires the process to hold SeBackupPrivilege or be running
        //   as SYSTEM, not just a UAC-elevated Admin token.  We try it anyway and
        //   fall through to Stage B on failure.
        //
        // Stage B — reg add PendingFileRenameOperations (fallback):
        //   Writing the registry entry directly only requires the standard elevated
        //   Admin token (no extra privilege needed), making it the reliable fallback
        //   when Stage A fails. The value format Windows expects is a REG_MULTI_SZ
        //   where each deletion is a pair of lines: "\??\<path>" followed by an
        //   empty string (meaning "delete, not rename").  The \??\ NT namespace
        //   prefix is required by Session Manager regardless of what MoveFileEx
        //   would have stored.
        String rawPath = file.getAbsolutePath();
        // NT namespace path for the reg-based fallback (\??\ not \\?\)
        String ntPath  = "\\??\\" + rawPath;

        String psRaw   = escapeForPowerShellSingleQuoted(rawPath);
        String psNt    = escapeForPowerShellSingleQuoted(ntPath);

        // Guard against Add-Type "type already exists" error on repeated calls.
        String script =
                // ── Stage A: MoveFileEx ───────────────────────────────────────────
                "if (-not ([System.Management.Automation.PSTypeName]'LipiWin32.KernelNativeMethods').Type) {\r\n" +
                "    Add-Type -MemberDefinition '" +
                "[DllImport(\"kernel32.dll\", SetLastError=true, CharSet=CharSet.Unicode)] " +
                "public static extern bool MoveFileEx(string lpExistingFileName, string lpNewFileName, uint dwFlags);'" +
                " -Name KernelNativeMethods -Namespace LipiWin32\r\n" +
                "}\r\n" +
                // MOVEFILE_DELAY_UNTIL_REBOOT = 0x4; destination $null = delete on reboot
                "$ok = [LipiWin32.KernelNativeMethods]::MoveFileEx('" + psRaw + "', $null, 4)\r\n" +
                "$lastErr = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()\r\n" +
                "Write-Output \"MoveFileEx returned: $ok  LastError: $lastErr\"\r\n" +
                "if ($ok) { exit 0 }\r\n" +
                // ── Stage B: reg add PendingFileRenameOperations ──────────────────
                // Falls through here only if MoveFileEx failed.
                // PendingFileRenameOperations is a REG_MULTI_SZ; each deletion is a
                // pair: the NT path to delete, followed by an empty string.
                // We append to any existing value with /f (force) and type MULTI_SZ.
                // PowerShell: build the pair as an array and join with \0.
                "$regKey = 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager'\r\n" +
                "try {\r\n" +
                "    $existing = (Get-ItemProperty -Path $regKey -Name PendingFileRenameOperations -ErrorAction SilentlyContinue).PendingFileRenameOperations\r\n" +
                "    if ($existing -eq $null) { $existing = @() }\r\n" +
                // Append our deletion pair: NT path + empty string
                "    $newVal = $existing + @('" + psNt + "', '')\r\n" +
                "    Set-ItemProperty -Path $regKey -Name PendingFileRenameOperations -Value $newVal -Type MultiString\r\n" +
                "    Write-Output 'PendingFileRenameOperations updated via reg fallback'\r\n" +
                "    exit 0\r\n" +
                "} catch {\r\n" +
                "    Write-Output \"reg fallback failed: $_\"\r\n" +
                "    exit 1\r\n" +
                "}\r\n";

        boolean ok = runPowerShellScript(script, "schedule deletion on reboot for " + file.getName());
        log("scheduleDeleteOnReboot: result=" + ok + " for " + file.getName());
        return ok;
    }

    /**
     * Cancels a reboot-time deletion previously queued by
     * {@link #scheduleDeleteOnReboot} for {@code file}.
     *
     * <p><b>Why this is needed:</b> {@code scheduleDeleteOnReboot} records the
     * font's path in Windows' {@code PendingFileRenameOperations} list, which
     * Session Manager acts on at the next boot. That record lives in the
     * registry and outlives Lipi entirely - {@link #clearPendingDelete} only
     * drops Lipi's own bookkeeping entry, it does not touch what Windows has
     * queued. So reinstalling a font that was removed via the reboot fallback
     * would previously leave the deletion still scheduled: the install would
     * appear to succeed, the font would work for the rest of the session, and
     * then Windows would delete the file at the next restart, leaving a
     * registry entry pointing at nothing. Cancelling the queued deletion is
     * what makes a reinstall actually stick.</p>
     *
     * <p>Entries in {@code PendingFileRenameOperations} are stored as pairs -
     * a source path followed by a destination, where an empty destination
     * means "delete". Removing one deletion therefore means removing both
     * elements of its pair, leaving every other queued operation intact.
     * Paths are matched with the {@code \??\} NT-namespace prefix stripped,
     * since {@code MoveFileEx} and the registry fallback can each store the
     * path in a slightly different form.</p>
     *
     * @return true if the queued deletion was removed, or if there was nothing
     *         queued for this file in the first place
     */
    public static boolean cancelScheduledDelete(File file) {
        String psPath = escapeForPowerShellSingleQuoted(file.getAbsolutePath());

        String script =
                "$regKey = 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager'\r\n" +
                "$target = '" + psPath + "'\r\n" +
                // Strip a leading \??\ (and any stray trailing NUL) so the two
                // storage forms compare equal.
                "function Norm([string]$p) {\r\n" +
                "    if ($p -eq $null) { return '' }\r\n" +
                "    $s = $p.Trim([char]0)\r\n" +
                "    if ($s.StartsWith('\\??\\')) { $s = $s.Substring(4) }\r\n" +
                "    return $s\r\n" +
                "}\r\n" +
                "$existing = (Get-ItemProperty -Path $regKey -Name PendingFileRenameOperations -ErrorAction SilentlyContinue).PendingFileRenameOperations\r\n" +
                "if ($existing -eq $null) { Write-Output 'nothing queued'; exit 0 }\r\n" +
                "$existing = @($existing)\r\n" +
                "$keep = @()\r\n" +
                "$removed = 0\r\n" +
                "for ($i = 0; $i -lt $existing.Count; $i += 2) {\r\n" +
                "    $src = $existing[$i]\r\n" +
                "    $dst = ''\r\n" +
                "    if ($i + 1 -lt $existing.Count) { $dst = $existing[$i + 1] }\r\n" +
                // Only drop pure deletions (empty destination) for our path; a
                // pair with a real destination is a rename someone else queued.
                "    if ((Norm $src) -ieq (Norm $target) -and (Norm $dst) -eq '') {\r\n" +
                "        $removed++\r\n" +
                "        continue\r\n" +
                "    }\r\n" +
                "    $keep += $src\r\n" +
                "    $keep += $dst\r\n" +
                "}\r\n" +
                "if ($removed -eq 0) { Write-Output 'no matching deletion queued'; exit 0 }\r\n" +
                "try {\r\n" +
                "    if ($keep.Count -eq 0) {\r\n" +
                "        Remove-ItemProperty -Path $regKey -Name PendingFileRenameOperations\r\n" +
                "    } else {\r\n" +
                "        Set-ItemProperty -Path $regKey -Name PendingFileRenameOperations -Value $keep -Type MultiString\r\n" +
                "    }\r\n" +
                "    Write-Output \"cancelled $removed queued deletion(s)\"\r\n" +
                "    exit 0\r\n" +
                "} catch {\r\n" +
                "    Write-Output \"failed to rewrite PendingFileRenameOperations: $_\"\r\n" +
                "    exit 1\r\n" +
                "}\r\n";

        boolean ok = runPowerShellScript(script, "cancel scheduled deletion for " + file.getName());
        log("cancelScheduledDelete: result=" + ok + " for " + file.getAbsolutePath());
        return ok;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Calls the Win32 {@code RemoveFontResourceW} GDI function to force the
     * current session to unload {@code fontFile}, releasing whatever lock the
     * OS is holding on it so the file can actually be deleted afterwards.
     * Best-effort: if this fails (e.g. the font was never loaded via this
     * mechanism), deletion is attempted anyway rather than aborting here.
     */
    private static void unloadFontResourceBestEffort(File fontFile) {
        log("unloadFontResource: calling RemoveFontResourceW for " + fontFile.getAbsolutePath());
        String psPath = escapeForPowerShellSingleQuoted(fontFile.getAbsolutePath());
        // Guard against Add-Type "type already exists" if called more than once.
        //
        // Fix 1: Distinguish error 203 (ERROR_ENVVAR_NOT_FOUND) from real failures.
        // RemoveFontResourceW returns 0 with LastError=203 when the font was never
        // loaded into this GDI session via AddFontResourceW (the common case for
        // system fonts loaded by Windows itself at boot).  That is NOT an error we
        // should treat as a warning: it just means GDI doesn't have a session lock
        // to release, so we can proceed straight to Files.delete().  We exit 0 for
        // LastError==203 and exit 1 only for genuine failures (other non-zero errors),
        // so the log clearly distinguishes the two cases.
        String script =
                "if (-not ([System.Management.Automation.PSTypeName]'LipiWin32.GdiNativeMethods').Type) {\r\n" +
                "    Add-Type -MemberDefinition '" +
                "[DllImport(\"gdi32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] " +
                "public static extern int RemoveFontResourceW(string lpFileName);'" +
                " -Name GdiNativeMethods -Namespace LipiWin32\r\n" +
                "}\r\n" +
                "$removed = [LipiWin32.GdiNativeMethods]::RemoveFontResourceW('" + psPath + "')\r\n" +
                "$lastErr = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()\r\n" +
                "Write-Output \"RemoveFontResourceW returned: $removed  LastError: $lastErr\"\r\n" +
                // Exit 0 when: (a) it succeeded ($removed > 0), or (b) LastError is 203
                // (font was never loaded in this session - not an error, just a no-op).
                // Exit 1 only for genuine failures so the caller's log is unambiguous.
                "if ($removed -eq 0 -and $lastErr -ne 203) { exit 1 }\r\n";

        boolean ok = runPowerShellScript(script, "unload font resource for " + fontFile.getName());
        // Not a fatal failure - log and continue. The file delete will reveal
        // the real picture: if it succeeds, the lock was already gone anyway;
        // if it fails with a sharing violation we fall through to the reboot fallback.
        if (!ok) {
            log("unloadFontResource: RemoveFontResourceW reported failure for " +
                    fontFile.getName() + " - will still attempt Files.delete()");
        } else {
            log("unloadFontResource: RemoveFontResourceW succeeded for " + fontFile.getName());
        }
    }

    /**
     * Builds the registry value name Windows expects, e.g. "Mangal (TrueType)".
     * Falls back to the file name (extension stripped) if the font's own
     * family name can't be read (corrupt/unusual font file) - an imperfect
     * label still makes the font show up and load correctly, since GDI keys
     * off the file referenced in the value's data, not the value's name.
     */
    /**
     * Returns the correct registry hive for {@code fontFile} based on where
     * the file actually lives on disk.
     *
     * <ul>
     *   <li>Files under {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts\} →
     *       {@code HKCU} (per-user, no elevation required)</li>
     *   <li>Everything else (system path, or path not yet determined) →
     *       {@code HKLM} (system-wide, requires Administrator)</li>
     * </ul>
     */
    private static String registryKeyForFile(File fontFile) {
        return isInPerUserStore(fontFile) ? FONTS_REGISTRY_KEY_USER : FONTS_REGISTRY_KEY_SYSTEM;
    }

    /** True when {@code fontFile} lives under {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts}. */
    private static boolean isInPerUserStore(File fontFile) {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) return false;
        String perUserFontsDir = java.nio.file.Paths.get(
                localAppData, "Microsoft", "Windows", "Fonts").toString().toLowerCase();
        return fontFile.getAbsolutePath().toLowerCase().startsWith(perUserFontsDir);
    }

    /**
     * The string to store as the registry value's data, which is what tells
     * Windows where the font file actually is.
     *
     * <p>The two hives want different things, and the difference is not
     * cosmetic. {@code HKLM} entries are resolved against
     * {@code %WINDIR%\Fonts}, so a bare file name is correct and is what
     * Windows itself stores there. {@code HKCU} has no such base directory:
     * the per-user store lives under {@code %LOCALAPPDATA%}, the registry has
     * no way to know that, and every per-user entry Windows writes is an
     * absolute path.</p>
     *
     * <p>Writing a bare name into {@code HKCU} - which this class did for
     * every unelevated install, the ordinary case for a user without
     * Administrator rights - produces an entry that names a file in
     * {@code %WINDIR%\Fonts} that is not there. The copy succeeds, the
     * registry write succeeds, {@code reg add} reports no error, and the font
     * does not load. That is precisely the failure described at the top of
     * this class, reintroduced in the branch that was added to avoid needing
     * elevation.</p>
     */
    private static String registryDataFor(File fontFile) {
        return isInPerUserStore(fontFile) ? fontFile.getAbsolutePath() : fontFile.getName();
    }

    /**
     * Queries the registry to determine which hive a font is registered in.
     *
     * <p>This is the authoritative way to find a font's real physical store on
     * Windows 10/11. The shell namespace creates compatibility symlinks that
     * make per-user fonts appear to exist at {@code C:\Windows\Fonts} too,
     * so {@code Files.exists()} on the system path is unreliable. The registry
     * hive has no such ambiguity: {@code HKCU} means per-user store,
     * {@code HKLM} means system store, unregistered means neither.</p>
     *
     * @param fontFileName  the bare filename, e.g. {@code "0001a__.ttf"}
     * @return {@code Boolean.TRUE}  if registered in {@code HKCU} (per-user)<br>
     *         {@code Boolean.FALSE} if registered in {@code HKLM} (system-wide)<br>
     *         {@code null}          if not found in either hive
     */
    /**
     * Reads the entire contents of both font registry hives in exactly two
     * {@code reg query} calls (one per hive) and returns a map from
     * lower-cased font filename to "is this in HKCU (per-user)?".
     *
     * <p><b>Why this exists:</b> {@link #isRegisteredInHkcu(String)} was
     * originally written as a one-font-at-a-time lookup, fine for
     * install/remove of a single font. But once {@code SystemUtil
     * .getSystemFontPath()} started calling it for every font during the
     * directory scan at startup (needed to correctly tell per-user fonts
     * apart from system fonts), a 300-font library means up to 600
     * {@code reg.exe} process spawns - each one is cheap in isolation but
     * the JVM fork/exec + process-table overhead adds up to the 1-2 minute
     * startup users started seeing. Querying each hive's entire value list
     * exactly once and reusing the result for every font in the scan turns
     * that into 2 process spawns total, regardless of library size.</p>
     *
     * <p>Parses lines of the form Windows' {@code reg query <key>} (no
     * {@code /v}) prints for each value:
     * <pre>    Mangal (TrueType)    REG_SZ    MANGAL.TTF</pre>
     * The font filename is the last whitespace-delimited token (registry
     * data for font values is always just the bare filename, never
     * contains spaces in practice for these keys, but even if it did the
     * filename is still recoverable as everything after the REG_SZ marker).</p>
     */
    public static Map<String, Boolean> queryAllRegisteredFonts() {
        Map<String, Boolean> result = new java.util.HashMap<>();
        for (String hive : new String[]{ FONTS_REGISTRY_KEY_USER, FONTS_REGISTRY_KEY_SYSTEM }) {
            boolean isHkcu = hive.equals(FONTS_REGISTRY_KEY_USER);
            try {
                Process p = new ProcessBuilder("reg", "query", hive)
                        .redirectErrorStream(true)
                        .start();
                String output = new String(p.getInputStream().readAllBytes());
                int exit = p.waitFor();
                if (exit != 0) {
                    log("queryAllRegisteredFonts: 'reg query " + hive + "' exited " + exit);
                    continue;
                }
                indexRegQueryOutput(output, isHkcu, result);
            } catch (Exception e) {
                log("queryAllRegisteredFonts: error querying " + hive + ": " + e.getMessage());
            }
        }
        log("queryAllRegisteredFonts: loaded " + result.size() + " registered font entries");
        return result;
    }

    /**
     * Adds every font value in one hive's {@code reg query} output to
     * {@code into}, keyed by font file name.
     *
     * <p>Separated from {@link #queryAllRegisteredFonts()} so it can be tested
     * against captured output from both hives. What the two hives put in the
     * data field differs, and getting that wrong is silent - the map simply
     * comes back with keys nobody looks up.</p>
     *
     * <p><b>The two storage forms.</b> {@code HKLM} stores a bare file name,
     * resolved against {@code %WINDIR%\Fonts}:
     * <pre>    Arial (TrueType)    REG_SZ    arial.ttf</pre>
     * {@code HKCU} has no implicit base directory, so it stores an absolute
     * path:
     * <pre>    Kantipur Regular (TrueType)    REG_SZ    C:\Users\me\AppData\Local\Microsoft\Windows\Fonts\Kantipur Regular.ttf</pre>
     * Callers ask about a font by its bare file name, so the key has to be the
     * last path component in both cases. Keying on the raw data - which this
     * method used to do - produced entries under a full path for every
     * per-user font, and nothing ever looked one up: the per-user hive was
     * read, parsed, and then silently unreachable.</p>
     *
     * <p>An entry is also indexed under its full path, since that costs
     * nothing and lets a caller that already holds a resolved path ask
     * directly.</p>
     *
     * <p><b>When both hives name the same file</b> the per-user answer wins.
     * {@link #queryAllRegisteredFonts()} reads {@code HKCU} first and nothing
     * here overwrites a key that is already set, so the first hive read
     * decides. Per-user is the right winner: it is the store an unelevated
     * user can actually modify, so resolving there lets install and remove
     * succeed where the system path would have demanded Administrator for a
     * font the user has their own copy of.</p>
     */
    static void indexRegQueryOutput(String output, boolean isHkcu, Map<String, Boolean> into) {
        for (String line : output.split("\r?\n")) {
            int marker = line.indexOf("REG_SZ");
            if (marker < 0) continue;
            String data = line.substring(marker + "REG_SZ".length()).trim();
            if (data.isEmpty()) continue;

            String lower = data.toLowerCase();
            into.putIfAbsent(lower, isHkcu);

            // The bare file name is what every caller asks about. File.getName()
            // is not usable here: on a non-Windows JVM it does not treat a
            // backslash as a separator, and this output is always Windows-shaped.
            String bare = lower.substring(
                    Math.max(lower.lastIndexOf('\\'), lower.lastIndexOf('/')) + 1);
            if (!bare.isEmpty()) into.putIfAbsent(bare, isHkcu);
        }
    }

    public static Boolean isRegisteredInHkcu(String fontFileName) {
        return isRegisteredInHkcu(fontFileName, null);
    }

    /**
     * Same as {@link #isRegisteredInHkcu(String)}, but if {@code snapshot}
     * is non-null, looks the answer up in it instead of spawning {@code reg
     * query} subprocesses. See {@link #queryAllRegisteredFonts()} for why
     * this matters: spawning a fresh {@code reg.exe} process per font (up to
     * two - one per hive) is what made startup take minutes once this
     * registry-aware check was added, since loading 300 fonts means 300
     * file-existence questions, each potentially launching 2 processes.
     */
    public static Boolean isRegisteredInHkcu(String fontFileName, Map<String, Boolean> snapshot) {
        if (snapshot != null) {
            Boolean inHkcu = snapshot.get(fontFileName.toLowerCase());
            return inHkcu; // null if not present in either hive - same contract as below
        }
        for (String hive : new String[]{ FONTS_REGISTRY_KEY_USER, FONTS_REGISTRY_KEY_SYSTEM }) {
            try {
                Process p = new ProcessBuilder(
                        "reg", "query", hive, "/f", fontFileName, "/d", "/e")
                        .redirectErrorStream(true)
                        .start();
                String output = new String(p.getInputStream().readAllBytes());
                int exit = p.waitFor();
                if (exit == 0 && output.contains(fontFileName)) {
                    boolean isHkcu = hive.equals(FONTS_REGISTRY_KEY_USER);
                    log("isRegisteredInHkcu: " + fontFileName + " found in " + hive);
                    return isHkcu;
                }
            } catch (Exception e) {
                log("isRegisteredInHkcu: error querying " + hive + ": " + e.getMessage());
            }
        }
        log("isRegisteredInHkcu: " + fontFileName + " not found in either hive");
        return null;
    }

    private static String registryValueName(File fontFile) {
        String familyName = readFamilyName(fontFile);
        String suffix = fontFile.getName().toLowerCase().endsWith(".otf") ? "(OpenType)" : "(TrueType)";
        return familyName + " " + suffix;
    }

    private static String readFamilyName(File fontFile) {
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, fontFile);
            String family = font.getFamily();
            if (family != null && !family.isBlank()) return family;
        } catch (Exception ignored) {
            // Fall through to filename-based fallback below.
        }
        String name = fontFile.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void runRegCommand(String[] command, String action) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Could not start 'reg " + action + "': " + e.getMessage(), e);
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("'reg " + action + "' exited with code " + exitCode +
                        (output.isBlank() ? "" : ": " + output.trim()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for 'reg " + action + "'", e);
        }
    }

    /**
     * Best-effort WM_FONTCHANGE broadcast so applications that are already
     * running pick up the new/removed font without needing a restart.
     * Newly-launched applications will see the change regardless (they read
     * the registry fresh on startup) - this step is purely a convenience for
     * apps that are already open, so any failure here is swallowed rather
     * than surfaced as an install/remove error.
     */
    private static void broadcastFontChangeBestEffort() {
        // Guard against Add-Type "type already exists" if broadcast is called
        // multiple times in the same session (e.g. install then remove).
        String script =
                "if (-not ([System.Management.Automation.PSTypeName]'LipiWin32.NativeMethods').Type) {\r\n" +
                "    Add-Type -MemberDefinition '" +
                "[DllImport(\"user32.dll\", SetLastError=true)] " +
                "public static extern bool SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, IntPtr lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);'" +
                " -Name NativeMethods -Namespace LipiWin32\r\n" +
                "}\r\n" +
                "[UIntPtr]$result = [UIntPtr]::Zero\r\n" +
                // HWND_BROADCAST=0xFFFF, WM_FONTCHANGE=0x1D, SMTO_ABORTIFHUNG=0x0002
                "[LipiWin32.NativeMethods]::SendMessageTimeout([IntPtr]0xffff, 0x1D, [UIntPtr]::Zero, [IntPtr]::Zero, 0x0002, 5000, [ref]$result) | Out-Null\r\n";

        runPowerShellScript(script, "broadcast WM_FONTCHANGE");
    }

    /**
     * Runs a PowerShell script via a temporary .ps1 file rather than the
     * "-Command &lt;inline string&gt;" form.
     *
     * <p>Inline {@code -Command} strings go through two independent layers of
     * quote parsing before PowerShell ever sees them: first the OS/JDK pack
     * the Java String[] into a single Windows command line (escaping any
     * embedded quotes), then PowerShell's own argv handling re-parses that
     * same command line back into its {@code -Command} argument. Scripts
     * that mix single- and double-quotes (exactly what's needed here for a
     * C# {@code Add-Type} member definition) routinely get mangled by that
     * round trip with no useful error - it just silently does nothing, which
     * is why fonts kept failing to delete even while correctly elevated.
     * Writing the script to a file and running it with {@code -File} avoids
     * that double-parsing entirely, the same way the UAC elevation script
     * elsewhere in this class is already handled via a temp .vbs file.</p>
     *
     * <p>All output (stdout + stderr merged) and the exit code are written
     * to the persistent log file so failures can be diagnosed even when the
     * app has no console attached.</p>
     */
    private static boolean runPowerShellScript(String script, String description) {
        File tempPs1 = null;
        try {
            tempPs1 = File.createTempFile("lipi_font", ".ps1");
            tempPs1.deleteOnExit();
            Files.writeString(tempPs1.toPath(), script);

            // -ExecutionPolicy Bypass is required, not convenient: the default
            // policy on Windows client editions is Restricted, which refuses to
            // run any .ps1 file at all, and AllSigned/RemoteSigned would need
            // this generated script to carry a signature. The scope is one
            // process running one file this method just wrote to the current
            // user's own temp directory; it changes no machine setting and
            // leaves nothing behind.
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", tempPs1.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();

            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            int exitCode = finished ? p.exitValue() : -1;

            if (!finished) {
                p.destroyForcibly();
                log("TIMEOUT trying to " + description);
                return false;
            } else if (exitCode != 0) {
                log("FAILED to " + description + " (exit " + exitCode + "): " + output.trim());
                return false;
            } else {
                log("OK " + description + (output.isBlank() ? "" : " -> " + output.trim()));
                return true;
            }
        } catch (Exception e) {
            log("ERROR could not " + description + ": " + e.getMessage());
            return false;
        } finally {
            if (tempPs1 != null) tempPs1.delete();
        }
    }

    /** Escapes a value for embedding inside a PowerShell single-quoted string literal. */
    private static String escapeForPowerShellSingleQuoted(String value) {
        return value.replace("'", "''");
    }

    // ── Logging ───────────────────────────────────────────────────────────

    /**
     * Writes a timestamped line to both {@code System.err} and the persistent
     * log file (if one has been configured via {@link #setLogFile(Path)}).
     *
     * <p>Writing to both ensures the message is visible in IDE/terminal runs
     * (via stderr) AND in headless jpackage/javaw runs (via the log file).
     * The log file is opened in APPEND mode so entries accumulate across
     * sessions, making it easy to compare a working vs. failing run.</p>
     */
    public static void log(String message) {
        String line = "[" + LocalDateTime.now().format(LOG_TS) + "] " + message;
        System.err.println(line);

        Path dest = logFile;
        if (dest == null || !loggingEnabled()) return;

        try {
            rotateIfOversized(dest);
            Files.writeString(dest, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // If we can't write the log, there's nothing sensible to do -
            // we're already inside the logging path.
        }
    }

    /**
     * Whether to write the diagnostic file at all.
     *
     * <p>The log is on by default and that is the right default - under the
     * jpackage launcher there is no console, so without the file there is no
     * way to tell a locked font file from a blocked PowerShell. But it is
     * permanent instrumentation shipped to every user, and a user who does not
     * want a record of every font they install kept on disk needs a way to say
     * so. Set {@code LIPI_LOG=off}, or run with {@code -Dlipi.log=off}.</p>
     */
    private static boolean loggingEnabled() {
        String setting = System.getProperty("lipi.log");
        if (setting == null) setting = System.getenv("LIPI_LOG");
        if (setting == null) return true;
        setting = setting.trim();
        return !(setting.equalsIgnoreCase("off")
                || setting.equalsIgnoreCase("false")
                || setting.equals("0"));
    }

    /**
     * Keeps the log to one previous file and one current one.
     *
     * <p>Without this the file grows for the life of the installation: a
     * refresh of a 300-font library writes a line per font, so the log is
     * measured in hundreds of lines per launch and nothing ever removes any of
     * it. Two files bounded at {@value #LOG_MAX_BYTES} bytes each keeps enough
     * history to compare a working run with a failing one, which is what the
     * log is for, without the size being unbounded.</p>
     */
    private static void rotateIfOversized(Path dest) throws IOException {
        if (!Files.exists(dest) || Files.size(dest) < LOG_MAX_BYTES) return;
        Path previous = dest.resolveSibling(dest.getFileName() + ".1");
        Files.deleteIfExists(previous);
        Files.move(dest, previous);
    }

    /** Size at which the log is rotated: one megabyte, roughly a few thousand launches. */
    private static final long LOG_MAX_BYTES = 1_048_576L;
}
