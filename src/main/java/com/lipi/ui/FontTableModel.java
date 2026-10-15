package com.lipi.ui;

import com.lipi.model.FontInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Swing TableModel for FontInfo rows.
 *
 * Replaces the JavaFX ObservableList + FilteredList combination.
 * The master list holds all fonts; the visible list is rebuilt whenever
 * the filter predicate changes.
 */
public class FontTableModel extends AbstractTableModel {

    // Column indices
    public static final int COL_SELECTED = 0;
    public static final int COL_NAME     = 1;
    public static final int COL_STATUS   = 2;
    public static final int COL_FILENAME = 3;

    private static final String[] COLUMN_NAMES = {"Select", "Font", "Status", "File Name"};

    private final List<FontInfo> masterList  = new ArrayList<>();
    private final List<FontInfo> visibleList = new ArrayList<>();
    private Predicate<FontInfo> filter = f -> true;

    // ── TableModel ─────────────────────────────────────────────────────────

    @Override public int getRowCount()    { return visibleList.size(); }
    @Override public int getColumnCount() { return COLUMN_NAMES.length; }
    @Override public String getColumnName(int col) { return COLUMN_NAMES[col]; }

    @Override
    public Class<?> getColumnClass(int col) {
        return col == COL_SELECTED ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return col == COL_SELECTED;
    }

    @Override
    public Object getValueAt(int row, int col) {
        FontInfo f = visibleList.get(row);
        return switch (col) {
            case COL_SELECTED -> f.isSelected();
            case COL_NAME     -> f.getName();
            case COL_STATUS   -> f.getStatus();
            case COL_FILENAME -> f.getFileName();
            default           -> null;
        };
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        if (col == COL_SELECTED && value instanceof Boolean b) {
            visibleList.get(row).setSelected(b);
            fireTableCellUpdated(row, col);
        }
    }

    // ── Data management ────────────────────────────────────────────────────

    /** Replaces all fonts and reapplies the current filter. */
    public void setFonts(List<FontInfo> fonts) {
        masterList.clear();
        masterList.addAll(fonts);
        applyFilter();
    }

    /** Returns the full (unfiltered) master list. */
    public List<FontInfo> getMasterList() {
        return masterList;
    }

    /** Returns only the currently visible (filtered) rows. */
    public List<FontInfo> getVisibleList() {
        return visibleList;
    }

    /** Returns the FontInfo at the given visible row index. */
    public FontInfo getFontAt(int row) {
        return visibleList.get(row);
    }

    // ── Selection queries ──────────────────────────────────────────────────
    //
    // Selection state lives on the FontInfo objects in the master list, so it
    // survives a change of filter. That is deliberate - it lets a user build a
    // selection across several searches - but it means the visible rows are not
    // the whole truth about what an action will touch. Everything that acts on
    // "the selection" must therefore ask the master list, and anything that
    // reports the selection to the user must be able to say how much of it is
    // currently off-screen.

    /** Returns every selected font in the library, whether or not it is visible. */
    public List<FontInfo> getSelectedFonts() {
        List<FontInfo> selected = new ArrayList<>();
        for (FontInfo f : masterList) {
            if (f.isSelected()) selected.add(f);
        }
        return selected;
    }

    /** Number of selected fonts across the whole library, visible or not. */
    public int getSelectedCount() {
        int count = 0;
        for (FontInfo f : masterList) {
            if (f.isSelected()) count++;
        }
        return count;
    }

    /**
     * Number of fonts that are selected but hidden by the current filter.
     * These are rows the user cannot see and so cannot reason about; any
     * destructive action has to account for them out loud.
     */
    public int getHiddenSelectedCount() {
        // Identity-based, not equals-based: FontInfo has no equals() override and
        // two different files could legitimately share a display name.
        java.util.Set<FontInfo> visible =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        visible.addAll(visibleList);

        int count = 0;
        for (FontInfo f : masterList) {
            if (f.isSelected() && !visible.contains(f)) count++;
        }
        return count;
    }

    /**
     * Clears the selection on every font in the library, including rows the
     * filter is currently hiding. This is what "Unselect All" has to mean: a
     * clear-all that only cleared visible rows would leave the user looking at
     * an empty-looking table with a live selection behind it.
     */
    public void clearAllSelections() {
        for (FontInfo f : masterList) {
            f.setSelected(false);
        }
        fireTableDataChanged();
    }

    /** Sets a new filter predicate and refreshes the table. */
    public void setFilter(Predicate<FontInfo> predicate) {
        this.filter = predicate != null ? predicate : f -> true;
        applyFilter();
    }

    /** Notifies the table that the data in all rows may have changed (e.g. after bulk select). */
    public void refreshAll() {
        fireTableDataChanged();
    }

    private void applyFilter() {
        visibleList.clear();
        for (FontInfo f : masterList) {
            if (filter.test(f)) visibleList.add(f);
        }
        fireTableDataChanged();
    }
}
