package com.lipi;

import com.lipi.ui.MainWindow;
import com.lipi.util.AdminUtil;
import com.lipi.util.AlertUtil;
import com.lipi.util.WindowsFontRegistry;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;

/**
 * Main application entry point for Lipi Font Manager.
 * JavaFX Application replaced with a plain main() that bootstraps Swing on the EDT.
 */
public class Lipi {

    public static void main(String[] args) {
        // Point WindowsFontRegistry at a persistent log file so diagnostic
        // output from font install/remove operations survives even when the
        // app is launched via the jpackage native launcher or javaw (no
        // console attached). Without this, every System.err line written by
        // WindowsFontRegistry and FontService is silently discarded, making
        // it impossible to tell which step in the removal sequence is failing.
        //
        // IMPORTANT: the log file must NOT go in getAppDir() / the install
        // directory. On a jpackage installer build the app lands under
        // C:\Program Files\Lipi\, which is read-only for all users including
        // Administrators (writes there require a separate, file-system-level
        // elevation that UAC does not grant automatically). Writing there
        // would silently fail and we'd still get no log - the exact problem
        // we're trying to fix.
        //
        // %APPDATA%\Lipi\ (C:\Users\<user>\AppData\Roaming\Lipi\) is the
        // correct Windows location for per-user app data: always writable,
        // persists across sessions, and survives UAC elevation (the elevated
        // process inherits the same %APPDATA% as the launching user).
        Path logFile = resolveLogFile();
        WindowsFontRegistry.setLogFile(logFile);
        WindowsFontRegistry.setPendingDeleteFile(logFile.resolveSibling("lipi-pending-delete.txt"));

        // Ask for elevation before any window exists.
        //
        // An installed Lipi cannot do anything useful without it - both of its
        // actions, installing and removing fonts, need Administrator rights -
        // and the old "Restart as Admin / Continue Anyway" dialog only asked
        // the user to confirm that they wanted to see the prompt Windows was
        // about to show them anyway. That is two decisions for one intent.
        // Windows' own prompt is already a yes/no with a clear "No", and
        // declining it now leaves the app running unelevated, so there is
        // nothing to lose by going straight to it.
        //
        // The dialog is still the right thing for a JAR or IDE run: there,
        // elevation goes through a different relaunch mechanism that a
        // developer may well not want, and "Continue Anyway" is a normal
        // choice rather than a speed bump. See showAdminWarning().
        String elevationError = null;
        if (!AdminUtil.isRunningAsAdmin() && AdminUtil.isPackagedApp()) {
            try {
                switch (AdminUtil.requestElevation()) {
                    case RELAUNCHED -> {
                        // The elevated instance is on its way; this one has
                        // nothing left to do.
                        System.exit(0);
                        return;
                    }
                    case DECLINED -> {
                        // The user said no to the OS prompt. Taking that answer
                        // at face value is the point - re-asking in a dialog of
                        // our own would be exactly the extra click this flow
                        // exists to remove. The main window's status bar already
                        // warns that install/remove will fail.
                    }
                    case FAILED -> elevationError =
                            "The operating system did not accept the elevation request.";
                }
            } catch (Exception e) {
                elevationError = e.getMessage() != null ? e.getMessage() : e.toString();
            }
        }

        // Use the system look-and-feel for a native appearance
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Falls back to the default cross-platform L&F
        }

        final String startupElevationError = elevationError;
        SwingUtilities.invokeLater(() -> {
            if (!AdminUtil.isRunningAsAdmin()) {
                if (startupElevationError != null) {
                    // Elevation could not even be attempted, so there is no OS
                    // prompt coming and the user has to be told something.
                    AlertUtil.showError("Administrator Privileges Required",
                            "Lipi could not request Administrator privileges automatically.\n\n"
                            + "Error: " + startupElevationError + "\n\n"
                            + "Close Lipi and start it again with right-click \u2192 "
                            + "Run as administrator.\n\n"
                            + "You can continue, but installing/removing fonts will fail.");
                } else if (!AdminUtil.isPackagedApp()) {
                    // JAR or IDE run: this is a developer flow, where being
                    // asked before a UAC prompt appears is genuinely useful.
                    showAdminWarning();
                }
            }

            MainWindow window = new MainWindow(getAppDir());
            window.buildAndShow();
        });
    }

    /**
     * Returns a writable path for the diagnostic log file.
     *
     * <p>Priority:
     * <ol>
     *   <li><b>Windows</b>: {@code %APPDATA%\Lipi\lipi-debug.log}.
     *       {@code %APPDATA%} resolves to {@code C:\Users\<user>\AppData\Roaming},
     *       which is writable for the current user in all launch modes (normal,
     *       "Run as Administrator", jpackage native launcher). The install
     *       directory ({@code C:\Program Files\Lipi\}) is intentionally avoided
     *       here: it is read-only without a separate file-system elevation that
     *       UAC does not grant automatically, so writing there silently fails.</li>
     *   <li><b>macOS / Linux</b>: falls back to {@code getAppDir()/lipi-debug.log}
     *       since those platforms don't have the same Program Files restriction.</li>
     *   <li><b>Fallback</b>: if {@code %APPDATA%} is unset (e.g. running as
     *       SYSTEM, in CI, or on a non-Windows OS), uses {@code getAppDir()}
     *       so logging degrades gracefully rather than crashing startup.</li>
     * </ol>
     */
    private static Path resolveLogFile() {
        if (com.lipi.util.SystemUtil.isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                File logDir = new File(appData, "Lipi");
                logDir.mkdirs();   // no-op if already exists; safe to call every startup
                return new File(logDir, "lipi-debug.log").toPath();
            }
        }
        // macOS, Linux, or %APPDATA% not set: fall back to app directory
        return new File(getAppDir(), "lipi-debug.log").toPath();
    }

    /**
     * Returns the directory containing the running JAR (or the working directory
     * when launching from an IDE).
     *
     * Three launch modes are handled:
     *   1. java -jar lipi-1.0.0.jar   → parent directory of the JAR
     *   2. jpackage native launcher            → reads the app.dir system property
     *   3. IDE / exploded classes              → falls back to the working directory
     */
    public static File getAppDir() {
        String appDirProp = System.getProperty("app.dir");
        if (appDirProp != null && !appDirProp.isBlank()) {
            return new File(appDirProp);
        }

        try {
            File codeSource = new File(Lipi.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return codeSource.isFile() ? codeSource.getParentFile() : codeSource;
        } catch (Exception e) {
            return new File(".");
        }
    }

    // ── Admin warning ─────────────────────────────────────────────────────

    private static void showAdminWarning() {
        boolean canRestart = isRunningFromJar();

        String message = AdminUtil.getAdminRequiredMessage();
        if (!canRestart) {
            message += "\n\nRunning from IDE/classes directory.\n" +
                    "Build a JAR file first (mvn clean package) to enable admin restart.";
        }
        message += "\n\nYou can continue, but installing/removing fonts will fail.";

        if (canRestart) {
            Object[] options = {"Restart as Admin", "Continue Anyway"};
            int choice = JOptionPane.showOptionDialog(
                    null, message, "Administrator Privileges Required",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[1]);

            if (choice == JOptionPane.YES_OPTION) {
                try {
                    AdminUtil.ElevationResult result = AdminUtil.requestElevation();
                    if (result == AdminUtil.ElevationResult.RELAUNCHED) {
                        // requestElevation() waited for the user to answer the
                        // OS prompt, so an elevated instance really is on its
                        // way and it is safe to exit now.
                        System.exit(0);
                    } else if (result == AdminUtil.ElevationResult.FAILED) {
                        AlertUtil.showError("Restart Failed",
                                "Could not locate the JAR file to restart.\n\n" +
                                "Please restart manually from an elevated command prompt:\n" +
                                "  java -jar target\\lipi-1.0.0.jar");
                    }
                    // DECLINED: the user dismissed the OS prompt after asking
                    // for it. Carry on unelevated rather than exiting into
                    // nothing, which is what the old boolean return caused.
                } catch (Exception e) {
                    AlertUtil.showError("Restart Failed",
                            "Could not restart with administrator privileges.\n\n" +
                            "Error: " + e.getMessage() + "\n\n" +
                            "Please restart manually from an elevated command prompt:\n" +
                            "  java -jar target\\lipi-1.0.0.jar");
                }
            }
        } else {
            JOptionPane.showMessageDialog(null, message,
                    "Administrator Privileges Required", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static boolean isRunningFromJar() {
        try {
            String path = new File(Lipi.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();
            return path.toLowerCase().endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }
}
