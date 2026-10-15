package com.lipi.ui;

import com.lipi.model.FontInfo;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.List;

/**
 * Swing JTable wrapper for displaying font information.
 * Replaces the JavaFX FontTableView / TableView.
 *
 * Layout note: callers should embed this inside a JScrollPane.
 */
public class FontTableView extends JTable {

    private static final Color COLOR_INSTALLED     = new Color(0x27, 0xAE, 0x60);
    private static final Color COLOR_NOT_INSTALLED = new Color(0xE7, 0x4C, 0x3C);

    private final FontTableModel model;

    public FontTableView() {
        model = new FontTableModel();
        setModel(model);
        configureTable();
        configureColumns();
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    private void configureTable() {
        setBackground(Color.WHITE);
        setGridColor(new Color(0xDC, 0xDC, 0xDC));
        setRowHeight(26);
        setFillsViewportHeight(true);
        // AUTO_RESIZE_ALL_COLUMNS lets the Name/Status/File Name columns grow and
        // shrink proportionally as the window is resized. The Select column stays
        // pinned at a fixed width below since its min == max == preferred width.
        setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        getTableHeader().setReorderingAllowed(false);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    private void configureColumns() {
        // Select column - fixed width, never resizes
        TableColumn selectCol = getColumnModel().getColumn(FontTableModel.COL_SELECTED);
        selectCol.setPreferredWidth(60);
        selectCol.setMaxWidth(60);
        selectCol.setMinWidth(60);

        // Font name column - flexible, but never shrinks below a usable width
        TableColumn nameCol = getColumnModel().getColumn(FontTableModel.COL_NAME);
        nameCol.setPreferredWidth(280);
        nameCol.setMinWidth(120);

        // Status column - custom renderer for coloured text
        TableColumn statusCol = getColumnModel().getColumn(FontTableModel.COL_STATUS);
        statusCol.setPreferredWidth(110);
        statusCol.setMinWidth(90);
        statusCol.setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {

                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                // Weight encodes the status and must not depend on selection.
                // Previously a selected row dropped back to plain, so clicking
                // a row silently changed what its Status cell was saying - the
                // bold that means "Installed" vanished on the one row the user
                // was looking at. Selection changes the colour only; the
                // selection foreground already supplies the needed contrast.
                boolean installed = "Installed".equals(value);
                setFont(getFont().deriveFont(installed ? Font.BOLD : Font.PLAIN));

                if (isSelected) {
                    setForeground(table.getSelectionForeground());
                } else {
                    setForeground(installed ? COLOR_INSTALLED : COLOR_NOT_INSTALLED);
                }
                return this;
            }
        });

        // File name column - flexible
        TableColumn fileNameCol = getColumnModel().getColumn(FontTableModel.COL_FILENAME);
        fileNameCol.setPreferredWidth(330);
        fileNameCol.setMinWidth(120);
    }

    // ── Public API mirroring the old JavaFX FontTableView ─────────────────

    /** Returns the full (unfiltered) master list of all fonts. */
    public List<FontInfo> getFontList() {
        return model.getMasterList();
    }

    /** Returns only the currently visible (filtered) rows. */
    public List<FontInfo> getFilteredFontList() {
        return model.getVisibleList();
    }

    /** Replaces the entire font list. */
    public void setFonts(List<FontInfo> fonts) {
        model.setFonts(fonts);
    }

    /**
     * Returns every selected font in the library, including rows the search
     * filter is currently hiding. This is the list every install/remove/export
     * action operates on, so callers must be prepared to tell the user about
     * the off-screen part of it - see {@link #getHiddenSelectedCount()}.
     */
    public List<FontInfo> getSelectedFonts() {
        return model.getSelectedFonts();
    }

    /** Number of selected fonts across the whole library, visible or not. */
    public int getSelectedCount() {
        return model.getSelectedCount();
    }

    /** Number of selected fonts currently hidden by the search filter. */
    public int getHiddenSelectedCount() {
        return model.getHiddenSelectedCount();
    }

    /** Clears the selection on every font, including rows hidden by the filter. */
    public void clearAllSelections() {
        model.clearAllSelections();
    }

    /** Filters the visible rows by search text (name or filename). */
    public void filterFonts(String searchText) {
        if (searchText == null || searchText.isBlank()) {
            model.setFilter(null);
        } else {
            String lower = searchText.toLowerCase().trim();
            model.setFilter(f ->
                    f.getName().toLowerCase().contains(lower) ||
                    f.getFileName().toLowerCase().contains(lower));
        }
    }

    /** Forces the table to repaint all rows (e.g. after bulk select/deselect). */
    public void refreshAll() {
        model.refreshAll();
    }

    /**
     * Returns the FontInfo backing the given view row, or null if rowIndex is
     * out of range (e.g. the click landed below the last row). There is no
     * row sorter on this table, so the view row index maps directly onto the
     * visible (filtered) list's index.
     */
    public FontInfo getFontAt(int rowIndex) {
        List<FontInfo> visible = model.getVisibleList();
        if (rowIndex < 0 || rowIndex >= visible.size()) return null;
        return visible.get(rowIndex);
    }
}
