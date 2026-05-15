package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import net.miginfocom.swing.MigLayout;

import java.awt.*;

/**
 * CommentCard - Simple chat-style comment display for flat conversations
 */
public class CommentCard extends ThemedPanel {

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final ReviewComment comment;

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
        authorLabel.setFont(new Font("Segoe UI", Font.BOLD, themeManager.scale(11)));
        authorLabel.setForeground(theme.getAccentColor());
        topPanel.add(authorLabel);

        ThemedLabel timeLabel = new ThemedLabel(comment.getTimestamp());
        timeLabel.setFont(new Font("Segoe UI", Font.PLAIN, themeManager.scale(9)));
        timeLabel.setForeground(theme.getSecondaryTextColor());
        topPanel.add(timeLabel);

        add(topPanel, "growx, wrap");

        String formattedText = formatCommentText(comment.getText(), theme);
        ThemedLabel textLabel = new ThemedLabel(formattedText);
        textLabel.setFont(new Font("Segoe UI", Font.PLAIN, themeManager.scale(11)));
        add(textLabel, "growx");

        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
    }

    private String formatCommentText(String text, Theme theme) {
        StringBuilder html = new StringBuilder("<html>");

        String[] parts = text.split("```");
        boolean isCode = false;

        for (String part : parts) {
            if (isCode) {
                Color codeBg = theme.getInputBackground();
                String bgColor = String.format("#%02x%02x%02x",
                    codeBg.getRed(), codeBg.getGreen(), codeBg.getBlue());
                Color codeText = theme.getForegroundColor();
                String textColor = String.format("#%02x%02x%02x",
                    codeText.getRed(), codeText.getGreen(), codeText.getBlue());

                html.append("<div style='background-color: ").append(bgColor)
                    .append("; padding: 4px; margin: 4px 0; font-family: Consolas, monospace; font-size: 10px; color: ")
                    .append(textColor).append(";'><pre>")
                    .append(escapeHtml(part.trim()))
                    .append("</pre></div>");
            } else {
                html.append(escapeHtml(part).replace("\n", "<br>"));
            }
            isCode = !isCode;
        }

        html.append("</html>");
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







