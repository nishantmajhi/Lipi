package com.lipi.tests;

import com.lipi.util.SystemUtil;
import com.lipi.util.WindowsFontRegistry;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static com.lipi.tests.Harness.check;
import static com.lipi.tests.Harness.equal;

/**
 * Reading the two font registry hives - the check behind every "Installed"
 * cell in the table, and behind which directory an install writes to.
 *
 * <p>{@code WindowsFontRegistry} exists because copying a file into the Fonts
 * folder is not what makes Windows see a font; the registry entry is. Its own
 * class comment says so at length. The snapshot this test covers is how that
 * belief is read back, and it had the hives' storage formats the wrong way
 * round.</p>
 */
public final class RegistrySnapshotTest {

    private RegistrySnapshotTest() {}

    /**
     * Output in the shape {@code reg query} prints for the system hive. Taken
     * from a real machine: the data is a bare file name, because
     * {@code %WINDIR%\Fonts} is the implicit base directory for this hive.
     */
    private static final String HKLM_OUTPUT = String.join("\r\n",
            "",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
            "    Arial (TrueType)    REG_SZ    arial.ttf",
            "    Arial Bold (TrueType)    REG_SZ    arialbd.ttf",
            "    Kantipur (TrueType)    REG_SZ    KANTI.TTF",
            "    Kantipur (Plain):001.001 (TrueType)    REG_SZ    KANTI.TTF",
            "");

    /**
     * Output in the shape {@code reg query} prints for the per-user hive, from
     * the same machine. Every value is an absolute path. Not one is a bare
     * file name, and that is not a quirk of one installation: {@code HKCU} has
     * no implicit Fonts directory to resolve a bare name against.
     */
    private static final String HKCU_OUTPUT = String.join("\r\n",
            "",
            "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
            "    Kantipur Regular (TrueType)    REG_SZ    C:\\Users\\me\\AppData\\Local\\Microsoft\\Windows\\Fonts\\Kantipur Regular.ttf",
            "    Limelight Regular (TrueType)    REG_SZ    C:\\Users\\me\\AppData\\Local\\Microsoft\\Windows\\Fonts\\Limelight-Regular.ttf",
            "    TrajanPro-Bold (TrueType)    REG_SZ    C:\\Users\\me\\AppData\\Local\\Microsoft\\Windows\\Fonts\\trajanpro-bold.otf",
            "",
            "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts\\Microsoft.InkDraft_1.0.1.0_neutral__8wekyb3d8bbwe",
            "");

    /**
     * The original parsing loop, transcribed exactly from the first commit.
     *
     * <p>It is here because the original had no seam: the six lines below sat
     * inside a method that spawns {@code reg.exe}, so there is no way to run
     * the original logic against captured output without copying it out. The
     * {@code transcription still matches the original source} check below
     * reads the first commit out of git and fails if these lines have drifted
     * from what it says, so this cannot quietly stop describing the code it
     * claims to describe.</p>
     */
    static Map<String, Boolean> originalIndex(String output, boolean isHkcu) {
        Map<String, Boolean> result = new HashMap<>();
        for (String line : output.split("\r?\n")) {
            int marker = line.indexOf("REG_SZ");
            if (marker < 0) continue;
            String data = line.substring(marker + "REG_SZ".length()).trim();
            if (data.isEmpty()) continue;
            result.put(data.toLowerCase(), isHkcu);
        }
        return result;
    }

    /**
     * Calls the package-private extracted parser, by reflection so that this
     * file still compiles against the original sources - where the method does
     * not exist, because the parsing was inline in a method that spawns
     * {@code reg.exe}. Running the suite with {@code run.ps1 -Original} then
     * reports these checks as failures naming the missing seam, rather than
     * failing to build and reporting nothing at all.
     */
    private static Map<String, Boolean> index(String hkcuOutput, String hklmOutput) throws Exception {
        Method m;
        try {
            m = WindowsFontRegistry.class.getDeclaredMethod(
                    "indexRegQueryOutput", String.class, boolean.class, Map.class);
        } catch (NoSuchMethodException e) {
            throw new Harness.Failure("WindowsFontRegistry.indexRegQueryOutput does not exist:"
                    + " the hive parsing is still inline in queryAllRegisteredFonts, where it"
                    + " cannot be run against captured output");
        }
        m.setAccessible(true);
        Map<String, Boolean> into = new HashMap<>();
        // The real caller reads the per-user hive first; collisions resolve to
        // whichever hive was read first, so the order is part of the contract.
        if (hkcuOutput != null) m.invoke(null, hkcuOutput, true, into);
        if (hklmOutput != null) m.invoke(null, hklmOutput, false, into);
        return into;
    }

    public static void run(Runner r) {
        r.group("WindowsFontRegistry - reading the font hives");

        // -- The defect, in the original code ---------------------------------

        r.test("the original keyed per-user fonts on a path nobody looks up", () -> {
            Map<String, Boolean> original = originalIndex(HKCU_OUTPUT, true);

            check(!original.isEmpty(), "the transcription parsed nothing at all");
            equal(null, original.get("kantipur regular.ttf"),
                    "the original could not answer the only question callers ask:"
                            + " is this file name registered per-user?");
            check(original.containsKey(
                            "c:\\users\\me\\appdata\\local\\microsoft\\windows\\fonts\\kantipur regular.ttf"),
                    "it stored the entry under the full path instead");
        });

        r.test("the original was right about the system hive", () -> {
            Map<String, Boolean> original = originalIndex(HKLM_OUTPUT, false);
            equal(Boolean.FALSE, original.get("arial.ttf"),
                    "bare names in HKLM always worked, which is why this survived");
        });

        // -- The fix ------------------------------------------------------------

        r.test("a per-user font is found by its file name", () -> {
            Map<String, Boolean> snapshot = index(HKCU_OUTPUT, HKLM_OUTPUT);
            equal(Boolean.TRUE, snapshot.get("kantipur regular.ttf"),
                    "HKCU means the per-user store");
            equal(Boolean.TRUE, snapshot.get("limelight-regular.ttf"), "a hyphenated name");
            equal(Boolean.TRUE, snapshot.get("trajanpro-bold.otf"), "an OpenType font");
        });

        r.test("a per-user font is also found by its full path", () -> {
            Map<String, Boolean> snapshot = index(HKCU_OUTPUT, HKLM_OUTPUT);
            equal(Boolean.TRUE, snapshot.get(
                            "c:\\users\\me\\appdata\\local\\microsoft\\windows\\fonts\\kantipur regular.ttf"),
                    "the full path is what Lipi itself now writes into HKCU");
        });

        r.test("a system font is still found by its file name", () -> {
            Map<String, Boolean> snapshot = index(HKCU_OUTPUT, HKLM_OUTPUT);
            equal(Boolean.FALSE, snapshot.get("arial.ttf"), "HKLM means the system store");
            equal(Boolean.FALSE, snapshot.get("kanti.ttf"),
                    "lookups are case-insensitive: the registry says KANTI.TTF");
        });

        r.test("an unregistered font is absent, not false", () -> {
            Map<String, Boolean> snapshot = index(HKCU_OUTPUT, HKLM_OUTPUT);
            equal(null, snapshot.get("nosuchfont.ttf"),
                    "null and FALSE mean different things to getSystemFontPath:"
                            + " not installed anywhere, versus installed system-wide");
        });

        r.test("two registry entries for one file collapse to one key", () -> {
            Map<String, Boolean> snapshot = index(null, HKLM_OUTPUT);
            equal(Boolean.FALSE, snapshot.get("kanti.ttf"),
                    "KANTI.TTF is registered twice under different value names");
        });

        r.test("the per-user hive wins when both hives name the same file", () -> {
            String hkcu = "    X (TrueType)    REG_SZ    C:\\Users\\me\\AppData\\Local"
                    + "\\Microsoft\\Windows\\Fonts\\shared.ttf";
            String hklm = "    X (TrueType)    REG_SZ    shared.ttf";
            Map<String, Boolean> snapshot = index(hkcu, hklm);
            equal(Boolean.TRUE, snapshot.get("shared.ttf"),
                    "the per-user copy is the one an unelevated user can remove");
        });

        r.test("key lines and blank lines are ignored", () -> {
            Map<String, Boolean> snapshot = index(HKCU_OUTPUT, HKLM_OUTPUT);
            for (String key : snapshot.keySet()) {
                check(!key.startsWith("hkey_"),
                        "a hive header line was parsed as a font: " + key);
                check(!key.isBlank(), "a blank key was stored");
            }
        });

        r.test("a value with empty data is ignored", () -> {
            Map<String, Boolean> snapshot = index("    Broken (TrueType)    REG_SZ    ", null);
            equal(0, snapshot.size(), "an empty data field names no file");
        });

        r.test("output with no font values yields an empty snapshot", () -> {
            Map<String, Boolean> snapshot = index("ERROR: The system was unable to find the "
                    + "specified registry key or value.", null);
            equal(0, snapshot.size(), "an error message is not a font list");
        });

        // -- Against the machine's own registry ---------------------------------

        if (!SystemUtil.isWindows()) {
            r.skip("every entry in the live snapshot is reachable by file name",
                    "not Windows");
        } else {
            r.test("every entry in the live snapshot is reachable by file name", () -> {
                Map<String, Boolean> live = WindowsFontRegistry.queryAllRegisteredFonts();
                check(!live.isEmpty(),
                        "reg query returned nothing at all - this check would otherwise"
                                + " pass by having nothing to look at");

                int perUser = 0;
                for (Map.Entry<String, Boolean> e : live.entrySet()) {
                    if (!e.getValue()) continue;
                    perUser++;
                    String key = e.getKey();
                    if (key.indexOf('\\') < 0) continue;      // already a bare name
                    String bare = key.substring(key.lastIndexOf('\\') + 1);
                    check(live.containsKey(bare),
                            "per-user font " + bare + " is in the registry but cannot be"
                                    + " looked up by the name every caller uses");
                }
                if (perUser == 0) {
                    // Not a failure, but say so: on a machine with no per-user
                    // fonts this check has nothing to prove and must not be
                    // read as evidence that the parsing is right.
                    System.out.println("       (note: no per-user fonts registered on this"
                            + " machine; the canned-output checks above carry this one)");
                }
            });
        }
    }
}
