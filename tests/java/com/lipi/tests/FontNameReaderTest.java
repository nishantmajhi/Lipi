package com.lipi.tests;

import com.lipi.util.FontNameReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.lipi.tests.Harness.check;
import static com.lipi.tests.Harness.equal;
import static com.lipi.tests.Harness.write;
import static com.lipi.tests.Sfnt.LANG_MAC_ENGLISH;
import static com.lipi.tests.Sfnt.LANG_WIN_EN_US;
import static com.lipi.tests.Sfnt.LANG_WIN_NEPALI;
import static com.lipi.tests.Sfnt.NAME_FAMILY;
import static com.lipi.tests.Sfnt.NAME_FULL;
import static com.lipi.tests.Sfnt.NAME_SUBFAMILY;
import static com.lipi.tests.Sfnt.NAME_TYPO_FAMILY;
import static com.lipi.tests.Sfnt.NAME_TYPO_SUBFAMILY;
import static com.lipi.tests.Sfnt.OTTO;
import static com.lipi.tests.Sfnt.PLATFORM_MACINTOSH;
import static com.lipi.tests.Sfnt.PLATFORM_UNICODE;
import static com.lipi.tests.Sfnt.PLATFORM_WINDOWS;
import static com.lipi.tests.Sfnt.Record;

/**
 * What name Lipi puts in the Font column, given what is inside the file.
 *
 * <p>This is the one piece of the application the user reads on every row, and
 * it is derived rather than stored, so it is worth asserting against the bytes
 * rather than against another copy of the same logic.</p>
 */
public final class FontNameReaderTest {

    private FontNameReaderTest() {}

    public static void run(Runner r) {
        r.group("FontNameReader - the name shown in the Font column");

        Path dir = Harness.scratchDir("names");

        r.test("the full name wins over family and style", () -> {
            File f = write(dir, "a.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "Kantipur Bold"),
                    Record.windows(NAME_FAMILY, "Kantipur"),
                    Record.windows(NAME_SUBFAMILY, "Bold")));
            equal("Kantipur Bold", FontNameReader.readDisplayName(f, "a.ttf"),
                    "name ID 4 is the string every font menu shows");
        });

        r.test("family and style are joined when there is no full name", () -> {
            File f = write(dir, "b.ttf", Sfnt.font(
                    Record.windows(NAME_FAMILY, "Preeti"),
                    Record.windows(NAME_SUBFAMILY, "Heavy")));
            equal("Preeti Heavy", FontNameReader.readDisplayName(f, "b.ttf"),
                    "family plus style");
        });

        r.test("a style already on the end of the family is not repeated", () -> {
            File f = write(dir, "c.ttf", Sfnt.font(
                    Record.windows(NAME_FAMILY, "Sagarmatha Bold"),
                    Record.windows(NAME_SUBFAMILY, "Bold")));
            equal("Sagarmatha Bold", FontNameReader.readDisplayName(f, "c.ttf"),
                    "\"Sagarmatha Bold Bold\" would be the naive answer");
        });

        r.test("a Windows record beats a Macintosh one for the same name ID", () -> {
            File f = write(dir, "d.ttf", Sfnt.font(
                    Record.mac(NAME_FULL, "MacName"),
                    Record.windows(NAME_FULL, "WindowsName")));
            equal("WindowsName", FontNameReader.readDisplayName(f, "d.ttf"),
                    "the Windows string is the one the rest of the system shows");
        });

        r.test("record order does not decide the winner", () -> {
            // Same two records as above with the Windows one written first, so
            // a parser that simply keeps the last record it saw would disagree
            // with the previous check.
            File f = write(dir, "e.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "WindowsName"),
                    Record.mac(NAME_FULL, "MacName")));
            equal("WindowsName", FontNameReader.readDisplayName(f, "e.ttf"),
                    "scoring, not position, picks the record");
        });

        r.test("US English beats another Windows language", () -> {
            File f = write(dir, "f.ttf", Sfnt.font(
                    new Record(PLATFORM_WINDOWS, 1, LANG_WIN_NEPALI, NAME_FULL, "कान्तिपुर"),
                    new Record(PLATFORM_WINDOWS, 1, LANG_WIN_EN_US, NAME_FULL, "Kantipur")));
            equal("Kantipur", FontNameReader.readDisplayName(f, "f.ttf"),
                    "en-US ranks above other Windows languages");
        });

        r.test("a Devanagari name survives the UTF-16BE round trip", () -> {
            String devanagari = "कान्तिपुर";
            File f = write(dir, "g.ttf", Sfnt.font(
                    new Record(PLATFORM_WINDOWS, 1, LANG_WIN_NEPALI, NAME_FULL, devanagari)));
            equal(devanagari, FontNameReader.readDisplayName(f, "g.ttf"),
                    "a font that names itself in Nepali keeps its name");
        });

        r.test("the typographic family outranks the legacy family", () -> {
            File f = write(dir, "h.ttf", Sfnt.font(
                    Record.windows(NAME_FAMILY, "Himali Basic"),
                    Record.windows(NAME_TYPO_FAMILY, "Himali"),
                    Record.windows(NAME_SUBFAMILY, "Regular"),
                    Record.windows(NAME_TYPO_SUBFAMILY, "Light")));
            equal("Himali Light", FontNameReader.readDisplayName(f, "h.ttf"),
                    "name IDs 16/17 are the modern pair and win over 1/2");
        });

        // -- The legacy Mac decoration these fonts actually carry -------------
        //
        // Every one of these strings is the shape a real Nepali font in this
        // library uses. KANTI.TTF in fonts/ declares itself, on the Windows
        // platform, as "Kantipur (Plain):001.001".

        r.test("a build stamp after a colon is dropped", () -> {
            File f = write(dir, "i.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "Annapurna (Plain):3/3/94 13:55:39")));
            equal("Annapurna", FontNameReader.readDisplayName(f, "i.ttf"),
                    "what the user wants to read is the font's name");
        });

        r.test("a version stamp after a colon is dropped", () -> {
            File f = write(dir, "j.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "Kantipur (Plain):001.001")));
            equal("Kantipur", FontNameReader.readDisplayName(f, "j.ttf"),
                    "the exact string fonts/KANTI.TTF carries");
        });

        r.test("a bare (Plain) marker is dropped", () -> {
            File f = write(dir, "k.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "Preeti (Plain)")));
            equal("Preeti", FontNameReader.readDisplayName(f, "k.ttf"),
                    "(Plain) says nothing about the font");
        });

        r.test("a subfamily of Regular is not appended", () -> {
            File f = write(dir, "l.ttf", Sfnt.font(
                    Record.windows(NAME_FAMILY, "Sagarmatha"),
                    Record.windows(NAME_SUBFAMILY, "Regular")));
            equal("Sagarmatha", FontNameReader.readDisplayName(f, "l.ttf"),
                    "\"Sagarmatha Regular\" adds nothing");
        });

        r.test("a real style is still appended", () -> {
            File f = write(dir, "m.ttf", Sfnt.font(
                    Record.windows(NAME_FAMILY, "Sagarmatha"),
                    Record.windows(NAME_SUBFAMILY, "Italic")));
            equal("Sagarmatha Italic", FontNameReader.readDisplayName(f, "m.ttf"),
                    "dropping every style would lose the one that matters");
        });

        r.test("control characters and padding are cleaned out", () -> {
            File f = write(dir, "n.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "  Preeti\u0000\r\n   Bold  ")));
            equal("Preeti Bold", FontNameReader.readDisplayName(f, "n.ttf"),
                    "NULs, newlines and runs of spaces are not part of the name");
        });

        r.test("an absurdly long name is truncated rather than shown whole", () -> {
            String huge = "N".repeat(400);
            File f = write(dir, "o.ttf", Sfnt.font(Record.windows(NAME_FULL, huge)));
            String shown = FontNameReader.readDisplayName(f, "o.ttf");
            check(shown.length() <= 96,
                    "a 400-character name would push every other column off the table, got "
                            + shown.length());
        });

        // -- Falling back ------------------------------------------------------

        r.test("a file that is not a font falls back to its file name", () -> {
            File f = write(dir, "notafont.ttf", Sfnt.notAFont());
            equal("notafont", FontNameReader.readDisplayName(f, "notafont.ttf"),
                    "the extension is dropped and nothing is invented");
        });

        r.test("an empty file falls back to its file name", () -> {
            File f = write(dir, "empty.ttf", new byte[0]);
            equal("empty", FontNameReader.readDisplayName(f, "empty.ttf"),
                    "a zero-byte font is the one the ZIP export used to produce");
        });

        r.test("a truncated file falls back rather than throwing", () -> {
            byte[] whole = Sfnt.font(Record.windows(NAME_FULL, "Truncated"));
            byte[] half = new byte[whole.length / 2];
            System.arraycopy(whole, 0, half, 0, half.length);
            File f = write(dir, "half.ttf", half);
            equal("half", FontNameReader.readDisplayName(f, "half.ttf"),
                    "half a font is not a name");
        });

        r.test("a name table pointing past the end of the file falls back", () -> {
            File f = write(dir, "beyond.ttf", Sfnt.fontWithNameTableBeyondEof());
            equal("beyond", FontNameReader.readDisplayName(f, "beyond.ttf"),
                    "the directory is not trusted over the file's real length");
        });

        r.test("a name record pointing past the end of the file is skipped", () -> {
            File f = write(dir, "past.ttf", Sfnt.fontWithRecordPastEnd());
            equal("past", FontNameReader.readDisplayName(f, "past.ttf"),
                    "an out-of-range string offset yields no name, not stray bytes");
        });

        r.test("a name table with no records falls back", () -> {
            File f = write(dir, "norecords.ttf", Sfnt.fontWithEmptyNameTable());
            equal("norecords", FontNameReader.readDisplayName(f, "norecords.ttf"),
                    "an empty name table is a font that declares nothing");
        });

        r.test("a file that does not exist falls back to the supplied name", () -> {
            equal("ghost", FontNameReader.readDisplayName(new File(dir.toFile(), "ghost.ttf"), "ghost.ttf"),
                    "readDisplayName never returns null or blank");
        });

        r.test("a blank name inside the font does not win", () -> {
            File f = write(dir, "blank.ttf", Sfnt.font(
                    Record.windows(NAME_FULL, "   "),
                    Record.windows(NAME_FAMILY, "Rukmini")));
            equal("Rukmini", FontNameReader.readDisplayName(f, "blank.ttf"),
                    "a full name of spaces is not a usable name");
        });

        // -- Container variants ------------------------------------------------

        r.test("an OpenType (CFF) container is read the same way", () -> {
            File f = write(dir, "p.otf", Sfnt.font(OTTO, Record.windows(NAME_FULL, "Mukti Narrow")));
            equal("Mukti Narrow", FontNameReader.readDisplayName(f, "p.otf"),
                    "an OTTO tag is an sfnt too");
        });

        r.test("a font collection reads the first face", () -> {
            byte[] face1 = Sfnt.font(Record.windows(NAME_FULL, "FirstFace"));
            byte[] face2 = Sfnt.font(Record.windows(NAME_FULL, "SecondFace"));
            File f = write(dir, "q.ttc", Sfnt.collection(face1, face2));
            equal("FirstFace", FontNameReader.readDisplayName(f, "q.ttc"),
                    "table offsets inside a collection are absolute from the file start");
        });

        r.test("a collection declaring no faces falls back", () -> {
            File f = write(dir, "nofaces.ttc", Sfnt.emptyCollection());
            equal("nofaces", FontNameReader.readDisplayName(f, "nofaces.ttc"),
                    "faceCount of zero is not a font");
        });

        r.test("a Unicode-platform record is decoded as UTF-16BE", () -> {
            File f = write(dir, "uni.ttf", Sfnt.font(
                    new Record(PLATFORM_UNICODE, 3, 0, NAME_FULL, "Unicode Platform")));
            equal("Unicode Platform", FontNameReader.readDisplayName(f, "uni.ttf"),
                    "platform 0 strings are UTF-16BE, like platform 3");
        });

        r.test("a Macintosh-only font is still read", () -> {
            File f = write(dir, "maconly.ttf", Sfnt.font(
                    new Record(PLATFORM_MACINTOSH, 0, LANG_MAC_ENGLISH, NAME_FULL, "Mac Only")));
            equal("Mac Only", FontNameReader.readDisplayName(f, "maconly.ttf"),
                    "a legacy Mac record is worse than a Windows one but better than nothing");
        });

        // -- Caching -----------------------------------------------------------

        r.test("replacing a font on disk changes the name it shows", () -> {
            File f = write(dir, "swapped.ttf", Sfnt.font(Record.windows(NAME_FULL, "Before")));
            equal("Before", FontNameReader.readDisplayName(f, "swapped.ttf"), "first read");

            // Import overwrites a font in place, so the cache has to notice.
            // A same-millisecond rewrite would keep both size and mtime, which
            // is why the replacement is a different length and the timestamp is
            // moved explicitly.
            Files.write(f.toPath(), Sfnt.font(Record.windows(NAME_FULL, "After The Change")));
            check(f.setLastModified(f.lastModified() + 5000), "could not move the timestamp");

            equal("After The Change", FontNameReader.readDisplayName(f, "swapped.ttf"),
                    "a font replaced on disk must not be served from the cache");
        });

        r.test("reading the same unchanged font twice gives the same answer", () -> {
            File f = write(dir, "stable.ttf", Sfnt.font(Record.windows(NAME_FULL, "Stable")));
            equal("Stable", FontNameReader.readDisplayName(f, "stable.ttf"), "first read");
            equal("Stable", FontNameReader.readDisplayName(f, "stable.ttf"), "second read");
        });

        // -- stripFontExtension ------------------------------------------------

        r.test("stripFontExtension drops only font extensions", () -> {
            equal("Preeti", FontNameReader.stripFontExtension("Preeti.ttf"), "lower case ttf");
            equal("KANTI", FontNameReader.stripFontExtension("KANTI.TTF"), "upper case TTF");
            equal("Mukti", FontNameReader.stripFontExtension("Mukti.otf"), "otf");
            equal("Devanagari", FontNameReader.stripFontExtension("Devanagari.ttc"), "ttc");
            equal("notes.txt", FontNameReader.stripFontExtension("notes.txt"),
                    "a non-font extension is left alone");
            equal("Preeti.v2", FontNameReader.stripFontExtension("Preeti.v2"),
                    "a dotted name that is not a font extension is left alone");
            equal(".ttf", FontNameReader.stripFontExtension(".ttf"),
                    "a leading dot is part of the name, not an extension");
            equal("", FontNameReader.stripFontExtension(null), "null is not a crash");
            equal("", FontNameReader.stripFontExtension("  "), "blank is not a crash");
        });
    }
}
