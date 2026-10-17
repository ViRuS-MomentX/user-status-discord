package ru.virus.manager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Map;
import java.util.Set;

/**
 * Цвета и шрифты V.I.R.U.S. — тёмная «лаборатория» с ядовито-зелёным.
 */
final class Theme {

    static final Color BG = new Color(0x07, 0x0A, 0x09);
    static final Color CARD = new Color(15, 20, 18, 200);
    static final Color CARD_SOLID = new Color(0x0F, 0x14, 0x12);
    static final Color RAISED = new Color(0x16, 0x1E, 0x1A);
    static final Color LINE = new Color(0x22, 0x2E, 0x29);
    static final Color LINE_2 = new Color(0x2E, 0x3D, 0x36);
    static final Color TEXT = new Color(0xD6, 0xE2, 0xDC);
    static final Color MUTED = new Color(0x7C, 0x8C, 0x84);
    static final Color DIM = new Color(0x52, 0x60, 0x59);
    static final Color ACCENT = new Color(0x39, 0xFF, 0x8A);
    static final Color ACCENT_DIM = new Color(0x1D, 0x7A, 0x47);
    static final Color WARN = new Color(0xFF, 0xB5, 0x47);
    static final Color ERROR = new Color(0xFF, 0x5C, 0x6C);
    static final Color THREAD = new Color(0x5D, 0x7D, 0x93);
    static final Color SAY = new Color(0xB8, 0xF5, 0xD0);

    static final int ARC = 16;

    static Font ui = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    static Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private Theme() {
    }

    /** Ставит тему. Звать до создания первого окна. */
    static void install() {
        pickFonts();

        FlatLaf.setGlobalExtraDefaults(Map.of(
                "@accentColor", hex(ACCENT),
                "@background", hex(CARD_SOLID),
                "@foreground", hex(TEXT),
                "@selectionBackground", hex(ACCENT_DIM),
                "@selectionForeground", "#ffffff"));
        FlatDarkLaf.setup();

        UIManager.put("defaultFont", ui);
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.borderColor", LINE_2);
        UIManager.put("Component.focusedBorderColor", ACCENT_DIM);
        UIManager.put("Component.focusColor", new Color(57, 255, 138, 70));
        UIManager.put("Button.background", RAISED);
        UIManager.put("Button.borderColor", LINE_2);
        UIManager.put("Button.hoverBorderColor", ACCENT_DIM);
        UIManager.put("Button.default.background", ACCENT);
        UIManager.put("Button.default.foreground", new Color(0x04, 0x14, 0x0B));
        UIManager.put("TextField.background", new Color(0x0B, 0x10, 0x0E));
        UIManager.put("PasswordField.background", new Color(0x0B, 0x10, 0x0E));
        UIManager.put("TextArea.background", new Color(0x06, 0x08, 0x07));
        UIManager.put("ScrollBar.thumb", LINE_2);
        UIManager.put("ScrollBar.track", new Color(0, 0, 0, 0));
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollPane.border", javax.swing.BorderFactory.createEmptyBorder());
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("TitlePane.background", BG);
        UIManager.put("ToolTip.background", RAISED);
        UIManager.put("PopupMenu.background", CARD_SOLID);
        UIManager.put("OptionPane.background", CARD_SOLID);
        UIManager.put("Panel.background", CARD_SOLID);
    }

    /** Шрифты Windows, если есть; иначе — что найдётся. */
    private static void pickFonts() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        Set<String> have = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());

        for (var name : new String[] { "Segoe UI Variable Text", "Segoe UI", "Inter", "Noto Sans", "DejaVu Sans" }) {
            if (have.contains(name)) {
                ui = new Font(name, Font.PLAIN, 14);
                break;
            }
        }

        for (var name : new String[] { "Cascadia Mono", "Cascadia Code", "Consolas", "JetBrains Mono", "DejaVu Sans Mono" }) {
            if (have.contains(name)) {
                mono = new Font(name, Font.PLAIN, 13);
                break;
            }
        }
    }

    static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
