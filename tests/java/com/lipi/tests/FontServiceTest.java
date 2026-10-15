package com.lipi.tests;

import com.lipi.model.FontInfo;
import com.lipi.service.FontService;
import com.lipi.util.SystemUtil;
import com.lipi.util.WindowsFontRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lipi.tests.Harness.check;
import static com.lipi.tests.Harness.equal;
import static com.lipi.tests.Harness.write;

/**
 * Loading the library, and what a removal reports.
 *
 * <p>Nothing here installs or removes a real font. Installing writes into
 * {@code C:\Windows\Fonts} or the per-user store and adds a registry entry,
 * and a test suite that did that to the machine it runs on would be changing
 * the system to check that it can change the system. What is asserted instead
 * is everything up to that point: which files become rows, what those rows
 * say, and how the counts come back - which is where the defects were.</p>
 */
public final class FontServiceTest {

    private FontServiceTest() {}

    private static byte[] fontBytes(String name) {
        return Sfnt.font(Sfnt.Record.windows(Sfnt.NAME_FULL, name));
    }

    public static void run(Runner r) {
        r.group("FontService - loading the library");

        r.test("only font files become rows", () -> {
            Path dir = Harness.scratchDir("load1");
            write(dir, "a.ttf", fontBytes("Alpha"));
            write(dir, "b.otf", fontBytes("Beta"));
            write(dir, "C.TTF", fontBytes("Gamma"));
            write(dir, "d.OTF", fontBytes("Delta"));
            write(dir, "readme.txt", "notes".getBytes());
            write(dir, "lipi-debug.log", "log".getBytes());
            write(dir, "noextension", new byte[]{1});

            List<FontInfo> fonts = new FontService(dir.toString()).loadFontsFromDirectory();
            equal(4, fonts.size(), "four fonts, three other files");
        });

        r.test("a row shows the font's own name, not its file name", () -> {
            Path dir = Harness.scratchDir("load2");
            write(dir, "4prrch_.ttf", fontBytes("Kantipur"));

            List<FontInfo> fonts = new FontService(dir.toString()).loadFontsFromDirectory();
            equal(1, fonts.size(), "one font");
            equal("Kantipur", fonts.get(0).getName(),
                    "this is the whole point of the Font column");
            equal("4prrch_.ttf", fonts.get(0).getFileName(),
                    "and the File Name column still shows the file, so a mismatch is visible");
        });

        r.test("a missing fonts directory is created rather than reported", () -> {
            Path parent = Harness.scratchDir("load3");
            Path fonts = parent.resolve("fonts");
            List<FontInfo> result = new FontService(fonts.toString()).loadFontsFromDirectory();
            equal(0, result.size(), "a fresh clone has an empty library, not an error");
            check(Files.isDirectory(fonts), "and the directory now exists");
        });

        r.test("an empty fonts directory is not an error", () -> {
            Path dir = Harness.scratchDir("load4");
            equal(0, new FontService(dir.toString()).loadFontsFromDirectory().size(),
                    "the window says \"Showing 0 font(s)\"");
        });

        r.test("a fonts path that is a file is reported", () -> {
            Path dir = Harness.scratchDir("load5");
            File notADir = write(dir, "fonts", "this is a file".getBytes());
            Throwable t = Harness.throwsSomething(
                    () -> new FontService(notADir.toString()).loadFontsFromDirectory(),
                    "a file where the fonts directory should be");
            check(t instanceof java.io.IOException,
                    "the window shows the message, so it has to be an IOException, got " + t);
        });

        r.test("a font that cannot be parsed still gets a row", () -> {
            Path dir = Harness.scratchDir("load6");
            write(dir, "broken.ttf", "not really a font".getBytes());
            List<FontInfo> fonts = new FontService(dir.toString()).loadFontsFromDirectory();
            equal(1, fonts.size(), "a damaged font is still a file in the library");
            equal("broken", fonts.get(0).getName(), "named after its file, with the extension off");
        });

        // -- Removal counts -------------------------------------------------------

        r.group("FontService - what a removal reports");

        r.test("removing a font that is not installed is not a failure", () -> {
            // Selecting every row and clicking Remove is the ordinary way to
            // use this window, and most rows are usually not installed. Each
            // one of those comes back NOT_FOUND, and counting NOT_FOUND as a
            // failure produces "Failed to remove 297 font(s). See
            // lipi-debug.log for details." after a removal in which nothing
            // went wrong at all.
            Path dir = Harness.scratchDir("rm1");
            File f = write(dir, "lipi-test-not-installed-9f3a.ttf", fontBytes("NeverInstalled"));
            FontInfo row = new FontInfo(f.getName(), f.getAbsolutePath(), true);

            FontService svc = new FontService(dir.toString());
            FontService.RemoveBatchResult res = svc.removeFonts(List.of(row));

            equal(0, res.getFailedCount(),
                    "nothing failed: the font was not installed in the first place");
            check(!row.isInstalled(),
                    "and the row is corrected, because the table was wrong about it");
        });

        r.test("removing a font that is not installed removes nothing from disk", () -> {
            Path dir = Harness.scratchDir("rm2");
            File f = write(dir, "lipi-test-not-installed-7c21.ttf", fontBytes("NeverInstalled"));
            FontInfo row = new FontInfo(f.getName(), f.getAbsolutePath(), false);

            new FontService(dir.toString()).removeFonts(List.of(row));
            check(f.exists(),
                    "the library copy is not what Remove removes - it removes the "
                            + "installed copy, and the library keeps its own");
        });

        r.test("an empty removal reports nothing at all", () -> {
            Path dir = Harness.scratchDir("rm3");
            FontService.RemoveBatchResult res =
                    new FontService(dir.toString()).removeFonts(List.of());
            equal(0, res.getSuccessCount(), "no successes");
            equal(0, res.getFailedCount(), "no failures");
            equal(0, res.getEffectivelyRemovedCount(), "and nothing to tell the user");
        });

        // -- Installed status -----------------------------------------------------

        r.group("FontService - is this font installed?");

        File arial = new File(System.getenv("WINDIR") == null
                ? "C:\\Windows\\Fonts\\arial.ttf"
                : System.getenv("WINDIR") + "\\Fonts\\arial.ttf");

        if (!SystemUtil.isWindows() || !arial.exists()) {
            r.skip("a font present in the system store reads as installed",
                    "needs Windows with arial.ttf in the system fonts folder");
            r.skip("a font queued for deletion reads as not installed", "same");
        } else {
            r.test("a font present in the system store reads as installed", () -> {
                // Arial is on every Windows install and is registered in HKLM,
                // so this exercises the real registry path without the suite
                // having to install anything of its own.
                Path dir = Harness.scratchDir("st1");
                File libraryCopy = write(dir, "arial.ttf", fontBytes("Arial"));

                FontService svc = new FontService(dir.toString());
                Map<String, Boolean> snapshot = WindowsFontRegistry.queryAllRegisteredFonts();
                check(svc.isFontInstalled(libraryCopy, snapshot, Collections.emptySet()),
                        "arial.ttf is in the system fonts folder and in HKLM");
            });

            r.test("a font queued for deletion reads as not installed", () -> {
                // After a locked removal the bytes are still on disk until the
                // next reboot. Reporting that as installed is the "removed font
                // comes back" symptom the pending list exists to prevent.
                Path dir = Harness.scratchDir("st2");
                File libraryCopy = write(dir, "arial.ttf", fontBytes("Arial"));

                FontService svc = new FontService(dir.toString());
                Map<String, Boolean> snapshot = WindowsFontRegistry.queryAllRegisteredFonts();
                Path target = SystemUtil.getSystemFontPath(libraryCopy, snapshot);
                Set<String> queued = Set.of(target.toString().toLowerCase());

                check(!svc.isFontInstalled(libraryCopy, snapshot, queued),
                        "the user removed it and Explorer agrees it is gone");
            });
        }

        r.test("a font nothing knows about reads as not installed", () -> {
            Path dir = Harness.scratchDir("st3");
            File f = write(dir, "lipi-test-unknown-4b7e.ttf", fontBytes("Unknown"));
            FontService svc = new FontService(dir.toString());
            check(!svc.isFontInstalled(f, Map.of(), Collections.emptySet()),
                    "not in the registry, not on disk anywhere the system looks");
        });
    }
}
