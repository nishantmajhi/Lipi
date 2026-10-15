package com.lipi.ui;

import com.lipi.model.FontInfo;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * Builder class for creating context menus.
 * JavaFX ContextMenu replaced with Swing JPopupMenu.
 *
 * <p>Note on the left-hand indent: Swing's Windows look-and-feel reserves an
 * "icon gutter" down the left of every popup, sized from the look-and-feel
 * defaults for check marks and menu icons. It is reserved unconditionally -
 * the UI does not check whether any item in this particular popup actually
 * has an icon, a checkbox, or a submenu, so removing those does not reclaim
 * the space. (An earlier version of this comment claimed that moving Export
 * Fonts to the menu bar had eliminated the gutter. It had not.)</p>
 *
 * <p>Since the space is reserved either way, every item here fills it with a
 * glyph from {@link MenuIcons} rather than leaving it blank.</p>
 */
public class ContextMenuBuilder {

    private Runnable onSelectAll;
    private Runnable onUnselectAll;
    private Runnable onInvertSelection;
    private Runnable onRefresh;
    private Consumer<FontInfo> onInstallFont;
    private Consumer<FontInfo> onRemoveFont;

    private JMenuItem installOrRemoveItem;
    private JMenuItem selectAllItem;
    private JMenuItem unselectAllItem;
    private JMenuItem invertItem;
    private FontInfo targetFont;

    public ContextMenuBuilder withSelectAllAction(Runnable action) {
        this.onSelectAll = action; return this;
    }

    public ContextMenuBuilder withUnselectAllAction(Runnable action) {
        this.onUnselectAll = action; return this;
    }

    public ContextMenuBuilder withInvertSelectionAction(Runnable action) {
        this.onInvertSelection = action; return this;
    }

    public ContextMenuBuilder withRefreshAction(Runnable action) {
        this.onRefresh = action; return this;
    }

    public ContextMenuBuilder withInstallFontAction(Consumer<FontInfo> action) {
        this.onInstallFont = action; return this;
    }

    public ContextMenuBuilder withRemoveFontAction(Consumer<FontInfo> action) {
        this.onRemoveFont = action; return this;
    }

    /**
     * Call this right before showing the popup so it knows which row (if any)
     * was right-clicked. Pass {@code null} when the click landed on empty
     * space (header, blank area below the last row, etc.) - the install/
     * remove item is hidden in that case since there's nothing for it to act
     * on. When a row IS supplied, the item's label and behaviour switch
     * automatically based on whether that font is currently installed:
     * "Remove this font" for an installed font, "Install this font" otherwise.
     */
    public void setTargetFont(FontInfo font) {
        this.targetFont = font;
        if (installOrRemoveItem == null) return;

        if (font == null) {
            installOrRemoveItem.setVisible(false);
        } else {
            installOrRemoveItem.setVisible(true);
            boolean installed = font.isInstalled();
            // Label and icon describe the same action, so they must switch
            // together - a green plus beside "Remove this font" would be worse
            // than no icon at all.
            installOrRemoveItem.setText(installed ? "Remove this font" : "Install this font");
            installOrRemoveItem.setIcon(installed ? MenuIcons.remove() : MenuIcons.install());
        }
    }

    /**
     * Call this right before showing the popup so the bulk-selection items can
     * say what they actually act on.
     *
     * <p>"Select All" and "Invert Selection" apply to the rows the search filter
     * is currently showing, not the whole library. When a filter is active that
     * distinction decides how many fonts a later Install/Remove touches, so the
     * label spells it out rather than leaving the user to infer it. "Unselect
     * All" is always library-wide - a clear-all that left hidden rows selected
     * would be the very trap this is guarding against.</p>
     *
     * @param filterActive whether a search filter is currently narrowing the table
     * @param visibleCount how many rows the filter is showing
     */
    public void setFilterState(boolean filterActive, int visibleCount) {
        if (selectAllItem == null) return;

        if (filterActive) {
            selectAllItem.setText("Select All " + visibleCount + " Matching");
            invertItem.setText("Invert Selection (matching only)");
            unselectAllItem.setText("Unselect All (entire library)");
        } else {
            selectAllItem.setText("Select All");
            invertItem.setText("Invert Selection");
            unselectAllItem.setText("Unselect All");
        }
    }

    public JPopupMenu build() {
        JPopupMenu menu = new JPopupMenu();

        // Only visible when right-clicking directly on a font row; label and
        // action both flip based on that font's current installed state.
        installOrRemoveItem = new JMenuItem("Install this font", MenuIcons.install());
        installOrRemoveItem.addActionListener(e -> {
            if (targetFont == null) return;
            if (targetFont.isInstalled()) {
                if (onRemoveFont != null) onRemoveFont.accept(targetFont);
            } else {
                if (onInstallFont != null) onInstallFont.accept(targetFont);
            }
        });
        installOrRemoveItem.setVisible(false);

        JMenuItem refreshItem = new JMenuItem("Refresh application", MenuIcons.refresh());
        refreshItem.addActionListener(e -> { if (onRefresh != null) onRefresh.run(); });

        selectAllItem = new JMenuItem("Select All", MenuIcons.selectAll());
        selectAllItem.addActionListener(e -> { if (onSelectAll != null) onSelectAll.run(); });

        unselectAllItem = new JMenuItem("Unselect All", MenuIcons.unselectAll());
        unselectAllItem.addActionListener(e -> { if (onUnselectAll != null) onUnselectAll.run(); });

        invertItem = new JMenuItem("Invert Selection", MenuIcons.invertSelection());
        invertItem.addActionListener(e -> { if (onInvertSelection != null) onInvertSelection.run(); });

        menu.add(installOrRemoveItem);
        menu.add(refreshItem);
        menu.addSeparator();
        menu.add(selectAllItem);
        menu.add(unselectAllItem);
        menu.add(invertItem);

        return menu;
    }
}
