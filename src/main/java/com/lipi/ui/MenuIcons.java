package com.lipi.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The 16x16 glyphs used in the menu bar and the right-click menu.
 *
 * <p>Drawn with Java2D rather than loaded from image files, for three reasons:
 * the project stays dependency- and asset-free (nothing to add to
 * {@code pom.xml}, no binaries in the repo outside {@code icons/}); vector
 * paths stay sharp at 125% and 150% display scaling where a 16x16 PNG would
 * blur; and the colours come from the same palette as the rest of the UI
 * instead of being baked into exported files.</p>
 *
 * <p>Windows' own action icons live inside {@code shell32.dll} and are not
 * reachable from a pure-Java application, and the {@code UIManager} icons that
 * <em>are</em> reachable are the wrong size, carry the wrong meaning, and
 * resolve differently per look-and-feel - which would break the cross-platform
 * behaviour the app otherwise has.</p>
 */
final class MenuIcons {

    /** Menu icon edge length, in unscaled pixels. */
    private static final int SIZE = 16;

    // Palette shared with MainWindow.
    private static final Color GREEN   = new Color(0x27, 0xAE, 0x60);
    private static final Color RED     = new Color(0xE7, 0x4C, 0x3C);
    private static final Color BLUE    = new Color(0x29, 0x80, 0xB9);
    private static final Color NEUTRAL = new Color(0x5D, 0x6D, 0x7E);
    private static final Color MUTED   = new Color(0x95, 0xA5, 0xA6);

    private MenuIcons() {}

    // ── Context menu ──────────────────────────────────────────────────────

    /** Plus sign - "Install this font". */
    static Icon install() {
        return icon(g -> {
            stroke(g, GREEN, 2f);
            g.drawLine(3, 8, 13, 8);
            g.drawLine(8, 3, 8, 13);
        });
    }

    /** Waste basket - "Remove this font". Red, because the action is destructive. */
    static Icon remove() {
        return icon(g -> {
            stroke(g, RED, 1.6f);
            g.drawLine(2, 4, 14, 4);          // lid
            g.drawLine(6, 4, 6, 2);           // handle
            g.drawLine(10, 4, 10, 2);
            g.drawLine(6, 2, 10, 2);
            Path2D body = new Path2D.Float();  // tapered bin
            body.moveTo(4, 5);
            body.lineTo(5, 14);
            body.lineTo(11, 14);
            body.lineTo(12, 5);
            g.draw(body);
            g.drawLine(8, 7, 8, 12);           // ribs
        });
    }

    /** Circular arrow - "Refresh application". */
    static Icon refresh() {
        return icon(g -> {
            stroke(g, BLUE, 1.8f);
            // Clockwise from the top, leaving a gap at the upper left. Drawing
            // it clockwise puts the open end at the top moving rightwards, so
            // the arrowhead below sits tangent to it rather than adrift.
            g.draw(new Arc2D.Float(3, 3, 10, 10, 90, -280, Arc2D.OPEN));
            Path2D head = new Path2D.Float();
            head.moveTo(7.4f, 0.6f);
            head.lineTo(12.2f, 3.0f);      // apex, pointing right
            head.lineTo(7.4f, 5.4f);
            head.closePath();
            g.setColor(BLUE);
            g.fill(head);                   // overlaps the arc's top at (8,3)
        });
    }

    /** Ticked box - "Select All". */
    static Icon selectAll() {
        return icon(g -> {
            stroke(g, BLUE, 1.5f);
            g.draw(new RoundRectangle2D.Float(2, 2, 12, 12, 3, 3));
            stroke(g, BLUE, 2f);
            Path2D tick = new Path2D.Float();
            tick.moveTo(5, 8.2f);
            tick.lineTo(7.2f, 10.8f);
            tick.lineTo(11.2f, 5.4f);
            g.draw(tick);
        });
    }

    /** Empty box - "Unselect All". Muted, since it clears rather than acts. */
    static Icon unselectAll() {
        return icon(g -> {
            stroke(g, MUTED, 1.5f);
            g.draw(new RoundRectangle2D.Float(2, 2, 12, 12, 3, 3));
        });
    }

    /** Half-filled box - "Invert Selection". */
    static Icon invertSelection() {
        return icon(g -> {
            Path2D half = new Path2D.Float();   // lower-left triangle filled
            half.moveTo(2.8f, 3.0f);
            half.lineTo(2.8f, 13.2f);
            half.lineTo(13.0f, 13.2f);
            half.closePath();
            g.setColor(BLUE);
            g.fill(half);
            stroke(g, BLUE, 1.5f);
            g.draw(new RoundRectangle2D.Float(2, 2, 12, 12, 3, 3));
        });
    }

    // ── File menu ─────────────────────────────────────────────────────────

    /** Folder with a plus - "Add Fonts". */
    static Icon addFonts() {
        return icon(g -> {
            stroke(g, NEUTRAL, 1.5f);
            g.draw(folderOutline());
            stroke(g, GREEN, 2f);
            g.drawLine(8, 8, 8, 12);
            g.drawLine(6, 10, 10, 10);
        });
    }

    /** Page with a folded corner - "Select .ttf/.otf files". */
    static Icon fontFile() {
        return icon(g -> {
            stroke(g, NEUTRAL, 1.5f);
            Path2D page = new Path2D.Float();
            page.moveTo(4, 1.5f);
            page.lineTo(9.5f, 1.5f);
            page.lineTo(12.5f, 4.5f);
            page.lineTo(12.5f, 14.5f);
            page.lineTo(4, 14.5f);
            page.closePath();
            g.draw(page);
            g.drawLine(9, 2, 9, 5);        // the fold
            g.drawLine(9, 5, 12, 5);
        });
    }

    /** Box with a band across it - "Select .zip file". */
    static Icon zipFile() {
        return icon(g -> {
            stroke(g, NEUTRAL, 1.5f);
            g.draw(new RoundRectangle2D.Float(2.5f, 3, 11, 11, 2, 2));
            g.drawLine(2, 7, 14, 7);
            g.setColor(NEUTRAL);           // zip pull
            g.fill(new Ellipse2D.Float(7.2f, 8.6f, 2.2f, 2.2f));
        });
    }

    /** Tray with an arrow leaving it - "Export Fonts". */
    static Icon export() {
        return icon(g -> {
            stroke(g, NEUTRAL, 1.5f);
            Path2D tray = new Path2D.Float();
            tray.moveTo(2.5f, 9.5f);
            tray.lineTo(2.5f, 13.5f);
            tray.lineTo(13.5f, 13.5f);
            tray.lineTo(13.5f, 9.5f);
            g.draw(tray);
            stroke(g, BLUE, 1.8f);
            g.drawLine(8, 2, 8, 10);
            Path2D head = new Path2D.Float();
            head.moveTo(8, 1.5f);
            head.lineTo(11, 5);
            head.lineTo(5, 5);
            head.closePath();
            g.setColor(BLUE);
            g.fill(head);
        });
    }

    /** Plain folder - "Open Fonts Folder". */
    static Icon openFolder() {
        return icon(g -> {
            stroke(g, NEUTRAL, 1.5f);
            g.draw(folderOutline());
        });
    }

    /** Arrow leaving a bracket - "Exit Lipi". */
    static Icon exit() {
        return icon(g -> {
            stroke(g, NEUTRAL, 1.5f);
            Path2D frame = new Path2D.Float();
            frame.moveTo(8, 2.5f);
            frame.lineTo(3, 2.5f);
            frame.lineTo(3, 13.5f);
            frame.lineTo(8, 13.5f);
            g.draw(frame);
            stroke(g, RED, 1.8f);
            g.drawLine(7, 8, 13, 8);
            Path2D head = new Path2D.Float();
            head.moveTo(14, 8);
            head.lineTo(10.5f, 5);
            head.lineTo(10.5f, 11);
            head.closePath();
            g.setColor(RED);
            g.fill(head);
        });
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private static Path2D folderOutline() {
        Path2D folder = new Path2D.Float();
        folder.moveTo(2, 4);
        folder.lineTo(6, 4);
        folder.lineTo(7.5f, 6);
        folder.lineTo(14, 6);
        folder.lineTo(14, 13);
        folder.lineTo(2, 13);
        folder.closePath();
        return folder;
    }

    private static void stroke(Graphics2D g, Color color, float width) {
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    /** What each glyph above supplies: drawing commands in a 16x16 space. */
    @FunctionalInterface
    private interface Glyph {
        void draw(Graphics2D g);
    }

    /**
     * Wraps a {@link Glyph} as a Swing {@link Icon}, handling the boilerplate:
     * a private copy of the Graphics (so stroke and colour changes cannot leak
     * into whatever paints next), antialiasing, and translation to the icon's
     * position within the menu item.
     */
    private static Icon icon(Glyph glyph) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                            RenderingHints.VALUE_STROKE_PURE);
                    g2.translate(x, y);
                    glyph.draw(g2);
                } finally {
                    g2.dispose();
                }
            }

            @Override public int getIconWidth()  { return SIZE; }
            @Override public int getIconHeight() { return SIZE; }
        };
    }
}
