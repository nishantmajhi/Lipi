package com.lipi.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Flat, solid-colour action button.
 *
 * <p>Two things a stock {@code JButton} could not do here:</p>
 *
 * <ul>
 *   <li><b>Disabled state.</b> Swing greys out the button's <em>text</em> and
 *       leaves {@code setBackground()} untouched, so a disabled Install button
 *       kept its saturated green fill and merely lost its label contrast -
 *       which reads as a rendering fault rather than as "busy". Here the fill
 *       turns grey instead and the label stays white, so grey is the single
 *       consistent signal for "disabled" across the whole action row.</li>
 *   <li><b>Label colour.</b> {@code BasicButtonUI} paints disabled text with
 *       the look-and-feel's {@code Button.disabledText} colour and ignores
 *       {@link #getForeground()}, so the white label cannot be preserved by
 *       ordinary means. {@link SolidButtonUI#paintText} overrides that to
 *       always use the button's own foreground.</li>
 * </ul>
 *
 * <p>Taking over the UI delegate also means rollover and pressed feedback have
 * to be drawn here, since the delegate no longer paints the platform's own.</p>
 */
class StyledButton extends JButton {

    /** Fill used whenever the button is enabled. */
    private final Color activeBackground;

    /** Shared "this control is unavailable" fill. */
    private final Color disabledBackground;

    StyledButton(String text, Color activeBackground, Color disabledBackground) {
        super(text);
        this.activeBackground   = activeBackground;
        this.disabledBackground = disabledBackground;

        setUI(new SolidButtonUI());
        setForeground(Color.WHITE);
        setBackground(activeBackground);
        setOpaque(true);
        setContentAreaFilled(true);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setFont(getFont().deriveFont(Font.PLAIN, 13f));
        // Padding is supplied by the border because the UI delegate lays the
        // label out inside the border insets.
        setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setBackground(enabled ? activeBackground : disabledBackground);
        // A hand cursor over a control that cannot be clicked is a small lie.
        setCursor(Cursor.getPredefinedCursor(
                enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** Returns {@code base} scaled towards white ({@code factor > 1}) or black ({@code < 1}). */
    private static Color shade(Color base, float factor) {
        return new Color(
                Math.min(255, Math.round(base.getRed()   * factor)),
                Math.min(255, Math.round(base.getGreen() * factor)),
                Math.min(255, Math.round(base.getBlue()  * factor)),
                base.getAlpha());
    }

    /**
     * Paints a plain rectangle of the button's background colour and draws the
     * label in the button's foreground regardless of enabled state.
     */
    private static final class SolidButtonUI extends BasicButtonUI {

        @Override
        public void update(Graphics g, JComponent c) {
            AbstractButton button = (AbstractButton) c;

            Color fill = button.getBackground();
            if (button.isEnabled()) {
                ButtonModel model = button.getModel();
                if (model.isPressed() && model.isArmed()) {
                    fill = shade(fill, 0.86f);
                } else if (model.isRollover()) {
                    fill = shade(fill, 1.12f);
                }
            }

            g.setColor(fill);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            paint(g, c);
        }

        @Override
        protected void paintText(Graphics g, AbstractButton b, Rectangle textRect, String text) {
            FontMetrics metrics = b.getFontMetrics(b.getFont());
            g.setColor(b.getForeground());
            BasicGraphicsUtils.drawStringUnderlineCharAt(
                    b, (Graphics2D) g, text, b.getDisplayedMnemonicIndex(),
                    textRect.x, textRect.y + metrics.getAscent());
        }
    }
}
