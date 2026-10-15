package com.lipi.build;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-time utility: converts PNG icon files into a single multi-resolution
 * Windows ICO file.
 *
 * Usage: java ConvertIcon <source-icons-dir> <output-ico-file>
 *
 * The ICO format stores a small directory followed by the raw PNG or BMP data
 * for each size. Modern Windows (Vista+) supports PNG-compressed entries
 * directly inside an ICO, which is what we write here — no BMP conversion
 * needed.
 *
 * ICO binary layout (all integers little-endian):
 *   ICONDIR       6 bytes   (reserved=0, type=1, count=N)
 *   ICONDIRENTRY  16 bytes  × N
 *   image data    variable  × N  (raw PNG bytes)
 *
 * ICONDIRENTRY:
 *   BYTE  width        (0 means 256)
 *   BYTE  height       (0 means 256)
 *   BYTE  colorCount   (0 for true-color)
 *   BYTE  reserved     (0)
 *   WORD  planes       (1)
 *   WORD  bitCount     (32 for RGBA)
 *   DWORD sizeInBytes
 *   DWORD fileOffset
 */
public class ConvertIcon {

    // Sizes to include in the ICO, in the order they appear in the directory.
    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: ConvertIcon <icons-dir> <output.ico>");
            System.exit(1);
        }

        File iconsDir  = new File(args[0]);
        File outputIco = new File(args[1]);

        List<byte[]> pngChunks = new ArrayList<>();

        for (int size : SIZES) {
            byte[] png = loadOrScale(iconsDir, size);
            if (png != null) {
                pngChunks.add(png);
            }
        }

        if (pngChunks.isEmpty()) {
            System.err.println("No PNG sources found in " + iconsDir.getAbsolutePath());
            System.exit(1);
        }

        writeIco(pngChunks, outputIco);
        System.out.println("Written: " + outputIco.getAbsolutePath() +
                           " (" + pngChunks.size() + " sizes)");
    }

    /**
     * Tries to load an exact-match PNG (e.g. icon-32.png) from the icons
     * directory. Falls back to scaling icon.png or the largest available file
     * if no exact match exists.
     */
    private static byte[] loadOrScale(File iconsDir, int size) throws Exception {
        // Try exact-match filenames
        String[] candidates = {
            "icon-" + size + ".png",
            "icon" + size + ".png"
        };

        File source = null;
        for (String name : candidates) {
            File f = new File(iconsDir, name);
            if (f.exists()) { source = f; break; }
        }

        // Fall back to icon.png and scale it
        if (source == null) {
            File fallback = new File(iconsDir, "icon.png");
            if (!fallback.exists()) return null;
            source = fallback;
        }

        BufferedImage img = ImageIO.read(source);
        if (img == null) return null;

        if (img.getWidth() != size || img.getHeight() != size) {
            // Scale to the required size using high-quality rendering
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                               RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, size, size, null);
            g.dispose();
            img = scaled;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Writes the ICO file containing all provided PNG chunks.
     */
    private static void writeIco(List<byte[]> chunks, File output) throws Exception {
        int count = chunks.size();

        // Compute data offsets: directory starts at byte 6,
        // each entry is 16 bytes, image data follows immediately after.
        int dataOffset = 6 + count * 16;

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);

        // ICONDIR header (little-endian)
        writeShortLE(dos, 0);      // reserved
        writeShortLE(dos, 1);      // type = ICO
        writeShortLE(dos, count);  // number of images

        // ICONDIRENTRY array
        int offset = dataOffset;
        for (int i = 0; i < count; i++) {
            byte[] png = chunks.get(i);
            // Determine the actual size by peeking at the PNG IHDR
            int[] dims = readPngDimensions(png);
            int w = dims[0];
            int h = dims[1];

            dos.write(w >= 256 ? 0 : w);   // width  (0 = 256)
            dos.write(h >= 256 ? 0 : h);   // height (0 = 256)
            dos.write(0);                   // colorCount (true-color)
            dos.write(0);                   // reserved
            writeShortLE(dos, 1);           // planes
            writeShortLE(dos, 32);          // bit count
            writeIntLE(dos, png.length);    // size in bytes
            writeIntLE(dos, offset);        // file offset
            offset += png.length;
        }

        // Image data
        for (byte[] png : chunks) {
            dos.write(png);
        }

        dos.flush();

        output.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(buf.toByteArray());
        }
    }

    /** Reads width and height from a PNG's IHDR chunk (bytes 16–23). */
    private static int[] readPngDimensions(byte[] png) {
        ByteBuffer bb = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN);
        return new int[]{ bb.getInt(), bb.getInt() };
    }

    private static void writeShortLE(DataOutputStream dos, int value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
    }

    private static void writeIntLE(DataOutputStream dos, int value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
        dos.write((value >> 16) & 0xFF);
        dos.write((value >> 24) & 0xFF);
    }
}
