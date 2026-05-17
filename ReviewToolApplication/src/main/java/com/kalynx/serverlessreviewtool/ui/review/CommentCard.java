package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.swingtheme.themedcomponents.ThemedHtmlLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import net.miginfocom.swing.MigLayout;

import java.awt.*;

/**
 * CommentCard - Simple chat-style comment display for flat conversations.
 */
public class CommentCard extends ThemedPanel {

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final ReviewComment comment;
    private final ThemedHtmlLabel textLabel = new ThemedHtmlLabel();

    public CommentCard(ReviewComment comment) {
        this.comment = comment;
        configureLayout();
        buildUI();
    }

    private void configureLayout() {
        Theme theme = themeManager.getCurrentTheme();

        setLayout(new MigLayout("fill, insets 4 8 4 8", "[grow]", "[]2[]"));
        setBackground(theme.getInputBackground());
    }

    private void buildUI() {
        Theme theme = themeManager.getCurrentTheme();

        ThemedPanel topPanel = new ThemedPanel(new MigLayout("insets 0", "[]4[]push", "[]"));
        topPanel.setOpaque(false);

        ThemedLabel authorLabel = new ThemedLabel(comment.getAuthor());
        authorLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.BOLD, themeManager.scale(11)));
        authorLabel.setForeground(theme.getAccentColor());
        topPanel.add(authorLabel);

        ThemedLabel timeLabel = new ThemedLabel(comment.getTimestamp());
        timeLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(9)));
        timeLabel.setForeground(theme.getSecondaryTextColor());
        topPanel.add(timeLabel);

        add(topPanel, "growx, wrap");

        textLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(11)));
        textLabel.setHtmlContent(formatCommentText(comment.getText()));
        add(textLabel, "growx");

        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
    }

    private String formatCommentText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        if (text.trim().toLowerCase().startsWith("<html")) {
            return text;
        }

        if (!text.contains("```")) {
            return escapeHtml(text).replace("\n", "<br>");
        }

        StringBuilder html = new StringBuilder();
        String[] parts = text.split("```");
        boolean isCode = false;

        for (String part : parts) {
            if (isCode) {
                html.append("<pre>")
                    .append(escapeHtml(part.trim()))
                    .append("</pre>");
            } else {
                html.append(escapeHtml(part).replace("\n", "<br>"));
            }
            isCode = !isCode;
        }

        return html.toString();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
}
