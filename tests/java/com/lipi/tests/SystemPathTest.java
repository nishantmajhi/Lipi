package com.lipi.tests;

import com.lipi.util.SystemUtil;
import com.lipi.util.WindowsFontRegistry;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.lipi.tests.Harness.check;
import static com.lipi.tests.Harness.equal;

/**
 * Where a font lives, and what Windows is told about it.
 *
 * <p>These two questions are the same question asked in opposite directions,
 * and the project got the second one wrong for the per-user store. A font
 * installed without Administrator rights goes to
 * {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts} and is registered under
 * {@code HKCU} - and unlike {@code HKLM}, that hive has no implicit Fonts
 * directory to resolve a bare file name against.</p>
 */
public final class SystemPathTest {

    private SystemPathTest() {}

    private static Path perUser(String fileName) throws Exception {
        Method m = SystemUtil.class.getDeclaredMethod("perUserFontPath", String.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, fileName);
    }

    /**
     * By reflection so this file compiles against the original sources too,
     * where {@code register()} passed {@code fontFile.getName()} straight to
     * {@code reg add} for both hives and there was nothing to call.
     */
    private static String registryData(File f) throws Exception {
        Method m;
        try {
            m = WindowsFontRegistry.class.getDeclaredMethod("registryDataFor", File.class);
        } catch (NoSuchMethodException e) {
            throw new Harness.Failure("WindowsFontRegistry.registryDataFor does not exist:"
                    + " register() writes fontFile.getName() for both hives, so a per-user"
                    + " entry names a file in %WINDIR%\\Fonts that is not there");
        }
        m.setAccessible(true);
        return (String) m.invoke(null, f);
    }

    public static void run(Runner r) {
        r.group("SystemUtil - which store owns a font");

        if (!SystemUtil.isWindows()) {
            r.skip("the registry hive decides the path", "not Windows");
            r.skip("an HKLM font resolves to the system fonts folder", "not Windows");
            r.skip("a per-user registration carries a full path", "not Windows");
            return;
        }

        r.test("an HKCU font resolves to the per-user store", () -> {
            File f = new File("lipi-test-hkcu-1a2b.ttf");
            Map<String, Boolean> snapshot = new HashMap<>();
            snapshot.put("lipi-test-hkcu-1a2b.ttf", Boolean.TRUE);

            Path resolved = SystemUtil.getSystemFontPath(f, snapshot);
            equal(perUser(f.getName()), resolved,
                    "HKCU is the authoritative sign of the per-user store");
        });

        r.test("an HKLM font resolves to the system fonts folder", () -> {
            File f = new File("lipi-test-hklm-3c4d.ttf");
            Map<String, Boolean> snapshot = new HashMap<>();
            snapshot.put("lipi-test-hklm-3c4d.ttf", Boolean.FALSE);

            Path resolved = SystemUtil.getSystemFontPath(f, snapshot);
            String windir = System.getenv("WINDIR");
            check(resolved.toString().toLowerCase()
                            .startsWith((windir == null ? "c:\\windows" : windir).toLowerCase()),
                    "HKLM means %WINDIR%\\Fonts, got " + resolved);
        });

        r.test("the hive is consulted case-insensitively", () -> {
            File f = new File("LIPI-TEST-CASE-5E6F.TTF");
            Map<String, Boolean> snapshot = new HashMap<>();
            snapshot.put("lipi-test-case-5e6f.ttf", Boolean.TRUE);

            equal(perUser(f.getName()), SystemUtil.getSystemFontPath(f, snapshot),
                    "the registry and the file system disagree about case all the time");
        });

        r.test("a font in neither hive and nowhere on disk gets an install target", () -> {
            File f = new File("lipi-test-absent-7a8b.ttf");
            Path resolved = SystemUtil.getSystemFontPath(f, Map.of());
            check(resolved != null && resolved.toString().endsWith("lipi-test-absent-7a8b.ttf"),
                    "an unregistered font still needs somewhere to be installed to, got " + resolved);
        });

        r.test("the per-user path is under LOCALAPPDATA", () -> {
            Path p = perUser("x.ttf");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isBlank()) {
                check(p == null, "with no LOCALAPPDATA there is no per-user store");
            } else {
                equal(Path.of(localAppData, "Microsoft", "Windows", "Fonts", "x.ttf"), p,
                        "this is where Windows 10 and 11 keep per-user fonts");
            }
        });

        // -- What gets written into the registry ---------------------------------

        r.group("WindowsFontRegistry - what the registry entry points at");

        r.test("a system font is registered by bare file name", () -> {
            String windir = System.getenv("WINDIR");
            File systemFont = new File((windir == null ? "C:\\Windows" : windir)
                    + "\\Fonts\\lipi-test-sys-9c0d.ttf");
            equal("lipi-test-sys-9c0d.ttf", registryData(systemFont),
                    "HKLM resolves a bare name against %WINDIR%\\Fonts, which is where this is");
        });

        r.test("a per-user font is registered by full path", () -> {
            // The original always wrote fontFile.getName(), for both hives.
            // In HKCU that names a file in %WINDIR%\Fonts - somewhere the font
            // is not - so the entry resolves to nothing and the font does not
            // load, which is the exact failure this class was written to stop.
            // Every per-user entry Windows itself writes is an absolute path.
            Path perUserPath = perUser("lipi-test-user-1e2f.ttf");
            if (perUserPath == null) {
                check(false, "no LOCALAPPDATA, so this machine has no per-user store to test");
                return;
            }
            String data = registryData(perUserPath.toFile());
            equal(perUserPath.toString(), data,
                    "HKCU has no base directory, so the value has to say where the file is");
            check(data.contains("\\"),
                    "a bare name here would send Windows looking in the wrong folder");
        });

        r.test("a font outside both stores is registered by bare name", () -> {
            // Nothing installs from here, but the fallback must be defined.
            File odd = new File("D:\\somewhere\\else\\lipi-test-odd-3a4b.ttf");
            equal("lipi-test-odd-3a4b.ttf", registryData(odd),
                    "anything not under the per-user store is treated as a system font");
        });
    }
}
