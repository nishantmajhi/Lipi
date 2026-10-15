package com.lipi.util;

import javax.swing.JOptionPane;

/**
 * Utility class for displaying alerts and dialogs.
 * JavaFX Alert replaced with Swing JOptionPane.
 */
public class AlertUtil {

    private AlertUtil() {}

    public static void showInfo(String title, String content) {
        JOptionPane.showMessageDialog(null, content, title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static void showError(String title, String content) {
        JOptionPane.showMessageDialog(null, content, title, JOptionPane.ERROR_MESSAGE);
    }

    public static void showWarning(String title, String content) {
        JOptionPane.showMessageDialog(null, content, title, JOptionPane.WARNING_MESSAGE);
    }

    public static boolean showConfirmation(String title, String header, String content) {
        String message = (header != null && !header.isBlank()) ? header + "\n\n" + content : content;
        int result = JOptionPane.showConfirmDialog(null, message, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return result == JOptionPane.OK_OPTION;
    }
}
