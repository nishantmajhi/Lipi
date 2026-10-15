package com.lipi.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Builder class for creating the application menu bar.
 * JavaFX MenuBar replaced with Swing JMenuBar.
 *
 * <p>Items carry glyphs from {@link MenuIcons}. Swing reserves the left-hand
 * icon gutter in a popup whether or not anything occupies it (see
 * {@link ContextMenuBuilder}), so filling it costs no layout space.</p>
 */
public class MenuBarBuilder {

    private Runnable onAddFontFiles;
    private Runnable onAddZipFile;
    private Runnable onOpenFontsFolder;
    private Runnable onExportSeparateFiles;
    private Runnable onExportZip;

    public MenuBarBuilder withAddFontFilesAction(Runnable action) {
        this.onAddFontFiles = action;
        return this;
    }

    public MenuBarBuilder withAddZipFileAction(Runnable action) {
        this.onAddZipFile = action;
        return this;
    }

    public MenuBarBuilder withOpenFontsFolderAction(Runnable action) {
        this.onOpenFontsFolder = action;
        return this;
    }

    public MenuBarBuilder withExportSeparateFilesAction(Runnable action) {
        this.onExportSeparateFiles = action;
        return this;
    }

    public MenuBarBuilder withExportZipAction(Runnable action) {
        this.onExportZip = action;
        return this;
    }

    public JMenuBar build() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(createFileMenu());
        return menuBar;
    }

    private JMenu createFileMenu() {
        JMenu fileMenu = new JMenu("File");

        // Add Fonts submenu
        JMenu addFontsMenu = new JMenu("Add Fonts");
        addFontsMenu.setIcon(MenuIcons.addFonts());

        JMenuItem selectTtfOtf = new JMenuItem("Select .ttf/.otf files", MenuIcons.fontFile());
        selectTtfOtf.addActionListener(e -> { if (onAddFontFiles != null) onAddFontFiles.run(); });

        JMenuItem selectZip = new JMenuItem("Select .zip file", MenuIcons.zipFile());
        selectZip.addActionListener(e -> { if (onAddZipFile != null) onAddZipFile.run(); });

        addFontsMenu.add(selectTtfOtf);
        addFontsMenu.add(selectZip);

        // Export Fonts submenu
        JMenu exportFontsMenu = new JMenu("Export Fonts");
        exportFontsMenu.setIcon(MenuIcons.export());

        JMenuItem exportSeparate = new JMenuItem("As separate font files", MenuIcons.fontFile());
        exportSeparate.addActionListener(e -> { if (onExportSeparateFiles != null) onExportSeparateFiles.run(); });

        JMenuItem exportZip = new JMenuItem("As zip file", MenuIcons.zipFile());
        exportZip.addActionListener(e -> { if (onExportZip != null) onExportZip.run(); });

        exportFontsMenu.add(exportSeparate);
        exportFontsMenu.add(exportZip);

        JMenuItem openFontsFolder = new JMenuItem("Open Fonts Folder", MenuIcons.openFolder());
        openFontsFolder.addActionListener(e -> { if (onOpenFontsFolder != null) onOpenFontsFolder.run(); });

        JMenuItem exit = new JMenuItem("Exit Lipi", MenuIcons.exit());
        exit.addActionListener(e -> System.exit(0));

        fileMenu.add(addFontsMenu);
        fileMenu.add(exportFontsMenu);
        fileMenu.add(openFontsFolder);
        fileMenu.addSeparator();
        fileMenu.add(exit);

        return fileMenu;
    }
}
