package com.lipi.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for checking and requesting administrator privileges.
 */
public class AdminUtil {

    private AdminUtil() {}

    /**
     * How long to wait for the OS elevation prompt before giving up on hearing
     * back. Generous on purpose - the user may be walking away from the screen
     * or typing an administrator password - because the only cost of waiting is
     * a splash-less startup, while cutting it short means guessing wrong about
     * whether an elevated instance is coming.
     */
    private static final int UAC_PROMPT_TIMEOUT_SECONDS = 300;

    /**
     * How long to give a Linux escalation helper to fail before assuming it
     * succeeded. See {@link #restartAsAdminLinux()} for why this one has to be
     * short rather than generous.
     */
    private static final int LINUX_AUTH_FAILFAST_SECONDS = 3;

    /**
     * Whether this process is elevated, worked out once.
     *
     * <p>A process cannot gain or lose elevation while it runs - that is what
     * {@link #requestElevation()} relaunches for - so the answer is fixed for
     * the lifetime of the JVM and caching it changes no behaviour.</p>
     *
     * <p>What it does change is how often the check runs.
     * {@code SystemUtil.getSystemFontPath()} asks this question for any font
     * it cannot find in the registry or on disk, which is every font in a
     * library that is not installed - so a refresh of a 303-font library asked
     * it 303 times. On Windows the check works by creating a file in
     * {@code C:\Windows\System32} and deleting it again. Unelevated that fails
     * immediately and costs almost nothing (measured at 38 ms for 303 calls),
     * so this is not the performance problem it looks like; but running
     * elevated it really does create and delete a file in System32 once per
     * font, every time the table is refreshed, and a font manager has no
     * business doing that.</p>
     */
    private static volatile Boolean elevated;

    public static boolean isRunningAsAdmin() {
        Boolean cached = elevated;
        if (cached != null) return cached;

        boolean result;
        if (SystemUtil.isWindows()) {
            result = isWindowsAdmin();
        } else if (SystemUtil.isMac() || SystemUtil.isLinux()) {
            result = isUnixRoot();
        } else {
            result = false;
        }
        elevated = result;
        return result;
    }

    private static boolean isWindowsAdmin() {
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) systemRoot = "C:\\Windows";
            Path testPath = Path.of(systemRoot, "System32", "lipi_admin_test.tmp");
            Files.write(testPath, "test".getBytes());
            Files.delete(testPath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isUnixRoot() {
        try {
            if ("root".equals(System.getProperty("user.name"))) return true;
            Path fontsPath = SystemUtil.getSystemFontPath(new File("test.ttf"));
            return Files.isWritable(fontsPath.getParent());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Outcome of asking the operating system for elevated privileges.
     *
     * <p>The three cases need different handling and used to be collapsed into
     * a single boolean, which made a declined UAC prompt indistinguishable from
     * a successful hand-off - so the app exited either way and the user was
     * left with nothing after clicking "No".</p>
     */
    public enum ElevationResult {
        /** An elevated instance was launched; the caller should now exit. */
        RELAUNCHED,
        /** The user dismissed the OS prompt. Keep running unelevated. */
        DECLINED,
        /** Elevation could not even be attempted (see the message on the exception path). */
        FAILED
    }

    /**
     * True when running from a jpackage-built installation (the {@code .exe} /
     * {@code .app} / {@code .deb} build) rather than a bare JAR or an IDE.
     *
     * <p>The native launcher sets {@code jpackage.app-path} to its own absolute
     * path, so this doubles as the check for whether we can relaunch ourselves.
     * It is what lets an installed Lipi go straight to the UAC prompt instead of
     * first asking the user, in its own dialog, whether they would like to be
     * asked by Windows.</p>
     */
    public static boolean isPackagedApp() {
        String appPath = System.getProperty("jpackage.app-path");
        return appPath != null && !appPath.isBlank() && new File(appPath).exists();
    }

    /**
     * Asks the operating system to relaunch Lipi with administrator/root
     * privileges and reports what the user did with the prompt.
     *
     * <p>This blocks while the prompt is on screen, which is the point: the
     * caller has to know whether an elevated instance is actually coming before
     * it decides to exit.</p>
     */
    public static ElevationResult requestElevation() throws IOException {
        if (!isPackagedApp() && !isRunningFromJar()) {
            throw new IOException(
                    "Cannot restart with admin privileges when running from IDE or classes directory. " +
                    "Please build a JAR file first using: mvn clean package");
        }

        if (SystemUtil.isWindows()) return restartAsAdminWindows();
        if (SystemUtil.isMac())     return restartAsAdminMac();
        if (SystemUtil.isLinux())   return restartAsAdminLinux();
        return ElevationResult.FAILED;
    }

    private static boolean isRunningFromJar() {
        String path = getJarPath();
        return path != null && path.toLowerCase().endsWith(".jar");
    }

    private static ElevationResult restartAsAdminWindows() throws IOException {
        // When running from a jpackage-built installer, the native launcher
        // (e.g. Lipi.exe) sets the "jpackage.app-path" system property to its
        // own absolute path. Prefer relaunching that executable directly:
        //
        //   1) It is guaranteed to exist (it's the running process's own file).
        //   2) jpackage's default jlink invocation (used whenever <runtimeImage>
        //      is not set, as in this project) bundles the runtime with
        //      --strip-native-commands, which removes java.exe/javaw.exe from
        //      the installed app's runtime/bin folder entirely. Looking for
        //      them there - as the old code did - fails with a Windows
        //      "the system cannot find the file specified" / "java could not
        //      be found in bin" error as soon as the app tries to restart
        //      itself elevated.
        //   3) The native launcher already knows its own main class/jar, so no
        //      "-jar ..." argument is needed.
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && new File(appPath).exists()) {
            return relaunchElevated(appPath, "", new File(appPath).getParent());
        }

        // Fallback: running via "java -jar lipi-1.0.0.jar" (dev / Quick
        // Start flow). Here java.home does point at a full-featured JDK/JRE that
        // does have javaw.exe, so the original approach is fine.
        String jarPath = getJarPath();
        if (jarPath == null) return ElevationResult.FAILED;

        String javaHome  = System.getProperty("java.home");
        File   javawFile = new File(javaHome, "bin\\javaw.exe");
        if (!javawFile.exists()) {
            // Fallback: try the plain java executable (shows a console window but works)
            javawFile = new File(javaHome, "bin\\java.exe");
        }
        if (!javawFile.exists()) {
            throw new IOException(
                    "Could not find javaw.exe or java.exe under: " + javaHome + "\\bin\n" +
                    "This Java runtime does not include a launcher executable.");
        }
        String javawPath = javawFile.getAbsolutePath();

        // Working directory = the folder that contains the JAR.
        // Passing this to ShellExecute ensures fonts/ and icons/ resolve
        // correctly in the elevated process even if its CWD is System32.
        String workDir = new File(jarPath).getParent();

        return relaunchElevated(javawPath, "-jar \"\"" + escapeForVbs(jarPath) + "\"\"", workDir);
    }

    /**
     * Builds and runs a tiny VBScript that calls Shell.Application.ShellExecute
     * with the "runas" verb, which triggers the UAC elevation prompt.
     *
     * <p>{@code ShellExecute} does not return until the user answers the UAC
     * prompt, and raises an automation error (0x800704C7, "operation cancelled
     * by the user") if they decline. The script therefore traps that error and
     * reports it through its exit code, and this method waits for it - which is
     * how a declined prompt becomes {@link ElevationResult#DECLINED} instead of
     * the caller exiting on the assumption that an elevated instance is on its
     * way.</p>
     *
     * @param targetExe absolute path to the executable to elevate
     * @param args      command-line arguments (already VBS-escaped), or "" for none
     * @param workDir   working directory for the elevated process
     */
    private static ElevationResult relaunchElevated(String targetExe, String args, String workDir) throws IOException {
        // In a VBS string literal, a double-quote is escaped by doubling it ("").
        // We do NOT wrap the value in outer quotes here - the VBS string delimiters
        // do that. So every " in the path becomes "".
        String vbsExe  = escapeForVbs(targetExe);
        String vbsWork = escapeForVbs(workDir);

        // ShellExecute signature:
        //   ShellExecute(file, args, workDir, verb, windowStyle)
        // windowStyle 1 = SW_SHOWNORMAL (visible window)
        // "On Error Resume Next" keeps wscript from popping its own error
        // dialog on top of ours when the user declines; the exit code carries
        // the answer instead.
        // Exit codes: 0 = elevated instance launched, 1 = user declined the
        // prompt, 2 = the shell object could not even be created. The last one
        // is kept separate so a broken scripting host is not silently reported
        // to the user as "you clicked No".
        String vbsScript =
                "On Error Resume Next\r\n" +
                "Set UAC = CreateObject(\"Shell.Application\")\r\n" +
                "If Err.Number <> 0 Then WScript.Quit 2\r\n" +
                "UAC.ShellExecute \"" + vbsExe + "\", " +
                    "\"" + args + "\", " +
                    "\"" + vbsWork + "\", " +
                    "\"runas\", 1\r\n" +
                "If Err.Number <> 0 Then WScript.Quit 1\r\n" +
                "WScript.Quit 0\r\n";

        File tempVbs = File.createTempFile("lipi_elevate", ".vbs");
        tempVbs.deleteOnExit();
        Files.writeString(tempVbs.toPath(), vbsScript);

        Process wscript = new ProcessBuilder("wscript", tempVbs.getAbsolutePath())
                .inheritIO()
                .start();
        try {
            // The UAC prompt is modal and can sit there indefinitely, so the
            // cap here is only a guard against a wscript that never returns at
            // all. Hitting it is treated as a successful hand-off: the launched
            // process is independent of this one, so exiting is still correct.
            if (!wscript.waitFor(UAC_PROMPT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                return ElevationResult.RELAUNCHED;
            }
            return switch (wscript.exitValue()) {
                case 0  -> ElevationResult.RELAUNCHED;
                case 1  -> ElevationResult.DECLINED;
                default -> ElevationResult.FAILED;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ElevationResult.FAILED;
        }
    }

    private static String escapeForVbs(String value) {
        return value.replace("\"", "\"\"");
    }

    private static ElevationResult restartAsAdminMac() throws IOException {
        String jarPath = getJarPath();
        if (jarPath == null) return ElevationResult.FAILED;

        // The relaunched app is detached with nohup + "&" so that "do shell
        // script" returns as soon as it has started, rather than blocking for
        // the entire lifetime of the new instance. That is what makes
        // osascript's exit code meaningful here: 0 once the app is running,
        // non-zero when the user dismissed the credentials dialog.
        String javaCmd = String.format("nohup java -jar '%s' >/dev/null 2>&1 &",
                escapeForShellSingleQuote(jarPath));
        String escapedForAppleScript = javaCmd.replace("\\", "\\\\").replace("\"", "\\\"");

        Process osascript = new ProcessBuilder(
                "osascript", "-e",
                "do shell script \"" + escapedForAppleScript + "\" with administrator privileges")
                .redirectErrorStream(true)
                .start();
        try {
            if (!osascript.waitFor(UAC_PROMPT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                return ElevationResult.RELAUNCHED;
            }
            return osascript.exitValue() == 0 ? ElevationResult.RELAUNCHED : ElevationResult.DECLINED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ElevationResult.FAILED;
        }
    }

    /**
     * Linux cannot report the outcome as cleanly as the other two platforms.
     * {@code pkexec} and friends do not detach: on success they stay alive for
     * as long as the relaunched app runs, so "still running" and "authenticated
     * successfully" look identical from here and waiting for the exit code is
     * not an option. What they do reliably is fail fast - pkexec exits 126 when
     * the authorization dialog is dismissed and 127 when authentication fails -
     * so a short wait catches a cancellation without stalling the successful
     * path.
     */
    private static ElevationResult restartAsAdminLinux() throws IOException {
        String jarPath = getJarPath();
        if (jarPath == null) return ElevationResult.FAILED;

        List<String> baseCmd = List.of("java", "-jar", jarPath);
        List<String> tried   = new ArrayList<>();

        for (String tool : List.of("pkexec", "gksudo", "kdesudo")) {
            List<String> cmd = new ArrayList<>();
            cmd.add(tool);
            cmd.addAll(baseCmd);
            try {
                Process process = new ProcessBuilder(cmd).start();
                try {
                    if (process.waitFor(LINUX_AUTH_FAILFAST_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                        int exit = process.exitValue();
                        // 126 = dialog dismissed, 127 = authentication failed.
                        if (exit == 126 || exit == 127) return ElevationResult.DECLINED;
                        if (exit != 0) return ElevationResult.FAILED;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ElevationResult.RELAUNCHED;
            } catch (IOException e) {
                tried.add(tool);
            }
        }

        throw new IOException(
                "Could not find a privilege escalation tool. Tried: " + tried + ".\n" +
                "Please run manually with: sudo java -jar " + jarPath);
    }

    private static String escapeForShellSingleQuote(String value) {
        return value.replace("'", "'\\''");
    }

    private static String getJarPath() {
        try {
            return new File(AdminUtil.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    public static String getAdminRequiredMessage() {
        if (SystemUtil.isWindows()) {
            return "This application requires Administrator privileges to install/remove system fonts.\n\n" +
                    "Please restart the application as Administrator by:\n" +
                    "1. Right-clicking the application\n" +
                    "2. Selecting 'Run as administrator'";
        } else if (SystemUtil.isMac()) {
            return "This application requires Administrator privileges to install/remove system fonts.\n\n" +
                    "Please restart the application with administrator privileges.";
        } else {
            return "This application requires root/sudo privileges to install/remove system fonts.\n\n" +
                    "Please run the application using:\n" +
                    "sudo java -jar lipi.jar";
        }
    }
}
