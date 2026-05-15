package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel.reviewitem;

import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.swingtheme.theme.ScalableComponent;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.utils.TimeFormatter;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class ReviewItemCellRenderer extends ThemedPanel implements ListCellRenderer<ReviewItem> {
    private final ThemedLabel titleLabel;
    private final ThemedLabel metadataLabel;
    private final ThemedLabel timeLabel;
    /** Pill badge – colour and text updated per row. */
    private final JLabel statusBadge;
    private Color badgeColor = Color.GRAY;

    public ReviewItemCellRenderer() {
        setLayout(new BorderLayout(themeManager.scale(10), themeManager.scale(2)));
        setBorder(BorderFactory.createEmptyBorder(
                themeManager.scale(6), themeManager.scale(12),
                themeManager.scale(6), themeManager.scale(12)));

        ThemedPanel leftPanel = new ThemedPanel();
        leftPanel.setOpaque(false);
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));

        titleLabel = new ThemedLabel();
        titleLabel.setFont(ScalableComponent.createScaledFont("Segoe UI", Font.BOLD, 13));

        metadataLabel = new ThemedLabel();
        metadataLabel.setFont(ScalableComponent.createScaledFont("Segoe UI", Font.PLAIN, 11));

        leftPanel.add(titleLabel);
        leftPanel.add(Box.createVerticalStrut(themeManager.scale(2)));
        leftPanel.add(metadataLabel);

        ThemedPanel rightPanel = new ThemedPanel();
        rightPanel.setOpaque(false);
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setAlignmentX(RIGHT_ALIGNMENT);

        // Pill-shaped status badge painted via custom JLabel
        statusBadge = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = getHeight();
                g2.setColor(badgeColor);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        statusBadge.setOpaque(false);
        statusBadge.setForeground(Color.WHITE);
        statusBadge.setFont(ScalableComponent.createScaledFont("Segoe UI", Font.BOLD, 10));
        statusBadge.setBorder(BorderFactory.createEmptyBorder(
                themeManager.scale(2), themeManager.scale(8),
                themeManager.scale(2), themeManager.scale(8)));
        statusBadge.setAlignmentX(RIGHT_ALIGNMENT);

        timeLabel = new ThemedLabel();
        timeLabel.setFont(ScalableComponent.createScaledFont("Segoe UI", Font.PLAIN, 10));
        timeLabel.setAlignmentX(RIGHT_ALIGNMENT);

        rightPanel.add(statusBadge);
        rightPanel.add(Box.createVerticalStrut(themeManager.scale(2)));
        rightPanel.add(timeLabel);

        add(leftPanel,  BorderLayout.CENTER);
        add(rightPanel, BorderLayout.EAST);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends ReviewItem> list, ReviewItem value,
                                                  int index, boolean isSelected, boolean cellHasFocus) {
        Theme theme = themeManager.getCurrentTheme();

        if (isSelected) {
            setBackground(theme.getAccentColor());
            titleLabel.setForeground(Color.WHITE);
            metadataLabel.setForeground(new Color(220, 220, 220));
            timeLabel.setForeground(new Color(220, 220, 220));
        } else {
            setBackground(index % 2 == 0 ? theme.getBackgroundColor() : theme.getButtonBackground());
            titleLabel.setForeground(theme.getForegroundColor());
            metadataLabel.setForeground(theme.getSecondaryTextColor());
            timeLabel.setForeground(theme.getSecondaryTextColor());
        }

        titleLabel.setText(value.getTitle());
        String repositories = String.join(", ", value.getRepositories());
        metadataLabel.setText(value.getAuthor() + "  ·  " + repositories);
        timeLabel.setText(TimeFormatter.formatRelativeTime(value.getLastUpdate()));

        // Status pill
        ReviewStatus status = value.getStatus();
        badgeColor = status.getColor();
        statusBadge.setText(status.getDisplayName());

        return this;
    }
}