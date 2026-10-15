package com.lipi.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for system-specific operations.
 */
public class SystemUtil {

    private SystemUtil() {}

    private static final String OS = System.getProperty("os.name").toLowerCase();

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMac()     { return OS.contains("mac"); }
    public static boolean isLinux()   { return OS.contains("nix") || OS.contains("nux") || OS.contains("aix"); }
    public static String  getOSName() { return OS; }

    /**
     * Opens the system fonts folder in the file explorer.
     * Uses the array form of exec() to avoid word-splitting on paths with spaces.
     */
    public static void openSystemFontsFolder() throws IOException {
        if (isWindows()) {
            String fontPath = System.getenv("WINDIR") + "\\Fonts";
            Runtime.getRuntime().exec(new String[]{"explorer.exe", fontPath});
        } else if (isMac()) {
            // The folder that is opened has to be the folder fonts are
            // installed into, or the menu item shows the user an empty
            // directory and invites them to conclude nothing was installed.
            // See getSystemFontPath: that is ~/Library/Fonts.
            Path fontsDir = userFontsDirectory();
            Files.createDirectories(fontsDir);
            Runtime.getRuntime().exec(new String[]{"open", fontsDir.toString()});
        } else {
            String fontPath = System.getProperty("user.home") + "/.local/share/fonts";
            File fontsDir = new File(fontPath);
            if (!fontsDir.exists()) fontsDir.mkdirs();
            Runtime.getRuntime().exec(new String[]{"xdg-open", fontPath});
        }
    }

    /**
     * Returns the real filesystem path where {@code fontFile} is installed,
     * or the preferred install target path if it is not yet installed.
     *
     * <h3>Why file-existence checks are not reliable on Windows 10/11</h3>
     * <p>{@code C:\Windows\Fonts} is a virtual shell namespace folder. Windows
     * creates compatibility symlinks/junctions inside it that make per-user
     * fonts (physically stored in
     * {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts\}) appear to exist at the
     * system path. {@code Files.exists()} follows those links and returns
     * {@code true} for both paths, so existence alone cannot distinguish the
     * real store. Raw Win32 APIs ({@code MoveFileEx}, {@code DeleteFile},
     * {@code RemoveFontResourceW}) operate on real filesystem paths and do NOT
     * follow the shell namespace junction, so passing the system path for a
     * per-user font causes {@code ERROR_PATH_NOT_FOUND} (3) even when the
     * font appears installed.</p>
     *
     * <h3>Resolution strategy</h3>
     * <ol>
     *   <li>Query the registry to find which hive ({@code HKLM} vs {@code HKCU})
     *       holds the font entry. The hive is the authoritative indicator of
     *       which physical store owns the font:
     *       <ul>
     *         <li>{@code HKCU} entry → per-user store →
     *             {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts\<name>}</li>
     *         <li>{@code HKLM} entry → system store →
     *             {@code %WINDIR%\Fonts\<name>}</li>
     *       </ul>
     *   </li>
     *   <li>If the font is not in the registry at all (not yet installed, or
     *       installed by a third-party tool that skipped the registry), fall
     *       back to whichever physical path actually exists on disk. If neither
     *       exists, choose the install target based on elevation: system path
     *       when running as Administrator, per-user path otherwise.</li>
     * </ol>
     */
    public static Path getSystemFontPath(File fontFile) throws IOException {
        return getSystemFontPath(fontFile, null);
    }

    /**
     * Same as {@link #getSystemFontPath(File)}, but if {@code registrySnapshot}
     * is non-null, resolves the registry-hive question from it instead of
     * spawning {@code reg query} subprocesses. Pass the result of
     * {@link WindowsFontRegistry#queryAllRegisteredFonts()}, computed once,
     * when resolving paths for many fonts in a row (e.g. the startup
     * directory scan) - see that method's docs for why this matters for
     * startup time.
     */
    public static Path getSystemFontPath(File fontFile, java.util.Map<String, Boolean> registrySnapshot) throws IOException {
        String fontFileName = fontFile.getName();

        if (isWindows()) {
            Path systemPath  = Paths.get(System.getenv("WINDIR"), "Fonts", fontFileName);
            Path perUserPath = perUserFontPath(fontFileName);

            // ── Step 1: trust the registry hive over file existence ───────
            // The hive tells us which physical store owns the font. File
            // existence is unreliable because the shell namespace creates
            // compatibility links that make per-user fonts appear at the
            // system path too.
            Boolean inHkcu = WindowsFontRegistry.isRegisteredInHkcu(fontFileName, registrySnapshot);
            if (inHkcu != null) {
                // Font is registered: hive determines the real path.
                Path registryPath = inHkcu ? perUserPath : systemPath;
                WindowsFontRegistry.log("getSystemFontPath: " + fontFileName +
                        " registered in " + (inHkcu ? "HKCU" : "HKLM") +
                        " -> " + registryPath);
                return registryPath;
            }

            // ── Step 2: not in registry — fall back to physical existence ─
            // Check the per-user path first (more common on Windows 10/11
            // without admin rights), then system path.
            if (perUserPath != null && Files.exists(perUserPath)) {
                WindowsFontRegistry.log("getSystemFontPath: " + fontFileName +
                        " not in registry, found at per-user path " + perUserPath);
                return perUserPath;
            }
            if (Files.exists(systemPath)) {
                WindowsFontRegistry.log("getSystemFontPath: " + fontFileName +
                        " not in registry, found at system path " + systemPath);
                return systemPath;
            }

            // ── Step 3: not installed yet — pick install target ───────────
            if (perUserPath != null && !AdminUtil.isRunningAsAdmin()) {
                WindowsFontRegistry.log("getSystemFontPath: " + fontFileName +
                        " not installed, not admin -> per-user install target " + perUserPath);
                return perUserPath;
            }
            WindowsFontRegistry.log("getSystemFontPath: " + fontFileName +
                    " not installed, admin -> system install target " + systemPath);
            return systemPath;

        } else if (isMac()) {
            // Same shape as the Windows resolution above, for the same reason.
            // /Library/Fonts is writable only by an administrator, while
            // ~/Library/Fonts needs no privileges at all, so targeting the
            // system directory unconditionally - as this did - meant every
            // install by an ordinary user failed. An existing installation in
            // either place still wins, so a font already installed
            // system-wide is removed from where it actually is.
            Path perUserPath = Paths.get(System.getProperty("user.home"), "Library", "Fonts", fontFileName);
            Path systemPath  = Paths.get("/Library/Fonts", fontFileName);
            if (Files.exists(perUserPath)) return perUserPath;
            if (Files.exists(systemPath))  return systemPath;
            return perUserPath;
        } else {
            return Paths.get(System.getProperty("user.home"), ".local", "share", "fonts", fontFileName);
        }
    }

    /** The directory this platform installs fonts into for the current user. */
    public static Path userFontsDirectory() {
        if (isWindows()) {
            Path perUser = perUserFontPath("x");
            return perUser != null ? perUser.getParent()
                    : Paths.get(System.getenv("WINDIR") == null ? "C:\\Windows" : System.getenv("WINDIR"), "Fonts");
        }
        if (isMac()) {
            return Paths.get(System.getProperty("user.home"), "Library", "Fonts");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "fonts");
    }

    /** Returns the per-user Windows font path, or null if {@code %LOCALAPPDATA%} is unset. */
    static Path perUserFontPath(String fontFileName) {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) return null;
        return Paths.get(localAppData, "Microsoft", "Windows", "Fonts", fontFileName);
    }

    /**
     * Refreshes the system font cache after a font is installed or removed.
     *
     * <p>On Linux this is not optional. Applications do not read the fonts
     * directory; they read fontconfig's cache of it, so a font that has just
     * been copied into place is invisible to everything until
     * {@code fc-cache} has run. That is the same failure the registry work on
     * Windows exists to prevent: the install reports success and the font does
     * not appear.</p>
     *
     * <p>The call is waited for rather than fired and forgotten. An install
     * that returns before the cache is rebuilt tells the user it is done while
     * the font is still missing from every font menu, which is the same lie
     * told a second later. The wait is bounded because {@code fc-cache} on a
     * large font library is slow and a hung one must not hang the app.</p>
     */
    public static void refreshFontCache() {
        try {
            if (isWindows()) {
                // Windows rebuilds its own font cache from the registry entry;
                // WM_FONTCHANGE is broadcast separately by WindowsFontRegistry.
                return;
            }

            String[] command = isMac()
                    ? new String[]{"atsutil", "databases", "-remove"}
                    // Naming the directory keeps the work proportional to what
                    // changed instead of rescanning every font path on the
                    // system, and -f forces it even when the directory's
                    // timestamp has not moved.
                    : new String[]{"fc-cache", "-f", userFontsDirectory().toString()};

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("Font cache refresh timed out: " + String.join(" ", command));
                return;
            }
            if (process.exitValue() != 0) {
                // Worth saying out loud: the font is on disk and the operation
                // "succeeded", but nothing will see it until the cache is
                // rebuilt by hand or the user logs out.
                System.err.println("Font cache refresh failed (exit " + process.exitValue()
                        + "): " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while refreshing the font cache");
        } catch (Exception e) {
            // A machine without fc-cache installed is a normal state, not an
            // error worth interrupting an install for.
            System.err.println("Failed to refresh font cache: " + e.getMessage());
        }
    }
}
