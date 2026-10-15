package com.lipi.model;

import com.lipi.util.FontNameReader;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;

/**
 * Data model representing a font file with its properties and installation status.
 * JavaFX observable properties replaced with standard Java beans PropertyChangeSupport.
 */
public class FontInfo {

    private boolean selected;
    private final String name;
    private final String filePath;
    private final String fileName;
    private String status;
    private boolean installed;

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    /**
     * Reads the font's own name out of the file. The display name is
     * deliberately NOT derived from the file name: the two are independent, a
     * file can be renamed to anything, and several of the fonts in a typical
     * Nepali library ship as {@code 4prrch_.ttf} or {@code KSN-21.TTF}, which
     * would tell the user nothing. See {@link FontNameReader}. The file name is
     * still kept and shown in its own column, so a mismatch is visible rather
     * than hidden.
     */
    public FontInfo(String fileName, String filePath, boolean installed) {
        this(fileName, filePath, installed,
             FontNameReader.readDisplayName(new File(filePath), fileName));
    }

    /**
     * Variant that takes an already-resolved display name, for callers that
     * have read the font file themselves and want to avoid a second read.
     */
    public FontInfo(String fileName, String filePath, boolean installed, String name) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.name = (name == null || name.isBlank())
                ? FontNameReader.stripFontExtension(fileName)
                : name;
        this.installed = installed;
        this.status = installed ? "Installed" : "Not Installed";
        this.selected = false;
    }

    // ── selected ──────────────────────────────────────────────────────────────

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value) {
        boolean old = this.selected;
        this.selected = value;
        pcs.firePropertyChange("selected", old, value);
    }

    // ── name (read-only) ──────────────────────────────────────────────────────

    /**
     * The font's real name as declared inside the font file (e.g. "Preeti"),
     * not the file name with its extension chopped off.
     */
    public String getName() {
        return name;
    }

    // ── filePath (read-only) ──────────────────────────────────────────────────

    public String getFilePath() {
        return filePath;
    }

    // ── fileName (read-only) ──────────────────────────────────────────────────

    public String getFileName() {
        return fileName;
    }

    // ── status ────────────────────────────────────────────────────────────────

    public String getStatus() {
        return status;
    }

    // ── installed ─────────────────────────────────────────────────────────────

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        boolean old = this.installed;
        this.installed = installed;
        this.status = installed ? "Installed" : "Not Installed";
        pcs.firePropertyChange("installed", old, installed);
    }

    // ── PropertyChange support ────────────────────────────────────────────────

    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }
}
