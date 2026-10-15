package com.lipi.util;

import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the real, self-declared name of a font out of the font file itself.
 *
 * <p><b>Why this exists</b> - the file name is not the font name. Anyone can
 * rename {@code Preeti.ttf} to {@code nepali-font-final-v2.ttf}, and plenty of
 * the legacy Nepali fonts ship with names like {@code 4prrch_.ttf},
 * {@code KSN-21.TTF} or {@code Copy of RUKMINI.TTF} that say nothing useful
 * about what the font actually is. Deriving the displayed name by chopping the
 * extension off the file name therefore states something the app does not
 * actually know, and the two can disagree - which is exactly the confusion this
 * class removes. Windows, macOS and every font menu on the system identify a
 * font by the strings inside its {@code name} table, so that is what Lipi
 * shows too.</p>
 *
 * <p><b>How</b> - every TrueType/OpenType file is an sfnt container: a table
 * directory followed by tables, one of which ({@code 'name'}) holds the
 * human-readable strings, each tagged with a platform/encoding/language and a
 * name ID. This class seeks straight to that table and reads it, rather than
 * calling {@link Font#createFont}, because {@code createFont} parses and
 * registers the whole font and spills a temporary file per call - fine once,
 * far too slow when a library of 300+ fonts is scanned on every startup and
 * refresh. {@code createFont} is kept only as a fallback for files this parser
 * cannot make sense of.</p>
 *
 * <p>Results are cached per (path, size, last-modified), so a refresh that finds
 * unchanged files on disk costs nothing, while a file replaced on disk is
 * re-read rather than served stale.</p>
 */
public final class FontNameReader {

    private FontNameReader() {}

    // ── name IDs from the OpenType 'name' table specification ──────────────
    private static final int NAME_FAMILY         = 1;
    private static final int NAME_SUBFAMILY      = 2;
    private static final int NAME_FULL           = 4;
    private static final int NAME_TYPO_FAMILY    = 16;
    private static final int NAME_TYPO_SUBFAMILY = 17;

    // ── sfnt / table tags, as big-endian 4-byte integers ───────────────────
    private static final int TAG_TTCF   = 0x74746366; // 'ttcf' - font collection
    private static final int TAG_OTTO   = 0x4F54544F; // 'OTTO' - CFF outlines
    private static final int TAG_TRUE   = 0x74727565; // 'true' - legacy Mac
    private static final int TAG_TYP1   = 0x74797031; // 'typ1'
    private static final int TAG_SFNT_1 = 0x00010000; // TrueType outlines
    private static final int TAG_NAME   = 0x6E616D65; // 'name'

    /** Longest name we are willing to display; anything beyond this is malformed. */
    private static final int MAX_NAME_LENGTH = 96;

    private static final Map<String, Map<Integer, String>> CACHE = new HashMap<>();

    /**
     * The name to show the user for this font - the font's own full name
     * (e.g. "Preeti", "Kantipur Bold"), falling back to family + style, then to
     * whatever AWT can make of the file, then finally to {@code fallback} with
     * any font extension removed.
     *
     * <p>The fallback chain never returns null or blank, so callers can display
     * the result directly.</p>
     *
     * @param fontFile the font file to read
     * @param fallback file name to fall back on when the font declares no
     *                 usable name of its own (a damaged or non-sfnt file)
     */
    public static String readDisplayName(File fontFile, String fallback) {
        Map<Integer, String> names = namesFor(fontFile);

        // Every candidate goes through cleanLegacyName first. Fonts of this
        // vintage were routinely converted from Mac originals, and the
        // conversion copied the Mac-style full name - build timestamp and all -
        // into the Windows records too, so "which platform declared it" is no
        // guide to whether a string is presentable. ANNA.TTF in a typical
        // Nepali library calls itself "Annapurna (Plain):3/3/94 13:55:39" on
        // all three platforms; what the user wants to read is "Annapurna".
        String full   = cleanLegacyName(names.get(NAME_FULL));
        String family = cleanLegacyName(firstUsable(names.get(NAME_TYPO_FAMILY), names.get(NAME_FAMILY)));
        String style  = cleanLegacyName(firstUsable(names.get(NAME_TYPO_SUBFAMILY), names.get(NAME_SUBFAMILY)));

        if (isUsable(full)) return full;

        if (isUsable(family)) {
            if (isUsable(style) && !family.toLowerCase().endsWith(style.toLowerCase())) {
                return truncate(family + " " + style);
            }
            return family;
        }

        String awtFamily = readFamilyViaAwt(fontFile);
        if (isUsable(awtFamily)) return awtFamily;

        return stripFontExtension(fallback != null ? fallback : fontFile.getName());
    }

    /** Drops a trailing {@code .ttf}/{@code .otf}/{@code .ttc} (case-insensitive) and nothing else. */
    public static String stripFontExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            String ext = fileName.substring(dot).toLowerCase();
            if (ext.equals(".ttf") || ext.equals(".otf") || ext.equals(".ttc")) {
                return fileName.substring(0, dot);
            }
        }
        return fileName;
    }

    // ── Cache ──────────────────────────────────────────────────────────────

    private static synchronized Map<Integer, String> namesFor(File fontFile) {
        if (fontFile == null || !fontFile.isFile()) return Map.of();

        // Size and mtime are part of the key so a font replaced on disk under a
        // path already seen this session cannot be served from the cache.
        String key = fontFile.getAbsolutePath() + "|" + fontFile.length() + "|" + fontFile.lastModified();
        Map<Integer, String> cached = CACHE.get(key);
        if (cached != null) return cached;

        Map<Integer, String> names;
        try {
            names = parseNameTable(fontFile);
        } catch (Exception e) {
            // A font we cannot parse is not an error worth surfacing - the
            // caller has a fallback chain. Keep it out of the UI entirely.
            names = Map.of();
        }
        CACHE.put(key, names);
        return names;
    }

    // ── sfnt parsing ───────────────────────────────────────────────────────

    /**
     * Returns the best available string for each name ID we care about, or an
     * empty map if the file is not a readable sfnt.
     */
    private static Map<Integer, String> parseNameTable(File fontFile) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(fontFile, "r")) {
            long fileLength = raf.length();
            if (fileLength < 12) return Map.of();

            long sfntStart = 0;
            int tag = raf.readInt();

            if (tag == TAG_TTCF) {
                // Font collection: read the offset of the first face and treat
                // that as the sfnt header. Table offsets inside a collection are
                // absolute from the start of the file, not relative to the face.
                raf.readInt();                       // collection version
                int faceCount = raf.readInt();
                if (faceCount <= 0) return Map.of();
                sfntStart = Integer.toUnsignedLong(raf.readInt());
                if (sfntStart + 12 > fileLength) return Map.of();
                raf.seek(sfntStart);
                tag = raf.readInt();
            }

            if (tag != TAG_SFNT_1 && tag != TAG_OTTO && tag != TAG_TRUE && tag != TAG_TYP1) {
                return Map.of();
            }

            int tableCount = raf.readUnsignedShort();
            if (tableCount <= 0 || tableCount > 512) return Map.of();

            long directoryStart = sfntStart + 12;
            if (directoryStart + (16L * tableCount) > fileLength) return Map.of();

            long nameOffset = -1;
            long nameLength = 0;
            raf.seek(directoryStart);
            for (int i = 0; i < tableCount; i++) {
                int tableTag = raf.readInt();
                raf.readInt();                                       // checksum
                long offset = Integer.toUnsignedLong(raf.readInt());
                long length = Integer.toUnsignedLong(raf.readInt());
                if (tableTag == TAG_NAME) {
                    nameOffset = offset;
                    nameLength = length;
                    break;
                }
            }

            if (nameOffset < 0 || nameLength < 6 || nameOffset + 6 > fileLength) return Map.of();

            raf.seek(nameOffset);
            raf.readUnsignedShort();                                 // format (0 or 1)
            int recordCount  = raf.readUnsignedShort();
            int storageStart = raf.readUnsignedShort();
            if (recordCount <= 0) return Map.of();

            long recordsStart = nameOffset + 6;
            if (recordsStart + (12L * recordCount) > fileLength) return Map.of();

            // Several records can carry the same name ID for different platforms
            // and languages; keep the highest-scoring one so a Windows/English
            // string wins over, say, a legacy Macintosh one.
            Map<Integer, String>  best      = new LinkedHashMap<>();
            Map<Integer, Integer> bestScore = new HashMap<>();

            for (int i = 0; i < recordCount; i++) {
                raf.seek(recordsStart + (12L * i));
                int platformId = raf.readUnsignedShort();
                int encodingId = raf.readUnsignedShort();
                int languageId = raf.readUnsignedShort();
                int nameId     = raf.readUnsignedShort();
                int length     = raf.readUnsignedShort();
                int offset     = raf.readUnsignedShort();

                if (!isWanted(nameId) || length <= 0) continue;

                long stringStart = nameOffset + storageStart + offset;
                if (stringStart < 0 || stringStart + length > fileLength) continue;

                int score = score(platformId, encodingId, languageId);
                Integer existing = bestScore.get(nameId);
                if (existing != null && existing >= score) continue;

                byte[] raw = new byte[length];
                raf.seek(stringStart);
                raf.readFully(raw);

                String value = sanitize(new String(raw, charsetFor(platformId, encodingId)));
                if (!isUsable(value)) continue;

                best.put(nameId, value);
                bestScore.put(nameId, score);
            }

            return best;
        }
    }

    private static boolean isWanted(int nameId) {
        return nameId == NAME_FAMILY || nameId == NAME_SUBFAMILY || nameId == NAME_FULL
                || nameId == NAME_TYPO_FAMILY || nameId == NAME_TYPO_SUBFAMILY;
    }

    /**
     * Ranks a name record. Windows/Unicode/US-English is the string every other
     * font menu on Windows shows, so it ranks highest; Macintosh records rank
     * last because their text is legacy-encoded and often abbreviated.
     */
    private static int score(int platformId, int encodingId, int languageId) {
        if (platformId == 3 && encodingId == 1 && languageId == 0x0409) return 100; // Windows, Unicode BMP, en-US
        if (platformId == 3 && encodingId == 0 && languageId == 0x0409) return 95;  // Windows, symbol, en-US
        if (platformId == 3 && encodingId == 1)                         return 80;  // Windows, Unicode BMP, other language
        if (platformId == 3)                                            return 70;
        if (platformId == 0)                                            return 60;  // Unicode platform
        if (platformId == 1 && languageId == 0)                         return 50;  // Macintosh, English
        if (platformId == 1)                                            return 40;
        return 10;
    }

    private static Charset charsetFor(int platformId, int encodingId) {
        // Windows (3) and Unicode (0) name strings are UTF-16BE, including under
        // the symbol encoding - encoding ID 0 on platform 3 changes how the cmap
        // is read, not how these strings are stored.
        if (platformId == 3 || platformId == 0) return StandardCharsets.UTF_16BE;
        if (platformId == 1 && encodingId == 0) {
            try {
                return Charset.forName("x-MacRoman");
            } catch (Exception e) {
                return StandardCharsets.ISO_8859_1;
            }
        }
        return StandardCharsets.ISO_8859_1;
    }

    // ── Fallback + tidying ─────────────────────────────────────────────────

    private static String readFamilyViaAwt(File fontFile) {
        try {
            return sanitize(Font.createFont(Font.TRUETYPE_FONT, fontFile).getFamily());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strips the decoration old Mac font tools left in these names: a build
     * timestamp after a colon, and a style marker carrying no information
     * ("Plain", "Regular", "Normal", with or without parentheses).
     *
     * <p>Returns null when nothing survives, which is the useful answer: a
     * subfamily of "(Plain)" means there is no style worth appending, and the
     * caller then shows the family name on its own.</p>
     */
    private static String cleanLegacyName(String value) {
        if (!isUsable(value)) return null;

        String cleaned = value;
        int colon = cleaned.indexOf(':');
        if (colon > 0) cleaned = cleaned.substring(0, colon);

        cleaned = cleaned.replaceAll("(?i)\\(\\s*(plain|regular|normal)\\s*\\)", "");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();

        if (cleaned.equalsIgnoreCase("plain")
                || cleaned.equalsIgnoreCase("regular")
                || cleaned.equalsIgnoreCase("normal")) {
            return null;
        }
        return isUsable(cleaned) ? cleaned : null;
    }

    /** Strips control characters, collapses runs of whitespace, trims and truncates. */
    private static String sanitize(String value) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder(value.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000') continue;
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (out.length() > 0 && !lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            out.append(c);
            lastWasSpace = false;
        }
        return truncate(out.toString().trim());
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_NAME_LENGTH ? value : value.substring(0, MAX_NAME_LENGTH).trim();
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstUsable(String... candidates) {
        for (String candidate : candidates) {
            if (isUsable(candidate)) return candidate;
        }
        return null;
    }
}
