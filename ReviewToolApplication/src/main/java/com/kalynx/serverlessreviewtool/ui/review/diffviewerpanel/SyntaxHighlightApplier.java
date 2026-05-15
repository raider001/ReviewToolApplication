package com.kalynx.serverlessreviewtool.ui.review.diffviewerpanel;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.plugin.SyntaxHighlighterPlugin;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.List;

/**
 * SyntaxHighlightApplier applies syntax-aware token colouring to a JTextPane.
 * Resolves token colours from the active plugin for the given file extension.
 */
class SyntaxHighlightApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyntaxHighlightApplier.class);

    private final PluginManager pluginManager;
    private final ThemeManager themeManager = ThemeManager.getInstance();

    SyntaxHighlightApplier(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * Applies syntax highlighting to the given text pane using the file extension of the provided file.
     *
     * @param textPane the pane to highlight
     * @param source   the source text that was set on the pane
     * @param file     the file whose extension determines the highlighter
     */
    void apply(JTextPane textPane, String source, ReviewFile file) {
        String ext = getFileExtension(file);
        if (ext.isEmpty()) return;

        pluginManager.getSyntaxHighlighterFor(ext).ifPresent(plugin -> {
            List<SyntaxHighlighterPlugin.SyntaxToken> tokens = safeTokenize(plugin, source, ext);
            if (tokens.isEmpty()) return;

            StyledDocument doc = textPane.getStyledDocument();
            boolean dark = isDarkTheme();

            for (SyntaxHighlighterPlugin.SyntaxToken token : tokens) {
                if (token.offset < 0 || token.length <= 0) continue;
                if (token.offset + token.length > doc.getLength()) continue;

                Style style = textPane.addStyle(null, null);
                StyleConstants.setForeground(style, resolveTokenColor(plugin, token.type, dark));
                doc.setCharacterAttributes(token.offset, token.length, style, false);
            }
        });
    }

    private String getFileExtension(ReviewFile file) {
        if (file == null || file.getPath() == null) return "";
        String path = file.getPath();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    private List<SyntaxHighlighterPlugin.SyntaxToken> safeTokenize(SyntaxHighlighterPlugin plugin, String source, String extension) {
        try {
            return plugin.tokenize(source);
        } catch (StackOverflowError error) {
            LOGGER.error("Syntax highlighting failed with stack overflow for extension {} ({} chars)",
                    extension, source == null ? 0 : source.length(), error);
            return List.of();
        } catch (RuntimeException error) {
            LOGGER.error("Syntax highlighting failed for extension {} ({} chars)",
                    extension, source == null ? 0 : source.length(), error);
            return List.of();
        }
    }

    private Color resolveTokenColor(SyntaxHighlighterPlugin plugin, SyntaxHighlighterPlugin.TokenType type, boolean darkTheme) {
        try {
            Object resolved = plugin.getClass()
                    .getMethod("getColorForTokenType", SyntaxHighlighterPlugin.TokenType.class, boolean.class)
                    .invoke(plugin, type, darkTheme);
            if (resolved instanceof Color color) return color;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Object resolved = plugin.getClass()
                    .getMethod("getColorForTokenType", SyntaxHighlighterPlugin.TokenType.class)
                    .invoke(plugin, type);
            if (resolved instanceof Color color) return color;
        } catch (ReflectiveOperationException ignored) {
        }

        return themeManager.getCurrentTheme().getForegroundColor();
    }

    private boolean isDarkTheme() {
        Theme theme = themeManager.getCurrentTheme();
        Color bg = theme.getBackgroundColor();
        double luminance = 0.2126 * bg.getRed() + 0.7152 * bg.getGreen() + 0.0722 * bg.getBlue();
        return luminance < 128.0;
    }
}

