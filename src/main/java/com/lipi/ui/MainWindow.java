package com.lipi.ui;

import com.lipi.model.FontInfo;
import com.lipi.service.FontExportService;
import com.lipi.service.FontService;
import com.lipi.util.AdminUtil;
import com.lipi.util.AlertUtil;
import com.lipi.util.SystemUtil;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main application window containing all UI components.
 * Complete Swing replacement for the JavaFX MainWindow.
 *
 * Threading model: long-running I/O runs on a background executor;
 * all UI updates are posted back to the EDT via SwingUtilities.invokeLater,
 * mirroring the original use of CompletableFuture + Platform.runLater.
 */
public class MainWindow extends JFrame {

    private static final String FONTS_DIR_NAME = "fonts";

    // ── colours matching the original JavaFX palette ──────────────────────
    private static final Color COLOR_HEADER_BG  = new Color(0x2C, 0x3E, 0x50);
    private static final Color COLOR_STATUS_BAR = new Color(0xEC, 0xF0, 0xF1);
    private static final Color COLOR_BTN_INSTALL = new Color(0x27, 0xAE, 0x60);
    private static final Color COLOR_BTN_REMOVE  = new Color(0xE7, 0x4C, 0x3C);
    // Clear used to be grey, which now carries a specific meaning: grey is how
    // every control in the action row shows it is disabled. Clear takes the
    // blue from the same palette as the green and red so the row reads as
    // three enabled actions, and any grey in it means "unavailable".
    private static final Color COLOR_BTN_CLEAR    = new Color(0x29, 0x80, 0xB9);
    private static final Color COLOR_BTN_DISABLED = new Color(0x95, 0xA5, 0xA6);
    private static final Color COLOR_STATUS_TEXT = new Color(0x7F, 0x8C, 0x8D);
    private static final Color COLOR_STATUS_WARN = new Color(0xC0, 0x39, 0x2B);

    /** Cap on how many font names a confirmation dialog lists before summarising. */
    private static final int MAX_NAMES_IN_DIALOG = 12;

    private final FontService        fontService;
    private final FontExportService  exportService;
    private final ExecutorService    bgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lipi-bg");
        t.setDaemon(true);
        return t;
    });

    // ── UI components ─────────────────────────────────────────────────────
    private FontTableView tableView;
    private JLabel        statusLabel;
    private JProgressBar  progressBar;
    private JButton       installBtn;
    private JButton       removeBtn;
    private JButton       clearBtn;
    private JTextField    searchField;
    private JPopupMenu    contextMenu;
    private ContextMenuBuilder contextMenuBuilder;

    public MainWindow(File appDir) {
        super("लिपि");
        String fontsDir = new File(appDir, FONTS_DIR_NAME).getAbsolutePath();
        this.fontService   = new FontService(fontsDir);
        this.exportService = new FontExportService(fontsDir);
    }

    /** Builds and shows the main window. */
    public void buildAndShow() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setMinimumSize(new Dimension(700, 450));

        // Load icons
        loadAppIcons();

        contextMenu = createContextMenu();

        setJMenuBar(createMenuBar());

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(createHeaderPanel(), BorderLayout.NORTH);
        getContentPane().add(createCenterPanel(), BorderLayout.CENTER);
        getContentPane().add(createStatusBar(),   BorderLayout.SOUTH);

        setLocationRelativeTo(null);
        setVisible(true);

        refreshFonts();
    }

    // ── Panel builders ────────────────────────────────────────────────────

    private JMenuBar createMenuBar() {
        return new MenuBarBuilder()
                .withAddFontFilesAction(this::selectAndAddFontFiles)
                .withAddZipFileAction(this::selectAndExtractZipFile)
                .withOpenFontsFolderAction(this::openFontsFolder)
                .withExportSeparateFilesAction(this::exportFontsAsSeparateFiles)
                .withExportZipAction(this::exportFontsAsZip)
                .build();
    }

    private JPopupMenu createContextMenu() {
        contextMenuBuilder = new ContextMenuBuilder()
                .withInstallFontAction(this::installSingleFont)
                .withRemoveFontAction(this::removeSingleFont)
                .withRefreshAction(this::refreshFonts)
                .withSelectAllAction(this::selectAll)
                .withUnselectAllAction(this::unselectAll)
                .withInvertSelectionAction(this::invertSelection);
        return contextMenuBuilder.build();
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel();
        header.setBackground(COLOR_HEADER_BG);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        JLabel title = new JLabel("Lipi");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Nepali Font Manager");
        subtitle.setFont(subtitle.getFont().deriveFont(14f));
        subtitle.setForeground(new Color(0xEC, 0xF0, 0xF1));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        return header;
    }

    private JPanel createCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        center.setBackground(new Color(0xF5, 0xF5, 0xF5));

        center.add(createControlPanel(), BorderLayout.NORTH);
        center.add(createTablePanel(),   BorderLayout.CENTER);

        return center;
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);

        // Left: Install / Remove buttons + spinner
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftButtons.setOpaque(false);

        installBtn = new StyledButton("Install Selected", COLOR_BTN_INSTALL, COLOR_BTN_DISABLED);
        installBtn.addActionListener(e -> installSelectedFonts());

        removeBtn = new StyledButton("Remove Selected", COLOR_BTN_REMOVE, COLOR_BTN_DISABLED);
        removeBtn.addActionListener(e -> removeSelectedFonts());

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(100, 20));
        progressBar.setVisible(false);

        leftButtons.add(installBtn);
        leftButtons.add(removeBtn);
        leftButtons.add(progressBar);

        // Right: Search field + Clear button
        JPanel rightSearch = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightSearch.setOpaque(false);

        searchField = new JTextField(20);
        searchField.putClientProperty("JTextField.placeholderText", "Search fonts...");
        searchField.setToolTipText("Search fonts...");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { onSearchChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { onSearchChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onSearchChanged(); }
        });

        clearBtn = new StyledButton("Clear", COLOR_BTN_CLEAR, COLOR_BTN_DISABLED);
        clearBtn.addActionListener(e -> searchField.setText(""));

        rightSearch.add(new JLabel("Search: "));
        rightSearch.add(searchField);
        rightSearch.add(clearBtn);

        // A JTextField's natural height is a few pixels shorter than a button's,
        // so the search box sat visibly low against the Clear button beside it.
        // Rather than hard-code a height (which breaks at non-100% display
        // scaling), take the tallest natural height in the row and give it to
        // everything, so all four controls share one baseline at any DPI.
        int rowHeight = maxPreferredHeight(installBtn, removeBtn, clearBtn, searchField);
        lockHeight(installBtn, rowHeight);
        lockHeight(removeBtn,  rowHeight);
        lockHeight(clearBtn,   rowHeight);
        lockHeight(searchField, rowHeight);

        // The taller field needs interior padding or the text hugs the frame.
        searchField.setBorder(BorderFactory.createCompoundBorder(
                searchField.getBorder(),
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));

        panel.add(leftButtons,  BorderLayout.WEST);
        panel.add(rightSearch,  BorderLayout.EAST);

        return panel;
    }

    /** Largest preferred height among {@code components}. */
    private static int maxPreferredHeight(JComponent... components) {
        int max = 0;
        for (JComponent c : components) {
            max = Math.max(max, c.getPreferredSize().height);
        }
        return max;
    }

    /** Pins {@code c} to {@code height}, leaving its preferred width alone. */
    private static void lockHeight(JComponent c, int height) {
        Dimension preferred = c.getPreferredSize();
        c.setPreferredSize(new Dimension(preferred.width, height));
    }

    private JScrollPane createTablePanel() {
        tableView = new FontTableView();

        // Attach the right-click context menu to the table
        MouseAdapter contextMenuTrigger = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tableView.rowAtPoint(e.getPoint());
                    FontInfo target = tableView.getFontAt(row);
                    if (target != null) {
                        // Mirror the usual desktop convention of selecting the
                        // row you right-click, so it's clear which font
                        // "Install this font" is about to act on.
                        tableView.setRowSelectionInterval(row, row);
                    }
                    contextMenuBuilder.setTargetFont(target);
                    contextMenuBuilder.setFilterState(
                            !searchField.getText().isBlank(),
                            tableView.getFilteredFontList().size());
                    contextMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        tableView.addMouseListener(contextMenuTrigger);

        // Keep the status bar's selection count honest when the user ticks or
        // unticks a checkbox directly, not just after a bulk action. Without
        // this the count only refreshes on search/refresh/install/remove, so a
        // hand-built selection stays invisible in the status bar.
        tableView.getModel().addTableModelListener(e -> updateStatus());

        JScrollPane scrollPane = new JScrollPane(tableView);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0xDC, 0xDC, 0xDC)));
        return scrollPane;
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(COLOR_STATUS_BAR);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xCC, 0xCC, 0xCC)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        left.setOpaque(false);
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusLabel.setForeground(COLOR_STATUS_TEXT);
        left.add(statusLabel);

        // Installing/removing fonts needs Administrator privileges. Surfacing
        // that status here - always visible, not just in a one-time startup
        // dialog - makes "why did remove just fail?" self-explanatory instead
        // of looking like a bug, especially when running from an IDE where
        // the in-app "Restart as Admin" option isn't available at all.
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        right.setOpaque(false);
        boolean isAdmin = AdminUtil.isRunningAsAdmin();
        JLabel adminLabel = new JLabel(isAdmin
                ? "\u25CF Running as Administrator"
                : "\u25CF Not running as Administrator - install/remove will fail");
        adminLabel.setFont(adminLabel.getFont().deriveFont(12f));
        adminLabel.setForeground(isAdmin ? new Color(0x27, 0xAE, 0x60) : new Color(0xE7, 0x4C, 0x3C));
        right.add(adminLabel);

        bar.add(left,  BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ── Font operations ───────────────────────────────────────────────────

    private void refreshFonts() {
        setLoading(true);

        bgExecutor.submit(() -> {
            try {
                List<FontInfo> fonts = fontService.loadFontsFromDirectory();
                SwingUtilities.invokeLater(() -> {
                    tableView.setFonts(fonts);
                    updateStatus();
                    setLoading(false);
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    AlertUtil.showError("Error",
                            "Could not load fonts: " + e.getMessage() +
                            "\n\nMake sure a 'fonts' directory exists in the application folder.");
                    setLoading(false);
                });
            }
        });
    }

    private void installSelectedFonts() {
        List<FontInfo> selected = tableView.getSelectedFonts();

        if (selected.isEmpty()) {
            AlertUtil.showWarning("No Selection", "Please select fonts to install.");
            return;
        }

        boolean confirmed = AlertUtil.showConfirmation(
                "Install Fonts",
                "Install " + selected.size() + " font(s)?",
                "The selected fonts will be installed to the system fonts directory."
                        + selectionSummary(selected));
        if (!confirmed) return;

        setLoading(true);

        bgExecutor.submit(() -> {
            FontService.InstallResult result = fontService.installFonts(selected);

            SwingUtilities.invokeLater(() -> {
                setLoading(false);
                tableView.refreshAll();
                updateStatus();

                if (result.getSuccessCount() > 0) {
                    AlertUtil.showInfo("Installation Complete",
                            String.format("Successfully installed %d font(s).", result.getSuccessCount()));
                }
                if (result.getNeedsElevationCount() > 0) {
                    AlertUtil.showError("Administrator Required",
                            String.format("%d font(s) install to the system fonts directory, which "
                                    + "requires Administrator privileges.\n\nPlease restart Lipi as "
                                    + "Administrator (right-click \u2192 Run as administrator) and try again.",
                                    result.getNeedsElevationCount()));
                }
                if (result.getLockedCount() > 0) {
                    AlertUtil.showError("Restart Required",
                            String.format("%d font(s) could not be replaced because Windows still has "
                                    + "the existing file open.\n\nThis is not a permissions problem. "
                                    + "Restart Windows and install again.",
                                    result.getLockedCount()));
                }
                if (result.getFailedCount() > 0) {
                    AlertUtil.showError("Installation Error",
                            String.format("Failed to install %d font(s). "
                                    + "See lipi-debug.log for details.", result.getFailedCount()));
                }
            });
        });
    }

    private void removeSelectedFonts() {
        List<FontInfo> selected = tableView.getSelectedFonts();

        if (selected.isEmpty()) {
            AlertUtil.showWarning("No Selection", "Please select fonts to remove.");
            return;
        }

        boolean confirmed = AlertUtil.showConfirmation(
                "Remove Fonts",
                "Remove " + selected.size() + " font(s)?",
                "The selected fonts will be removed from the system fonts directory."
                        + selectionSummary(selected));
        if (!confirmed) return;

        setLoading(true);

        bgExecutor.submit(() -> {
            FontService.RemoveBatchResult result = fontService.removeFonts(selected);

            SwingUtilities.invokeLater(() -> {
                setLoading(false);
                tableView.refreshAll();
                updateStatus();

                // Fix 3 & 4: separate messages for each outcome category so the
                // user knows exactly what happened and what (if anything) to do next.
                if (result.getSuccessCount() > 0) {
                    AlertUtil.showInfo("Removal Complete",
                            String.format("Successfully removed %d font(s).", result.getSuccessCount()));
                }
                if (result.getPendingRebootCount() > 0) {
                    AlertUtil.showInfo("Removal Scheduled",
                            String.format("%d font(s) have been unregistered and will be fully removed "
                                    + "from disk on the next Windows restart.",
                                    result.getPendingRebootCount()));
                }
                if (result.getNeedsElevationCount() > 0) {
                    AlertUtil.showError("Administrator Required",
                            String.format("%d font(s) are system fonts that require Administrator "
                                    + "privileges to remove.\n\nPlease restart Lipi as Administrator "
                                    + "(right-click → Run as administrator) and try again.",
                                    result.getNeedsElevationCount()));
                }
                if (result.getFailedCount() > 0) {
                    AlertUtil.showError("Removal Error",
                            String.format("Failed to remove %d font(s). "
                                    + "See lipi-debug.log for details.", result.getFailedCount()));
                }
            });
        });
    }

    private void selectAndAddFontFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Font Files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Font Files (*.ttf, *.otf)", "ttf", "otf", "TTF", "OTF"));

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        List<File> selectedFiles = Arrays.asList(chooser.getSelectedFiles());
        if (selectedFiles.isEmpty()) return;

        bgExecutor.submit(() -> {
            FontExportService.ExportResult result = exportService.addFontFiles(selectedFiles);

            SwingUtilities.invokeLater(() -> {
                if (result.getSuccessCount() > 0) {
                    AlertUtil.showInfo("Fonts Added",
                            String.format("Successfully added %d font file(s) to the fonts folder.",
                                    result.getSuccessCount()));
                    refreshFonts();
                }
                if (result.getFailedCount() > 0) {
                    AlertUtil.showError("Error",
                            String.format("Failed to add %d font file(s).", result.getFailedCount()));
                }
            });
        });
    }

    private void selectAndExtractZipFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select ZIP File");
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP Archives (*.zip)", "zip", "ZIP"));

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File selectedFile = chooser.getSelectedFile();
        if (selectedFile == null) return;

        bgExecutor.submit(() -> {
            FontExportService.ExportResult result = exportService.extractFromZip(selectedFile);

            SwingUtilities.invokeLater(() -> {
                if (result.getSuccessCount() > 0) {
                    AlertUtil.showInfo("Fonts Extracted",
                            String.format("Successfully extracted %d font file(s) from the ZIP archive.",
                                    result.getSuccessCount()));
                    refreshFonts();
                } else {
                    AlertUtil.showWarning("No Fonts Found",
                            "No .ttf or .otf font files were found in the ZIP archive.");
                }
            });
        });
    }

    private void openFontsFolder() {
        try {
            SystemUtil.openSystemFontsFolder();
        } catch (Exception e) {
            AlertUtil.showError("Error", "Failed to open system fonts folder: " + e.getMessage());
        }
    }

    private void exportFontsAsSeparateFiles() {
        List<FontInfo> selected = tableView.getSelectedFonts();

        if (selected.isEmpty()) {
            AlertUtil.showWarning("No Selection", "Please select fonts to export.");
            return;
        }

        if (!confirmHiddenSelection(selected, "Export Fonts", "export")) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Export Directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File exportDir = chooser.getSelectedFile();
        if (exportDir == null) return;

        setLoading(true);

        bgExecutor.submit(() -> {
            FontExportService.ExportResult result = exportService.exportAsSeparateFiles(selected, exportDir);

            SwingUtilities.invokeLater(() -> {
                setLoading(false);
                if (result.getSuccessCount() > 0) {
                    AlertUtil.showInfo("Export Complete",
                            String.format("Successfully exported %d font file(s) to:\n%s",
                                    result.getSuccessCount(), exportDir.getAbsolutePath()));
                }
                if (result.getFailedCount() > 0) {
                    AlertUtil.showWarning("Export Error",
                            String.format("Failed to export %d font file(s).", result.getFailedCount()));
                }
            });
        });
    }

    private void exportFontsAsZip() {
        List<FontInfo> selected = tableView.getSelectedFonts();

        if (selected.isEmpty()) {
            AlertUtil.showWarning("No Selection", "Please select fonts to export.");
            return;
        }

        if (!confirmHiddenSelection(selected, "Export Fonts", "export")) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save ZIP File");
        chooser.setSelectedFile(new File("fonts-export.zip"));
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP Archives (*.zip)", "zip"));

        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File zipFile = chooser.getSelectedFile();
        if (zipFile == null) return;
        // Ensure .zip extension
        if (!zipFile.getName().toLowerCase().endsWith(".zip")) {
            zipFile = new File(zipFile.getAbsolutePath() + ".zip");
        }

        final File finalZipFile = zipFile;
        setLoading(true);

        bgExecutor.submit(() -> {
            FontExportService.ExportResult result = exportService.exportAsZip(selected, finalZipFile);

            SwingUtilities.invokeLater(() -> {
                setLoading(false);
                if (result.getSuccessCount() > 0) {
                    AlertUtil.showInfo("Export Complete",
                            String.format("Successfully exported %d font file(s) to:\n%s",
                                    result.getSuccessCount(), finalZipFile.getAbsolutePath()));
                } else {
                    AlertUtil.showError("Export Failed", "Failed to export any fonts to ZIP file.");
                }
            });
        });
    }

    // ── Selection helpers ─────────────────────────────────────────────────

    private void selectAll() {
        tableView.getFilteredFontList().forEach(f -> f.setSelected(true));
        tableView.refreshAll();
    }

    /**
     * Clears the selection on every font, not just the rows the filter happens
     * to be showing. Select All and Invert Selection are deliberately scoped to
     * the visible rows - that is what makes a filtered bulk-select useful - but
     * Unselect All has to be the escape hatch that guarantees nothing is left
     * selected, otherwise there is no way to get back to a known-empty state
     * without restarting the app.
     */
    private void unselectAll() {
        tableView.clearAllSelections();
        tableView.refreshAll();
        updateStatus();
    }

    private void invertSelection() {
        tableView.getFilteredFontList().forEach(f -> f.setSelected(!f.isSelected()));
        tableView.refreshAll();
    }

    /** Installs a single font directly from the right-click "Install this font" action. */
    private void installSingleFont(FontInfo font) {
        if (font == null) return;

        setLoading(true);

        bgExecutor.submit(() -> {
            FontService.InstallOutcome outcome = fontService.installFont(new File(font.getFilePath()));

            if (outcome == FontService.InstallOutcome.SUCCESS
                    || outcome == FontService.InstallOutcome.REINSTATED) {
                font.setInstalled(true);
            }

            SwingUtilities.invokeLater(() -> {
                setLoading(false);
                tableView.refreshAll();
                updateStatus();

                switch (outcome) {
                    case SUCCESS ->
                        AlertUtil.showInfo("Installation Complete",
                                "Successfully installed \"" + font.getName() + "\".");
                    case REINSTATED ->
                        AlertUtil.showInfo("Installation Complete",
                                "Successfully reinstalled \"" + font.getName() + "\".\n\n"
                                + "This font was removed earlier in this session and was still "
                                + "awaiting deletion at the next restart. That deletion has been "
                                + "cancelled and the existing file reused.");
                    case NEEDS_ELEVATION ->
                        AlertUtil.showError("Administrator Required",
                                "\"" + font.getName() + "\" installs to the system fonts directory, "
                                + "which requires Administrator privileges.\n\n"
                                + "Please restart Lipi as Administrator (right-click \u2192 "
                                + "Run as administrator) and try again.");
                    case LOCKED ->
                        AlertUtil.showError("Restart Required",
                                "\"" + font.getName() + "\" could not be replaced because Windows "
                                + "still has the existing file open.\n\n"
                                + "This is not a permissions problem. Restart Windows and install again.");
                    case PENDING_DELETE_STUCK ->
                        AlertUtil.showError("Restart Required",
                                "Windows has a pending deletion queued for \"" + font.getName()
                                + "\" that Lipi could not cancel.\n\n"
                                + "Nothing was installed \u2014 installing now would appear to work "
                                + "and then be undone at the next restart. Restart Windows and "
                                + "install again.");
                    default ->
                        AlertUtil.showError("Installation Error",
                                "Failed to install \"" + font.getName() + "\". "
                                + "See lipi-debug.log for details.");
                }
            });
        });
    }

    /** Removes a single font directly from the right-click "Remove this font" action. */
    private void removeSingleFont(FontInfo font) {
        if (font == null) return;

        setLoading(true);

        bgExecutor.submit(() -> {
            FontService.RemoveResult result = fontService.removeFont(new java.io.File(font.getFilePath()));

            // Fix 3 & 4: surface precise messages for each outcome instead of
            // the generic "make sure you're running as Administrator" that the
            // old boolean API produced regardless of the actual failure reason.
            if (result == FontService.RemoveResult.SUCCESS
                    || result == FontService.RemoveResult.PENDING_REBOOT) {
                font.setInstalled(false);
            }

            SwingUtilities.invokeLater(() -> {
                setLoading(false);
                tableView.refreshAll();
                updateStatus();

                switch (result) {
                    case SUCCESS ->
                        AlertUtil.showInfo("Removal Complete",
                                "Successfully removed \"" + font.getName() + "\".");
                    case PENDING_REBOOT ->
                        AlertUtil.showInfo("Removal Scheduled",
                                "\"" + font.getName() + "\" has been unregistered and will be "
                                + "fully removed from disk on the next Windows restart.");
                    case NEEDS_ELEVATION ->
                        AlertUtil.showError("Administrator Required",
                                "\"" + font.getName() + "\" is a system font and requires "
                                + "Administrator privileges to remove.\n\n"
                                + "Please restart Lipi as Administrator (right-click → "
                                + "Run as administrator) and try again.");
                    default ->
                        AlertUtil.showError("Removal Error",
                                "Failed to remove \"" + font.getName() + "\". "
                                + "See lipi-debug.log for details.");
                }
            });
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void onSearchChanged() {
        tableView.filterFonts(searchField.getText());
        updateStatus();
    }

    /**
     * Refreshes the status bar, which now reports the selection as well as the
     * row count.
     *
     * <p>The selection outlives the search filter, so a font selected under one
     * search stays selected after the search changes even though its row is no
     * longer on screen. Reporting only "Showing N font(s)" left that state with
     * no representation anywhere in the UI: the user saw an empty-looking table
     * and had no way to know an action would touch rows they could not see.
     * The count - and the explicit "hidden by search" warning - is what makes
     * that state visible.</p>
     */
    private void updateStatus() {
        if (statusLabel == null) return;   // called before the status bar exists

        int total    = tableView.getFontList().size();
        int filtered = tableView.getFilteredFontList().size();
        int selected = tableView.getSelectedCount();
        int hidden   = tableView.getHiddenSelectedCount();

        StringBuilder text = new StringBuilder();
        if (filtered == total) {
            text.append("Showing ").append(total).append(" font(s)");
        } else {
            text.append("Found ").append(filtered).append(" of ").append(total).append(" font(s)");
        }

        if (selected > 0) {
            text.append("   \u2022   ").append(selected).append(" selected");
            if (hidden > 0) {
                text.append(" (").append(hidden).append(" hidden by search)");
            }
        }

        statusLabel.setText(text.toString());
        statusLabel.setForeground(hidden > 0 ? COLOR_STATUS_WARN : COLOR_STATUS_TEXT);
    }

    /**
     * Builds the trailing part of a confirmation dialog describing any part of
     * the selection the search filter is hiding, or an empty string when the
     * whole selection is on screen.
     *
     * <p>A confirmation that says only "Remove 6 font(s)?" while one row is
     * visible is worse than no confirmation: it looks like a formality, so it
     * gets clicked through. Naming the off-screen fonts is what turns it back
     * into a real decision point.</p>
     */
    private String selectionSummary(List<FontInfo> selected) {
        int hidden = tableView.getHiddenSelectedCount();
        if (hidden == 0) return "";

        java.util.Set<FontInfo> visible =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        visible.addAll(tableView.getFilteredFontList());

        List<String> hiddenNames = selected.stream()
                .filter(f -> !visible.contains(f))
                .map(FontInfo::getName)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n\u26A0  ").append(hidden)
          .append(hidden == 1 ? " of these is" : " of these are")
          .append(" hidden by your current search (\"")
          .append(searchField.getText().trim())
          .append("\") and ")
          .append(hidden == 1 ? "is" : "are")
          .append(" not shown in the list:\n");

        hiddenNames.stream().limit(MAX_NAMES_IN_DIALOG)
                .forEach(n -> sb.append("    ").append(n).append("\n"));
        if (hiddenNames.size() > MAX_NAMES_IN_DIALOG) {
            sb.append("    ... and ")
              .append(hiddenNames.size() - MAX_NAMES_IN_DIALOG)
              .append(" more\n");
        }
        return sb.toString();
    }

    /**
     * Confirmation gate used by the non-destructive actions (export), which had
     * no confirmation step at all. Prompts only when part of the selection is
     * off screen; returns true when there is nothing hidden and the caller
     * should just proceed.
     */
    private boolean confirmHiddenSelection(List<FontInfo> selected, String title, String verb) {
        if (tableView.getHiddenSelectedCount() == 0) return true;
        return AlertUtil.showConfirmation(
                title,
                "This will " + verb + " " + selected.size() + " font(s).",
                "Continue?" + selectionSummary(selected));
    }

    /**
     * Toggles the busy state of the action row. Every control in the row greys
     * out together, so the grey fill reads as one unambiguous "unavailable"
     * signal rather than leaving Clear looking live beside two dead buttons.
     */
    private void setLoading(boolean loading) {
        progressBar.setVisible(loading);
        installBtn.setEnabled(!loading);
        removeBtn.setEnabled(!loading);
        clearBtn.setEnabled(!loading);
        searchField.setEnabled(!loading);
    }

    private void loadAppIcons() {
        // Swing stages (JFrame) accept a List<Image> for multi-resolution icons
        java.util.List<Image> icons = new java.util.ArrayList<>();
        for (String name : List.of("icon-16.png", "icon-32.png", "icon-64.png",
                                   "icon-128.png", "icon-256.png", "icon.png")) {
            File f = new File(com.lipi.Lipi.getAppDir(), "icons/" + name);
            if (f.exists()) {
                try {
                    icons.add(new ImageIcon(f.toURI().toURL()).getImage());
                } catch (Exception ignored) {}
            }
        }
        if (!icons.isEmpty()) setIconImages(icons);
    }
}
