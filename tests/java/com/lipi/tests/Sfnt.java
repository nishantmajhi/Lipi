package com.lipi.tests;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds TrueType/OpenType files byte by byte, so a test can state exactly
 * what is inside a font and then assert on the name Lipi shows for it.
 *
 * <p>Real font files cannot do this job. The fonts in {@code fonts/} are a
 * fixed set nobody controls: to test that a Macintosh record loses to a
 * Windows one you need a font carrying both, saying different things, and no
 * amount of searching a font library guarantees one exists. Building the
 * container here makes the input to every assertion explicit and lets the
 * suite cover the cases that matter and never occur in practice - a name
 * table pointing past the end of the file, a zero-length string, a collection
 * with no faces - which are exactly the ones a parser gets wrong.</p>
 *
 * <p>Only the {@code name} table is real. Nothing in Lipi's own parser reads
 * any other table, so the rest of the container is the minimum that makes the
 * file a well-formed sfnt. Where a test needs AWT to accept the file as well,
 * it uses one of the project's own fonts instead and says so.</p>
 */
public final class Sfnt {

    /** sfnt version for TrueType outlines. */
    public static final int TRUETYPE = 0x00010000;
    /** sfnt version for CFF (OpenType) outlines. */
    public static final int OTTO     = 0x4F54544F;

    // Platform IDs from the OpenType specification.
    public static final int PLATFORM_UNICODE   = 0;
    public static final int PLATFORM_MACINTOSH = 1;
    public static final int PLATFORM_WINDOWS   = 3;

    public static final int LANG_MAC_ENGLISH = 0;
    public static final int LANG_WIN_EN_US   = 0x0409;
    public static final int LANG_WIN_NEPALI  = 0x0461;

    public static final int NAME_FAMILY         = 1;
    public static final int NAME_SUBFAMILY      = 2;
    public static final int NAME_FULL           = 4;
    public static final int NAME_TYPO_FAMILY    = 16;
    public static final int NAME_TYPO_SUBFAMILY = 17;

    private static final int TAG_NAME = 0x6E616D65;
    private static final int TAG_CVT  = 0x63767420;
    private static final int TAG_TTCF = 0x74746366;

    private Sfnt() {}

    /** One row of the name table. */
    public record Record(int platformId, int encodingId, int languageId, int nameId, String value) {

        /** A Windows / Unicode BMP / US-English record - what every font menu on Windows shows. */
        public static Record windows(int nameId, String value) {
            return new Record(PLATFORM_WINDOWS, 1, LANG_WIN_EN_US, nameId, value);
        }

        /** A legacy Macintosh / Roman / English record. */
        public static Record mac(int nameId, String value) {
            return new Record(PLATFORM_MACINTOSH, 0, LANG_MAC_ENGLISH, nameId, value);
        }

        byte[] encode() {
            return value.getBytes(charset());
        }

        Charset charset() {
            if (platformId == PLATFORM_WINDOWS || platformId == PLATFORM_UNICODE) {
                return StandardCharsets.UTF_16BE;
            }
            if (platformId == PLATFORM_MACINTOSH && encodingId == 0) {
                try {
                    return Charset.forName("x-MacRoman");
                } catch (Exception e) {
                    return StandardCharsets.ISO_8859_1;
                }
            }
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** A complete, well-formed single-face font carrying exactly {@code records}. */
    public static byte[] font(Record... records) {
        return container(TRUETYPE, nameTable(records), 0);
    }

    /** As {@link #font(Record...)} but with a chosen sfnt version tag. */
    public static byte[] font(int sfntVersion, Record... records) {
        return container(sfntVersion, nameTable(records), 0);
    }

    /**
     * A font whose {@code name} table entry in the directory claims an offset
     * that runs past the end of the file. Real fonts are truncated by failed
     * downloads and by the ZIP import path, and a parser that trusts the
     * directory reads whatever follows in memory or throws.
     */
    public static byte[] fontWithNameTableBeyondEof() {
        byte[] whole = font(Record.windows(NAME_FULL, "Ignored"));
        byte[] copy = whole.clone();
        // Table directory entry 0 is 'name' (see container below); its offset
        // field sits 8 bytes into the entry, which itself starts at byte 12.
        writeInt(copy, 12 + 8, 0x7F000000);
        return copy;
    }

    /** A font collection ('ttcf') wrapping {@code faces}, first face first. */
    public static byte[] collection(byte[]... faces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int headerLength = 12 + (4 * faces.length);

        // Offsets are absolute from the start of the collection file.
        int[] offsets = new int[faces.length];
        int running = headerLength;
        for (int i = 0; i < faces.length; i++) {
            offsets[i] = running;
            running += faces[i].length;
        }

        writeInt(out, TAG_TTCF);
        writeInt(out, 0x00010000);        // collection version 1.0
        writeInt(out, faces.length);
        for (int offset : offsets) writeInt(out, offset);

        // A face's table directory holds absolute offsets, so each face has to
        // be relocated by the distance it moved from the start of the file.
        for (int i = 0; i < faces.length; i++) {
            out.writeBytes(relocate(faces[i], offsets[i]));
        }
        return out.toByteArray();
    }

    /** A collection header that declares no faces at all. */
    public static byte[] emptyCollection() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, TAG_TTCF);
        writeInt(out, 0x00010000);
        writeInt(out, 0);                 // faceCount = 0
        writeInt(out, 0);
        return out.toByteArray();
    }

    /** Bytes that are not an sfnt at all. */
    public static byte[] notAFont() {
        return "this is a text file, not a font, whatever its extension says"
                .getBytes(StandardCharsets.UTF_8);
    }

    // -- Building blocks ---------------------------------------------------

    /** Builds a {@code name} table holding {@code records}, in format 0. */
    public static byte[] nameTable(Record... records) {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        List<int[]> placed = new ArrayList<>();   // {offsetIntoStorage, length}

        for (Record r : records) {
            byte[] encoded = r.encode();
            placed.add(new int[]{ strings.size(), encoded.length });
            strings.writeBytes(encoded);
        }

        int storageStart = 6 + (12 * records.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0);                        // format 0
        writeShort(out, records.length);
        writeShort(out, storageStart);
        for (int i = 0; i < records.length; i++) {
            Record r = records[i];
            int[] where = placed.get(i);
            writeShort(out, r.platformId());
            writeShort(out, r.encodingId());
            writeShort(out, r.languageId());
            writeShort(out, r.nameId());
            writeShort(out, where[1]);             // length
            writeShort(out, where[0]);             // offset from storageStart
        }
        out.writeBytes(strings.toByteArray());
        return out.toByteArray();
    }

    /**
     * A font whose single name record points at a string starting beyond the
     * end of the file. The parser is expected to skip the record rather than
     * read something that is not the string.
     */
    public static byte[] fontWithRecordPastEnd() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0);
        writeShort(out, 1);
        writeShort(out, 18);                       // storageStart
        writeShort(out, PLATFORM_WINDOWS);
        writeShort(out, 1);
        writeShort(out, LANG_WIN_EN_US);
        writeShort(out, NAME_FULL);
        writeShort(out, 40);                       // length
        writeShort(out, 0xF000);                   // offset - far past the end
        return container(TRUETYPE, out.toByteArray(), 0);
    }

    /** A font whose name table declares zero records. */
    public static byte[] fontWithEmptyNameTable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0);
        writeShort(out, 0);                        // count = 0
        writeShort(out, 6);
        return container(TRUETYPE, out.toByteArray(), 0);
    }

    /**
     * Wraps {@code nameTable} in an sfnt container. {@code extraTables} adds
     * that many filler entries after the name table, so a test can vary the
     * directory without moving the name entry.
     */
    public static byte[] container(int sfntVersion, byte[] nameTable, int extraTables) {
        int tableCount = 1 + extraTables;
        int directoryBytes = 16 * tableCount;
        int bodyStart = 12 + directoryBytes;

        byte[] filler = new byte[16];              // contents irrelevant; never parsed

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, sfntVersion);
        writeShort(out, tableCount);
        writeShort(out, 0);                        // searchRange   - unread by Lipi
        writeShort(out, 0);                        // entrySelector - unread by Lipi
        writeShort(out, 0);                        // rangeShift    - unread by Lipi

        int offset = bodyStart;
        // 'name' goes first so fontWithNameTableBeyondEof knows where its
        // offset field is.
        writeInt(out, TAG_NAME);
        writeInt(out, 0);                          // checksum - unread by Lipi
        writeInt(out, offset);
        writeInt(out, nameTable.length);
        offset += nameTable.length;

        for (int i = 0; i < extraTables; i++) {
            writeInt(out, TAG_CVT);
            writeInt(out, 0);
            writeInt(out, offset);
            writeInt(out, filler.length);
            offset += filler.length;
        }

        out.writeBytes(nameTable);
        for (int i = 0; i < extraTables; i++) out.writeBytes(filler);
        return out.toByteArray();
    }

    /**
     * Shifts every table offset in a face's directory by {@code base}, which is
     * what makes a face valid once it has been placed inside a collection.
     */
    private static byte[] relocate(byte[] face, int base) {
        byte[] copy = face.clone();
        int tableCount = readShort(copy, 4);
        for (int i = 0; i < tableCount; i++) {
            int offsetField = 12 + (16 * i) + 8;
            writeInt(copy, offsetField, readInt(copy, offsetField) + base);
        }
        return copy;
    }

    // -- Primitives --------------------------------------------------------

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(byte[] buffer, int at, int value) {
        buffer[at]     = (byte) ((value >>> 24) & 0xFF);
        buffer[at + 1] = (byte) ((value >>> 16) & 0xFF);
        buffer[at + 2] = (byte) ((value >>> 8) & 0xFF);
        buffer[at + 3] = (byte) (value & 0xFF);
    }

    private static int readInt(byte[] buffer, int at) {
        return ((buffer[at] & 0xFF) << 24) | ((buffer[at + 1] & 0xFF) << 16)
                | ((buffer[at + 2] & 0xFF) << 8) | (buffer[at + 3] & 0xFF);
    }

    private static int readShort(byte[] buffer, int at) {
        return ((buffer[at] & 0xFF) << 8) | (buffer[at + 1] & 0xFF);
    }
}
