package com.lipi.service;

import com.lipi.model.FontInfo;
import com.lipi.util.SystemUtil;
import com.lipi.util.WindowsFontRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Service class for font-related operations (installation, removal, checking).
 */
public class FontService {

    private final String fontsDirectory;

    public FontService(String fontsDirectory) {
        this.fontsDirectory = fontsDirectory;
    }

    /**
     * Loads all font files from the fonts directory.
     * If the directory does not exist it is created so the app starts cleanly
     * on a fresh install or after a first-time "mvn clean package".
     */
    public List<FontInfo> loadFontsFromDirectory() throws IOException {
        File fontsDir = new File(fontsDirectory);

        if (!fontsDir.exists()) {
            fontsDir.mkdirs();   // create it; no fonts yet, but no error either
        }

        if (!fontsDir.isDirectory()) {
            throw new IOException("Fonts path exists but is not a directory: " + fontsDirectory);
        }

        File[] fontFiles = fontsDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".ttf") || name.toLowerCase().endsWith(".otf"));

        if (fontFiles == null || fontFiles.length == 0) {
            return new ArrayList<>();
        }

        // Read both registry hives exactly once for the whole scan, instead of
        // once (or twice) per font. Per-font registry queries here are what
        // turned startup into a 1-2 minute wait once the install/remove fix
        // started consulting the registry for every font's real path - see
        // WindowsFontRegistry.queryAllRegisteredFonts() for the full story.
        java.util.Map<String, Boolean> registrySnapshot = SystemUtil.isWindows()
                ? WindowsFontRegistry.queryAllRegisteredFonts()
                : java.util.Collections.emptyMap();
        java.util.Set<String> pendingDeletes = SystemUtil.isWindows()
                ? WindowsFontRegistry.loadPendingDeletes()
                : java.util.Collections.emptySet();

        List<FontInfo> fonts = new ArrayList<>();
        for (File fontFile : fontFiles) {
            boolean installed = isFontInstalled(fontFile, registrySnapshot, pendingDeletes);
            fonts.add(new FontInfo(fontFile.getName(), fontFile.getAbsolutePath(), installed));
        }

        return fonts;
    }

    /**
     * Checks if a font is installed in the system.
     */
    public boolean isFontInstalled(File fontFile) {
        java.util.Map<String, Boolean> snapshot = SystemUtil.isWindows()
                ? WindowsFontRegistry.queryAllRegisteredFonts()
                : java.util.Collections.emptyMap();
        java.util.Set<String> pendingDeletes = SystemUtil.isWindows()
                ? WindowsFontRegistry.loadPendingDeletes()
                : java.util.Collections.emptySet();
        return isFontInstalled(fontFile, snapshot, pendingDeletes);
    }

    /**
     * Same check as {@link #isFontInstalled(File)}, but takes a pre-fetched
     * registry snapshot (see {@link WindowsFontRegistry#queryAllRegisteredFonts()})
     * and the set of paths pending a reboot-time delete, so a directory scan
     * over many fonts doesn't re-query the registry for each one.
     *
     * <p>The {@code pendingDeletes} check matters because of a real-world
     * sequence: a system-locked font's registry entry gets removed during
     * removal (so Explorer/Settings correctly show it gone), but the file
     * bytes survive on disk until next reboot (the {@code MoveFileEx}
     * fallback - see {@link #removeFont(File)}).
     * Without this check, the very next directory scan would see "not in
     * registry" + "file still exists on disk" and conclude the font is
     * installed again - exactly the "removed font still shows as installed
     * in the app" symptom, even though Explorer agrees it's gone.</p>
     */
    public boolean isFontInstalled(File fontFile, java.util.Map<String, Boolean> registrySnapshot,
                                    java.util.Set<String> pendingDeletes) {
        try {
            Path targetPath = SystemUtil.getSystemFontPath(fontFile, registrySnapshot);
            if (pendingDeletes.contains(targetPath.toString().toLowerCase())) {
                return false;
            }
            return Files.exists(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Installs a font to the system.
     *
     * <p>Install sequence on Windows:
     * <ol>
     *   <li><b>Cancel any queued reboot-time deletion.</b> If this font was
     *       removed earlier in the session via the {@code MoveFileEx}
     *       fallback, Windows still has its path queued in
     *       {@code PendingFileRenameOperations}. Left in place, that queued
     *       deletion would silently destroy the font we are about to install
     *       at the next restart. See
     *       {@link WindowsFontRegistry#cancelScheduledDelete(File)}.</li>
     *   <li><b>Elevation check</b>, mirroring {@link #removeFont(File)}:
     *       writing into {@code C:\Windows\Fonts} needs Administrator, the
     *       per-user store does not. Checked up front so the failure is
     *       reported as what it is rather than as a generic error.</li>
     *   <li><b>Copy, but only if the bytes differ.</b> See
     *       {@link #contentMatches(Path, Path)} - this is what makes a
     *       remove-then-reinstall work without a reboot.</li>
     *   <li>Register the font so Windows actually recognises it.</li>
     * </ol>
     */
    public InstallOutcome installFont(File fontFile) {
        try {
            Path sourcePath = fontFile.toPath();
            Path targetPath = SystemUtil.getSystemFontPath(fontFile);

            if (SystemUtil.isWindows()) {
                // Step 1: elevation check, before anything that needs elevation.
                // Cancelling a queued deletion rewrites a key under HKLM, so it
                // would fail for an unelevated user anyway - and failing it
                // quietly is the dangerous case, since the queued deletion
                // would then survive and destroy the font at the next restart.
                if (isSystemFontPath(targetPath) && !com.lipi.util.AdminUtil.isRunningAsAdmin()) {
                    WindowsFontRegistry.log("installFont: system font requires elevation, " +
                            "not running as admin: " + targetPath);
                    return InstallOutcome.NEEDS_ELEVATION;
                }

                // Step 2: undo any deletion Windows still has queued for this
                // path, then drop our own bookkeeping entry for it.
                boolean wasQueued = WindowsFontRegistry.loadPendingDeletes()
                        .contains(targetPath.toString().toLowerCase());
                if (wasQueued) {
                    WindowsFontRegistry.log("installFont: " + fontFile.getName() +
                            " has a reboot deletion queued at " + targetPath +
                            " - cancelling it before reinstalling");
                    if (!WindowsFontRegistry.cancelScheduledDelete(targetPath.toFile())) {
                        // Installing on top of a deletion we could not cancel
                        // would look like it worked and then silently undo
                        // itself at the next restart. Refuse instead.
                        WindowsFontRegistry.log("installFont: could not cancel the queued deletion "
                                + "for " + targetPath + " - aborting install to avoid a font that "
                                + "disappears at the next restart");
                        return InstallOutcome.PENDING_DELETE_STUCK;
                    }
                }
                WindowsFontRegistry.clearPendingDelete(targetPath.toFile());
            }

            // Step 3: write the file, unless an identical one is already there.
            boolean reusedExistingFile = false;
            if (contentMatches(sourcePath, targetPath)) {
                WindowsFontRegistry.log("installFont: " + fontFile.getName() +
                        " already present at " + targetPath +
                        " with identical content - skipping copy, re-registering only");
                reusedExistingFile = true;
            } else {
                Files.createDirectories(targetPath.getParent());
                try {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AccessDeniedException e) {
                    // Genuine permissions failure.
                    WindowsFontRegistry.log("installFont: AccessDeniedException for " +
                            fontFile.getName() + ": " + e.getMessage());
                    return InstallOutcome.NEEDS_ELEVATION;
                } catch (IOException e) {
                    // ERROR_SHARING_VIOLATION: Font Driver Host still holds the
                    // old file open and the replacement bytes differ, so there
                    // is nothing we can do until the lock is released.
                    WindowsFontRegistry.log("installFont: could not overwrite locked file " +
                            targetPath + " [" + e.getClass().getSimpleName() + "]: " + e.getMessage());
                    return InstallOutcome.LOCKED;
                }
            }

            // Step 4: on Windows, copying the file alone is not enough: the
            // Fonts folder shown in Explorer/Settings is backed by the
            // registry, not a plain directory listing, so without this step
            // the copy can succeed (no exception, no error) while the font
            // still appears completely absent everywhere.
            if (SystemUtil.isWindows()) {
                WindowsFontRegistry.register(targetPath.toFile());
            }

            SystemUtil.refreshFontCache();

            return reusedExistingFile ? InstallOutcome.REINSTATED : InstallOutcome.SUCCESS;

        } catch (Exception e) {
            WindowsFontRegistry.log("installFont: exception for " +
                    fontFile.getName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return InstallOutcome.FAILED;
        }
    }

    /**
     * Returns true if {@code target} already exists and holds exactly the same
     * bytes as {@code source}, meaning the copy would be a no-op.
     *
     * <p>This is the key to remove-then-reinstall inside one session. After a
     * {@link RemoveResult#PENDING_REBOOT} removal the font file is still
     * physically on disk and still locked by Font Driver Host - so
     * {@code Files.copy} fails with a sharing violation. But the bytes sitting
     * at the target are the same bytes we were about to write, because both
     * copies came from this same source file. There is nothing to write, and
     * re-adding the registry entry is enough to make the font installed again.
     * Attempting the copy anyway is what produced the bogus "make sure you
     * have administrator privileges" error even when running elevated.</p>
     *
     * <p>Reading a locked font file is fine - Font Driver Host opens fonts
     * with read sharing - but if the read does fail for any reason we report
     * a mismatch so the caller falls through to a real copy attempt rather
     * than wrongly skipping it.</p>
     */
    private static boolean contentMatches(Path source, Path target) {
        try {
            if (!Files.exists(target)) return false;
            if (Files.size(source) != Files.size(target)) return false;
            return java.util.Arrays.equals(digestOf(source), digestOf(target));
        } catch (Exception e) {
            WindowsFontRegistry.log("contentMatches: could not compare " + source +
                    " with " + target + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static byte[] digestOf(Path path) throws IOException, java.security.NoSuchAlgorithmException {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /**
     * Outcome of a single font-installation attempt. Replaces the old boolean
     * return, which forced every failure - locked file, missing elevation,
     * unreadable source - through the same "make sure you have administrator
     * privileges" message regardless of the actual cause.
     */
    public enum InstallOutcome {
        /** Font file was copied into place and registered. */
        SUCCESS,
        /**
         * An identical file was already on disk (typically because it was
         * removed earlier in this session and is awaiting a reboot delete);
         * the queued deletion was cancelled and the font re-registered.
         */
        REINSTATED,
        /**
         * Target is a system font path and the process is not elevated.
         * The caller should prompt the user to restart as Administrator.
         */
        NEEDS_ELEVATION,
        /**
         * The existing file is locked by Font Driver Host and its contents
         * differ from the font being installed, so it cannot be overwritten
         * until Windows releases it at the next restart.
         */
        LOCKED,
        /**
         * A reboot-time deletion is queued for this path and could not be
         * cancelled, so installing now would produce a font that vanishes at
         * the next restart. Nothing was written.
         */
        PENDING_DELETE_STUCK,
        /** Installation failed for another reason; see the log for details. */
        FAILED
    }

    /**
     * Removes a font from the system.
     *
     * <p>Removal sequence on Windows:
     * <ol>
     *   <li><b>Elevation check (Fix 3)</b>: System fonts live in
     *       {@code C:\Windows\Fonts} and require Administrator. Per-user fonts
     *       live in {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts} and do not.
     *       If the target is a system font and the process is not elevated we
     *       return {@code false} immediately with a clear log entry rather than
     *       attempting operations that will fail silently anyway.</li>
     *   <li>Unregister the font (remove registry entry + call RemoveFontResourceW
     *       to release any GDI session lock on the file).</li>
     *   <li>Delete the file with {@code Files.delete()}.</li>
     *   <li>If the delete fails because the file is locked — fall back to
     *       {@code MoveFileEx(MOVEFILE_DELAY_UNTIL_REBOOT)}, the same mechanism
     *       MSI installers use. The font is already gone from the registry at
     *       this point so it is effectively uninstalled; only the bytes on disk
     *       remain until the next reboot.</li>
     *   <li><b>Graceful fallback (Fix 4)</b>: If even the reboot-schedule fails,
     *       the method returns {@code false} so the caller can surface a precise
     *       message ("font will be removed on next restart") instead of the
     *       generic "removal failed" that the old code produced.</li>
     * </ol>
     *
     * <p><b>Why we catch {@code IOException}, not just {@code AccessDeniedException}:</b>
     * The JDK maps two distinct Win32 errors to two different exception types:
     * <ul>
     *   <li>{@code ERROR_ACCESS_DENIED} (5) → {@code AccessDeniedException}
     *       — true permissions failure (not running as Administrator).</li>
     *   <li>{@code ERROR_SHARING_VIOLATION} (32) → plain {@code FileSystemException}
     *       — the file is open by another process (Font Driver Host,
     *       {@code fontdrvhost.exe}). This is the common case on Windows 10/11
     *       and it is NOT a subtype of {@code AccessDeniedException}.</li>
     * </ul>
     * Catching only {@code AccessDeniedException} silently misses the sharing-
     * violation case, so {@code scheduleDeleteOnReboot} is never reached and
     * removal always fails with a misleading "access denied" message even though
     * the real cause is a file lock. We therefore catch {@code IOException}
     * (the common supertype of both) and invoke the reboot fallback for any
     * IO failure from {@code Files.delete()}, logging the exact exception type
     * and message so the two cases remain distinguishable in the log.</p>
     */
    public RemoveResult removeFont(File fontFile) {
        try {
            Path targetPath = SystemUtil.getSystemFontPath(fontFile);
            WindowsFontRegistry.log("removeFont: target=" + targetPath +
                    "  exists=" + java.nio.file.Files.exists(targetPath));

            if (!java.nio.file.Files.exists(targetPath)) {
                WindowsFontRegistry.log("removeFont: target does not exist, nothing to do: " + targetPath);
                return RemoveResult.NOT_FOUND;
            }

            // Fix 3: Check elevation before touching system fonts.
            // System fonts (C:\Windows\Fonts) require Administrator. Per-user fonts
            // (%LOCALAPPDATA%\Microsoft\Windows\Fonts) do not. Attempting to delete a
            // system font without elevation produces AccessDeniedException or a silent
            // MoveFileEx failure. We catch this up-front and return a distinct result
            // so the UI can show an actionable "restart as Administrator" message.
            if (SystemUtil.isWindows()) {
                boolean isSystemFont = isSystemFontPath(targetPath);
                if (isSystemFont && !com.lipi.util.AdminUtil.isRunningAsAdmin()) {
                    WindowsFontRegistry.log("removeFont: system font requires elevation, not running as admin: "
                            + targetPath);
                    return RemoveResult.NEEDS_ELEVATION;
                }
            }

            if (SystemUtil.isWindows()) {
                // Removes the registry entry (so the font disappears from
                // Explorer/Settings/other apps right away) and tries to
                // release the OS's lock on the file via RemoveFontResourceW.
                WindowsFontRegistry.unregister(targetPath.toFile());
            }

            try {
                java.nio.file.Files.delete(targetPath);
                WindowsFontRegistry.log("removeFont: Files.delete() succeeded for " + targetPath);
            } catch (IOException deleteFailed) {
                // Log the exact exception type and message so the log distinguishes:
                //   AccessDeniedException  → "Access is denied" (permissions problem)
                //   FileSystemException    → "file is being used by another process"
                //                           (Font Driver Host / fontdrvhost.exe lock)
                WindowsFontRegistry.log("removeFont: Files.delete() threw " +
                        deleteFailed.getClass().getSimpleName() + ": " + deleteFailed.getMessage());

                if (SystemUtil.isWindows()) {
                    boolean scheduled = WindowsFontRegistry.scheduleDeleteOnReboot(targetPath.toFile());
                    WindowsFontRegistry.log("removeFont: scheduleDeleteOnReboot returned " + scheduled);

                    // Fix 4: Return a distinct result so the UI can tell the user
                    // "the font will be fully removed on next restart" rather than
                    // silently failing or showing a generic error.
                    if (scheduled) {
                        WindowsFontRegistry.log("removeFont: font scheduled for deletion on reboot: "
                                + targetPath);
                        WindowsFontRegistry.recordPendingDelete(targetPath.toFile());
                        SystemUtil.refreshFontCache();
                        return RemoveResult.PENDING_REBOOT;
                    } else {
                        WindowsFontRegistry.log("removeFont: failed for " + fontFile.getName()
                                + " - could not delete or schedule reboot deletion");
                        return RemoveResult.FAILED;
                    }
                } else {
                    throw deleteFailed;
                }
            }

            SystemUtil.refreshFontCache();
            return RemoveResult.SUCCESS;

        } catch (IOException e) {
            WindowsFontRegistry.log("removeFont: failed for " +
                    fontFile.getName() + " [" + e.getClass().getSimpleName() + "]: " + e.getMessage());
            return RemoveResult.FAILED;
        } catch (Exception e) {
            WindowsFontRegistry.log("removeFont: unexpected exception for " +
                    fontFile.getName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return RemoveResult.FAILED;
        }
    }

    /**
     * Returns true if {@code path} is under the Windows system fonts directory
     * ({@code %WINDIR%\Fonts}), which requires Administrator to modify.
     */
    private static boolean isSystemFontPath(Path path) {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) windir = "C:\\Windows";
        String systemFonts = java.nio.file.Paths.get(windir, "Fonts")
                .toString().toLowerCase();
        return path.toString().toLowerCase().startsWith(systemFonts);
    }

    /**
     * Outcome of a single font-removal attempt.
     */
    public enum RemoveResult {
        /** File deleted successfully. */
        SUCCESS,
        /** Font was not installed; nothing to do. */
        NOT_FOUND,
        /**
         * Font is a system font and the process is not running as Administrator.
         * The caller should prompt the user to restart elevated.
         */
        NEEDS_ELEVATION,
        /**
         * File was locked by Font Driver Host; registry entry is already removed.
         * The file will be deleted on next Windows restart via PendingFileRenameOperations.
         */
        PENDING_REBOOT,
        /** Removal failed for another reason; see the log for details. */
        FAILED
    }

    /**
     * Installs multiple fonts.
     */
    public InstallResult installFonts(List<FontInfo> fonts) {
        int success        = 0;
        int failed         = 0;
        int locked         = 0;
        int needsElevation = 0;

        for (FontInfo font : fonts) {
            try {
                InstallOutcome outcome = installFont(new File(font.getFilePath()));
                switch (outcome) {
                    case SUCCESS, REINSTATED -> { font.setInstalled(true); success++; }
                    case NEEDS_ELEVATION     -> needsElevation++;
                    case LOCKED, PENDING_DELETE_STUCK -> locked++;
                    default                  -> failed++;
                }
            } catch (Exception e) {
                failed++;
            }
        }

        return new InstallResult(success, failed, locked, needsElevation);
    }

    /**
     * Removes multiple fonts.
     */
    public RemoveBatchResult removeFonts(List<FontInfo> fonts) {
        int success       = 0;
        int failed        = 0;
        int pendingReboot = 0;
        int needsElevation = 0;

        for (FontInfo font : fonts) {
            try {
                RemoveResult result = removeFont(new File(font.getFilePath()));
                switch (result) {
                    case SUCCESS -> { font.setInstalled(false); success++; }
                    case PENDING_REBOOT -> { font.setInstalled(false); pendingReboot++; }
                    case NEEDS_ELEVATION -> needsElevation++;
                    // NOT_FOUND is not a failure. The font is not installed,
                    // which is the state the user asked for, so there is
                    // nothing to do and nothing to report. Counting it as a
                    // failure - which is what the default arm did - meant that
                    // selecting the whole library and clicking Remove produced
                    // "Failed to remove 297 font(s). See lipi-debug.log for
                    // details." for a removal in which nothing went wrong.
                    // The row is corrected on the way past, because a row
                    // saying Installed for a font that is not is the reason
                    // the user pressed the button.
                    case NOT_FOUND -> font.setInstalled(false);
                    case FAILED -> failed++;
                }
            } catch (Exception e) {
                failed++;
            }
        }

        return new RemoveBatchResult(success, failed, pendingReboot, needsElevation);
    }

    /**
     * Result of a batch remove operation.
     */
    public static class RemoveBatchResult {
        private final int successCount;
        private final int failedCount;
        private final int pendingRebootCount;
        private final int needsElevationCount;

        public RemoveBatchResult(int successCount, int failedCount,
                                 int pendingRebootCount, int needsElevationCount) {
            this.successCount       = successCount;
            this.failedCount        = failedCount;
            this.pendingRebootCount = pendingRebootCount;
            this.needsElevationCount = needsElevationCount;
        }

        public int getSuccessCount()        { return successCount; }
        public int getFailedCount()         { return failedCount; }
        public int getPendingRebootCount()  { return pendingRebootCount; }
        public int getNeedsElevationCount() { return needsElevationCount; }

        /** Total fonts that were effectively removed (immediately or pending reboot). */
        public int getEffectivelyRemovedCount() { return successCount + pendingRebootCount; }
    }

    /**
     * Result of batch install/remove operations.
     */
    public static class InstallResult {
        private final int successCount;
        private final int failedCount;
        private final int lockedCount;
        private final int needsElevationCount;

        public InstallResult(int successCount, int failedCount,
                             int lockedCount, int needsElevationCount) {
            this.successCount        = successCount;
            this.failedCount         = failedCount;
            this.lockedCount         = lockedCount;
            this.needsElevationCount = needsElevationCount;
        }

        public int getSuccessCount()        { return successCount; }
        public int getFailedCount()         { return failedCount; }
        public int getLockedCount()         { return lockedCount; }
        public int getNeedsElevationCount() { return needsElevationCount; }
    }
}
