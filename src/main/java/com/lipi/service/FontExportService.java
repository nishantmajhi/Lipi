package com.lipi.service;

import com.lipi.model.FontInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Service class for font import/export operations.
 */
public class FontExportService {

    private static final int BUFFER_SIZE = 8192;

    private final String fontsDirectory;

    public FontExportService(String fontsDirectory) {
        this.fontsDirectory = fontsDirectory;
    }

    /**
     * Exports selected fonts as separate files to a directory.
     */
    public ExportResult exportAsSeparateFiles(List<FontInfo> fonts, File exportDir) {
        int exported = 0;
        int failed = 0;

        for (FontInfo font : fonts) {
            try {
                File sourceFile = new File(font.getFilePath());
                File targetFile = new File(exportDir, font.getFileName());
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                exported++;
            } catch (Exception e) {
                System.err.println("Failed to export font: " + font.getFileName() + " - " + e.getMessage());
                failed++;
            }
        }

        return new ExportResult(exported, failed);
    }

    /**
     * Exports selected fonts as a ZIP archive.
     */
    public ExportResult exportAsZip(List<FontInfo> fonts, File zipFile) {
        int exported = 0;
        int failed = 0;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (FontInfo font : fonts) {
                try {
                    File sourceFile = new File(font.getFilePath());

                    // Read the whole font before starting its entry, so that a
                    // font which cannot be read never begins one.
                    //
                    // Starting the entry first - which is how this read - left
                    // an entry open whenever the source turned out to be
                    // unreadable, and nothing closed it: the next
                    // putNextEntry closed it instead, and the archive ended up
                    // carrying a zero-byte .ttf for the font the dialog had
                    // just reported as failed. Windows refuses to install one
                    // of those, so the user was left holding a file that looks
                    // like a font, came from Lipi, and cannot work.
                    //
                    // Reading into memory rather than streaming also makes the
                    // failure atomic: a read that dies half way through writes
                    // nothing, instead of leaving a truncated entry that no
                    // count can describe. A font is tens of kilobytes and only
                    // one is held at a time.
                    byte[] contents = Files.readAllBytes(sourceFile.toPath());

                    zos.putNextEntry(new ZipEntry(font.getFileName()));
                    zos.write(contents);
                    zos.closeEntry();

                    exported++;
                } catch (Exception e) {
                    System.err.println("Failed to add font to ZIP: " + font.getFileName() + " - " + e.getMessage());
                    failed++;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to create ZIP file: " + e.getMessage());
            return new ExportResult(0, fonts.size());
        }

        return new ExportResult(exported, failed);
    }

    /**
     * Adds font files to the fonts directory.
     */
    public ExportResult addFontFiles(List<File> fontFiles) {
        int copied = 0;
        int failed = 0;

        File fontsDir = new File(fontsDirectory);
        if (!fontsDir.exists()) {
            fontsDir.mkdirs();
        }

        for (File file : fontFiles) {
            try {
                File targetFile = new File(fontsDir, file.getName());
                Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (Exception e) {
                System.err.println("Failed to copy font file: " + file.getName() + " - " + e.getMessage());
                failed++;
            }
        }

        return new ExportResult(copied, failed);
    }

    /**
     * Extracts font files from a ZIP archive to the fonts directory.
     * Includes zip-slip protection via canonical path verification.
     *
     * <p>The count that comes back is the number of fonts actually written,
     * including when the archive turns out to be damaged part of the way
     * through. That is not bookkeeping: the window shows "No Fonts Found - no
     * .ttf or .otf font files were found in the ZIP archive" whenever the
     * count is zero, and it only refreshes the table when the count is
     * positive. Returning zero after writing fonts to disk therefore told the
     * user the opposite of what had happened and then hid the result until the
     * next launch.</p>
     */
    public ExportResult extractFromZip(File zipFile) {
        int extracted = 0;
        int failed = 0;

        try {
            File fontsDir = new File(fontsDirectory);
            if (!fontsDir.exists()) {
                fontsDir.mkdirs();
            }

            // A file that is not an archive at all reads as an archive with no
            // entries, which is indistinguishable from a ZIP full of things
            // that are not fonts - and the user is then told their fonts
            // could not be found in a file that was never a ZIP. Checking the
            // signature is what tells the two apart.
            if (!looksLikeZip(zipFile)) {
                System.err.println("Not a ZIP archive: " + zipFile);
                return new ExportResult(0, 1);
            }

            String canonicalFontsDir = fontsDir.getCanonicalPath();

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String fileName = new File(entry.getName()).getName();

                    if (!entry.isDirectory() &&
                            (fileName.toLowerCase().endsWith(".ttf") ||
                             fileName.toLowerCase().endsWith(".otf"))) {

                        File targetFile = new File(fontsDir, fileName);

                        // Zip-slip guard
                        String canonicalTarget = targetFile.getCanonicalPath();
                        if (!canonicalTarget.startsWith(canonicalFontsDir + File.separator)
                                && !canonicalTarget.equals(canonicalFontsDir)) {
                            System.err.println("Zip slip attempt blocked for entry: " + entry.getName());
                            zis.closeEntry();
                            continue;
                        }

                        try {
                            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                                byte[] buffer = new byte[BUFFER_SIZE];
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            extracted++;
                        } catch (Exception entryFailed) {
                            // A truncated archive dies part-way through an
                            // entry, leaving a half-written font behind.
                            // Deleting it matters: a partial .ttf still
                            // installs and then renders nothing, which is a
                            // worse outcome than the import plainly failing.
                            System.err.println("Failed to extract " + fileName + ": "
                                    + entryFailed.getMessage());
                            if (targetFile.exists() && !targetFile.delete()) {
                                System.err.println("Could not remove the partial file " + targetFile);
                            }
                            failed++;
                            throw entryFailed;
                        }
                    }
                    zis.closeEntry();
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to extract ZIP file: " + e.getMessage());
            // Keep the count of what really was written. The fonts that landed
            // before the failure are on disk whatever this returns.
            return new ExportResult(extracted, Math.max(failed, 1));
        }

        return new ExportResult(extracted, failed);
    }

    /**
     * True when {@code file} starts with one of the ZIP signatures: a local
     * file header, or the end-of-central-directory record that is all an empty
     * archive contains.
     */
    private static boolean looksLikeZip(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] signature = in.readNBytes(4);
            if (signature.length < 4) return false;
            if (signature[0] != 'P' || signature[1] != 'K') return false;
            return (signature[2] == 3 && signature[3] == 4)      // local file header
                    || (signature[2] == 5 && signature[3] == 6)  // empty archive
                    || (signature[2] == 7 && signature[3] == 8); // spanned archive
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Result of export/import operations.
     */
    public static class ExportResult {
        private final int successCount;
        private final int failedCount;

        public ExportResult(int successCount, int failedCount) {
            this.successCount = successCount;
            this.failedCount = failedCount;
        }

        public int getSuccessCount() { return successCount; }
        public int getFailedCount()  { return failedCount; }
    }
}
