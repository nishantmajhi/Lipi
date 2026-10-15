package com.lipi.tests;

import com.lipi.model.FontInfo;
import com.lipi.service.FontExportService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.lipi.tests.Harness.check;
import static com.lipi.tests.Harness.equal;
import static com.lipi.tests.Harness.write;

/**
 * Import and export - the four things Lipi does that are not installing.
 *
 * <p>Two of these checks are about what the user is told afterwards rather
 * than what happened. That is deliberate: both failures here were cases where
 * the operation did roughly the right thing and then reported it wrongly, and
 * a count that is wrong is indistinguishable from an operation that failed.</p>
 */
public final class FontExportServiceTest {

    private FontExportServiceTest() {}

    /** A font file with real name-table bytes, so a round trip can be checked properly. */
    private static byte[] fontBytes(String name) {
        return Sfnt.font(Sfnt.Record.windows(Sfnt.NAME_FULL, name));
    }

    private static FontInfo info(File f) {
        return new FontInfo(f.getName(), f.getAbsolutePath(), false);
    }

    /** Reads a ZIP into a map of entry name to contents, so assertions can be exact. */
    private static Map<String, byte[]> readZip(File zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.put(e.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return out;
    }

    private static byte[] buildZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(raw)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return raw.toByteArray();
    }

    /**
     * Cuts an archive off part-way through its last entry's compressed data -
     * a download that stopped, or a file copied off a failing disk.
     *
     * <p>Chopping a fixed number of bytes off the end is not enough and looks
     * like it works: the last couple of hundred bytes of a small archive are
     * its central directory, which {@code ZipInputStream} never reads, so
     * every entry still extracts perfectly and the test passes while proving
     * nothing. The cut has to land inside the data, so this finds the last
     * local file header and stops a little way past it.</p>
     */
    private static byte[] truncateInsideLastEntry(byte[] zip) {
        int lastHeader = -1;
        for (int i = 0; i + 4 <= zip.length; i++) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4B && zip[i + 2] == 0x03 && zip[i + 3] == 0x04) {
                lastHeader = i;
            }
        }
        check(lastHeader > 0, "no local file header found in the fixture archive");
        // Local header is 30 bytes plus the file name; a little past that is
        // inside the compressed data.
        int cut = lastHeader + 30 + 16;
        check(cut < zip.length, "the fixture's last entry is too small to cut inside");
        return Arrays.copyOf(zip, cut);
    }

    /**
     * Bytes that do not compress, so a truncated entry really is short of data
     * rather than being a handful of bytes that happened to survive.
     */
    private static byte[] incompressible(int length, long seed) {
        byte[] out = new byte[length];
        new java.util.Random(seed).nextBytes(out);
        return out;
    }

    public static void run(Runner r) {
        r.group("FontExportService - import and export");

        // -- Export as separate files -------------------------------------------

        r.test("exporting copies the bytes, not just the names", () -> {
            Path dir = Harness.scratchDir("exp1");
            Path out = Harness.scratchDir("exp1out");
            File a = write(dir, "a.ttf", fontBytes("Alpha"));
            File b = write(dir, "b.ttf", fontBytes("Beta"));

            FontExportService svc = new FontExportService(dir.toString());
            FontExportService.ExportResult res =
                    svc.exportAsSeparateFiles(List.of(info(a), info(b)), out.toFile());

            equal(2, res.getSuccessCount(), "both exported");
            equal(0, res.getFailedCount(), "neither failed");
            check(Arrays.equals(Harness.read(a), Harness.read(out.resolve("a.ttf").toFile())),
                    "the exported file is byte-for-byte the original");
        });

        r.test("exporting a font whose file has vanished is counted as failed", () -> {
            Path dir = Harness.scratchDir("exp2");
            Path out = Harness.scratchDir("exp2out");
            File a = write(dir, "a.ttf", fontBytes("Alpha"));
            FontInfo ghost = new FontInfo("gone.ttf", dir.resolve("gone.ttf").toString(), false);

            FontExportService svc = new FontExportService(dir.toString());
            FontExportService.ExportResult res =
                    svc.exportAsSeparateFiles(List.of(info(a), ghost), out.toFile());

            equal(1, res.getSuccessCount(), "the one that exists");
            equal(1, res.getFailedCount(), "the one that does not");
        });

        // -- Export as a ZIP -----------------------------------------------------

        r.test("a ZIP export round-trips every font", () -> {
            Path dir = Harness.scratchDir("zip1");
            Path out = Harness.scratchDir("zip1out");
            File a = write(dir, "a.ttf", fontBytes("Alpha"));
            File b = write(dir, "b.ttf", fontBytes("Beta"));
            File zip = out.resolve("fonts.zip").toFile();

            FontExportService svc = new FontExportService(dir.toString());
            FontExportService.ExportResult res = svc.exportAsZip(List.of(info(a), info(b)), zip);

            equal(2, res.getSuccessCount(), "both added");
            Map<String, byte[]> inside = readZip(zip);
            equal(2, inside.size(), "two entries");
            check(Arrays.equals(Harness.read(a), inside.get("a.ttf")),
                    "the bytes survive the compression");
        });

        r.test("a font that cannot be read leaves no empty entry in the ZIP", () -> {
            // The entry is opened before the source file is, so a source that
            // cannot be read leaves an entry already started. Left unclosed, the
            // next putNextEntry closes it anyway and the archive ends up with a
            // zero-byte .ttf in it - a file Windows will not install, delivered
            // alongside a dialog that said this font failed.
            Path dir = Harness.scratchDir("zip2");
            Path out = Harness.scratchDir("zip2out");
            File a = write(dir, "a.ttf", fontBytes("Alpha"));
            File c = write(dir, "c.ttf", fontBytes("Gamma"));
            FontInfo ghost = new FontInfo("b.ttf", dir.resolve("b.ttf").toString(), false);
            File zip = out.resolve("fonts.zip").toFile();

            FontExportService svc = new FontExportService(dir.toString());
            FontExportService.ExportResult res =
                    svc.exportAsZip(List.of(info(a), ghost, info(c)), zip);

            equal(2, res.getSuccessCount(), "two fonts were readable");
            equal(1, res.getFailedCount(), "one was not");

            Map<String, byte[]> inside = readZip(zip);
            equal(null, inside.get("b.ttf"),
                    "a font that failed must not appear in the archive at all");
            equal(2, inside.size(), "exactly the two that succeeded");
            for (Map.Entry<String, byte[]> e : inside.entrySet()) {
                check(e.getValue().length > 0, "entry " + e.getKey() + " is empty");
            }
        });

        r.test("a ZIP export of nothing is still a readable archive", () -> {
            Path dir = Harness.scratchDir("zip3");
            Path out = Harness.scratchDir("zip3out");
            File zip = out.resolve("empty.zip").toFile();

            FontExportService svc = new FontExportService(dir.toString());
            FontExportService.ExportResult res = svc.exportAsZip(List.of(), zip);

            equal(0, res.getSuccessCount(), "nothing to export");
            equal(0, readZip(zip).size(), "an archive with no entries, not a corrupt file");
        });

        // -- Import from a ZIP ---------------------------------------------------

        r.test("importing extracts every font and ignores everything else", () -> {
            Path dir = Harness.scratchDir("imp1");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("a.ttf", fontBytes("Alpha"));
            entries.put("b.OTF", fontBytes("Beta"));
            entries.put("readme.txt", "not a font".getBytes());
            entries.put("licence.pdf", "also not a font".getBytes());
            File zip = write(dir, "in.zip", buildZip(entries));

            Path fonts = Harness.scratchDir("imp1fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            FontExportService.ExportResult res = svc.extractFromZip(zip);

            equal(2, res.getSuccessCount(), "the two fonts");
            check(Files.exists(fonts.resolve("a.ttf")), "a.ttf landed");
            check(Files.exists(fonts.resolve("b.OTF")), "an upper-case extension is still a font");
            check(!Files.exists(fonts.resolve("readme.txt")), "a text file is not imported");
        });

        r.test("a font in a subdirectory is imported into the fonts folder itself", () -> {
            Path dir = Harness.scratchDir("imp2");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("nepali/devanagari/preeti.ttf", fontBytes("Preeti"));
            File zip = write(dir, "in.zip", buildZip(entries));

            Path fonts = Harness.scratchDir("imp2fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            equal(1, svc.extractFromZip(zip).getSuccessCount(), "one font");
            check(Files.exists(fonts.resolve("preeti.ttf")),
                    "Lipi has one flat fonts directory; the archive's tree is not reproduced");
        });

        r.test("an entry that tries to escape the fonts folder cannot", () -> {
            Path dir = Harness.scratchDir("imp3");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("../../evil.ttf", fontBytes("Evil"));
            entries.put("..\\..\\evil2.ttf", fontBytes("Evil2"));
            File zip = write(dir, "in.zip", buildZip(entries));

            Path fonts = Harness.scratchDir("imp3fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            svc.extractFromZip(zip);

            check(!Files.exists(fonts.getParent().resolve("evil.ttf")),
                    "nothing was written outside the fonts directory");
            check(!Files.exists(fonts.getParent().resolve("evil2.ttf")),
                    "nor by the backslash spelling");
        });

        r.test("a ZIP with no fonts in it reports nothing extracted", () -> {
            Path dir = Harness.scratchDir("imp4");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("readme.txt", "no fonts here".getBytes());
            File zip = write(dir, "in.zip", buildZip(entries));

            Path fonts = Harness.scratchDir("imp4fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            FontExportService.ExportResult res = svc.extractFromZip(zip);
            equal(0, res.getSuccessCount(),
                    "which is what makes the window say \"No Fonts Found\"");
        });

        r.test("a truncated ZIP still reports the fonts it did extract", () -> {
            // A partial import is the case the count has to get right. The
            // window shows "No Fonts Found - no .ttf or .otf font files were
            // found in the ZIP archive" whenever the count is zero, and it
            // does not refresh the table. Reporting zero after writing two
            // fonts to disk therefore tells the user the opposite of what
            // happened and hides the result until the next launch.
            Path dir = Harness.scratchDir("imp5");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("a.ttf", fontBytes("Alpha"));
            entries.put("b.ttf", fontBytes("Beta"));
            entries.put("c.ttf", incompressible(4096, 1));
            File zip = write(dir, "cut.zip", truncateInsideLastEntry(buildZip(entries)));

            Path fonts = Harness.scratchDir("imp5fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            FontExportService.ExportResult res = svc.extractFromZip(zip);

            check(Files.exists(fonts.resolve("a.ttf")), "a.ttf really is on disk");
            check(Files.exists(fonts.resolve("b.ttf")), "b.ttf really is on disk");
            equal(2, res.getSuccessCount(),
                    "two fonts were written; saying zero denies what is on disk");
            check(res.getFailedCount() > 0, "the truncation is still reported as a failure");
        });

        r.test("a font cut short by a truncated ZIP is not left behind", () -> {
            Path dir = Harness.scratchDir("imp6");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("a.ttf", fontBytes("Alpha"));
            entries.put("partial.ttf", incompressible(4096, 2));
            File zip = write(dir, "cut.zip", truncateInsideLastEntry(buildZip(entries)));

            Path fonts = Harness.scratchDir("imp6fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            svc.extractFromZip(zip);

            check(!Files.exists(fonts.resolve("partial.ttf")),
                    "half a font in the library is worse than no font: it installs, "
                            + "and then renders nothing");
        });

        r.test("a file that is not a ZIP at all is reported as a failure", () -> {
            Path dir = Harness.scratchDir("imp7");
            File notZip = write(dir, "in.zip", "definitely not a zip file".getBytes());
            Path fonts = Harness.scratchDir("imp7fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            FontExportService.ExportResult res = svc.extractFromZip(notZip);
            equal(0, res.getSuccessCount(), "nothing came out");
            check(res.getFailedCount() > 0, "and that is a failure, not an empty archive");
        });

        r.test("importing creates the fonts directory if it is missing", () -> {
            Path dir = Harness.scratchDir("imp8");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("a.ttf", fontBytes("Alpha"));
            File zip = write(dir, "in.zip", buildZip(entries));

            Path fonts = Harness.scratchDir("imp8fonts").resolve("does-not-exist-yet");
            FontExportService svc = new FontExportService(fonts.toString());
            equal(1, svc.extractFromZip(zip).getSuccessCount(),
                    "a fresh clone has no fonts directory at all");
        });

        // -- Add files directly ---------------------------------------------------

        r.test("adding font files copies them into the library", () -> {
            Path src = Harness.scratchDir("add1src");
            File a = write(src, "a.ttf", fontBytes("Alpha"));
            File b = write(src, "b.ttf", fontBytes("Beta"));

            Path fonts = Harness.scratchDir("add1fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            FontExportService.ExportResult res = svc.addFontFiles(List.of(a, b));

            equal(2, res.getSuccessCount(), "both copied");
            check(Files.exists(fonts.resolve("a.ttf")) && Files.exists(fonts.resolve("b.ttf")),
                    "both are in the library");
        });

        r.test("adding a file that does not exist is counted as failed", () -> {
            Path src = Harness.scratchDir("add2src");
            Path fonts = Harness.scratchDir("add2fonts");
            FontExportService svc = new FontExportService(fonts.toString());
            List<File> files = new ArrayList<>();
            files.add(src.resolve("nothing.ttf").toFile());
            equal(1, svc.addFontFiles(files).getFailedCount(), "one failure, no exception");
        });

        r.test("re-adding a font already in the library is not an error", () -> {
            Path fonts = Harness.scratchDir("add3fonts");
            File inPlace = write(fonts, "a.ttf", fontBytes("Alpha"));
            FontExportService svc = new FontExportService(fonts.toString());
            FontExportService.ExportResult res = svc.addFontFiles(List.of(inPlace));
            equal(1, res.getSuccessCount(),
                    "picking a font out of the fonts folder in the file chooser is easy to do");
            check(Files.exists(fonts.resolve("a.ttf")), "and it is still there afterwards");
        });
    }
}
