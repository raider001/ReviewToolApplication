package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

public class SwipeActionPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwipeActionPanel.class);

    private static final int EDGE_TRIGGER_ZONE = 15;
    private static final float PANEL_WIDTH_RATIO = 0.75f;
    private static final float FULL_PULL_THRESHOLD_RATIO = 0.90f;
    private static final int HOVER_PEEK_WIDTH = 10;
    private static final int WINDOW_RESIZE_ZONE = 10;

    private final JPanel contentPanel;
    private final JPanel leftPullPanel;
    private final JPanel rightPullPanel;
    private final JPanel glassPane;
    private JLayeredPane layeredPane;

    private boolean isDragging = false;
    private int dragStartX = 0;
    private int currentDragOffset = 0;
    private boolean isLeftPull = false;
    private boolean isLeftHover = false;
    private boolean isRightHover = false;

    private float leftPanelAlpha = 0.0f;
    private float rightPanelAlpha = 0.0f;
    private javax.swing.Timer fadeTimer;

    private boolean enabled = false;

    private Runnable onApprove;
    private Runnable onRequestChanges;

    public SwipeActionPanel(JPanel contentPanel) {
        this.contentPanel = contentPanel;
        this.leftPullPanel = createPullPanel("Request Changes", true);
        this.rightPullPanel = createPullPanel("Approve", false);
        this.glassPane = createGlassPane();

        setBorder(null);
        setOpaque(true);

        initializeFadeTimer();
        configureLayout();
        setupListeners();
    }

    private void initializeFadeTimer() {
        fadeTimer = new javax.swing.Timer(16, ignored -> {
            boolean needsUpdate = false;

            if (isLeftHover && leftPanelAlpha < 0.8f) {
                leftPanelAlpha = Math.min(0.8f, leftPanelAlpha + 0.08f);
                needsUpdate = true;
            } else if (!isLeftHover && !isDragging && leftPanelAlpha > 0.0f) {
                leftPanelAlpha = Math.max(0.0f, leftPanelAlpha - 0.08f);
                if (leftPanelAlpha < 0.01f) {
                    leftPanelAlpha = 0.0f;
                }
                needsUpdate = true;
            }

            if (isRightHover && rightPanelAlpha < 0.8f) {
                rightPanelAlpha = Math.min(0.8f, rightPanelAlpha + 0.08f);
                needsUpdate = true;
            } else if (!isRightHover && !isDragging && rightPanelAlpha > 0.0f) {
                rightPanelAlpha = Math.max(0.0f, rightPanelAlpha - 0.08f);
                if (rightPanelAlpha < 0.01f) {
                    rightPanelAlpha = 0.0f;
                }
                needsUpdate = true;
            }

            if (needsUpdate) {
                leftPullPanel.repaint();
                rightPullPanel.repaint();
            }

            if (leftPanelAlpha <= 0.0f && rightPanelAlpha <= 0.0f) {
                fadeTimer.stop();
                SwingUtilities.invokeLater(() -> {
                    updatePullPanels();
                    contentPanel.repaint();
                });
            }
        });
    }

    private void configureLayout() {
        setLayout(new BorderLayout(0, 0));
        setBorder(null);

        layeredPane = new JLayeredPane();
        layeredPane.setLayout(null);
        layeredPane.setBorder(null);

        contentPanel.setBorder(null);

        add(layeredPane, BorderLayout.CENTER);

        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                layoutOverlayComponents();
            }
        });

        SwingUtilities.invokeLater(this::layoutOverlayComponents);

        layeredPane.add(contentPanel, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(glassPane, JLayeredPane.PALETTE_LAYER);
    }

    @Override
    public void doLayout() {
        super.doLayout();
        layoutOverlayComponents();
    }

    private void layoutOverlayComponents() {
        if (layeredPane == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        layeredPane.setBounds(0, 0, width, height);
        contentPanel.setBounds(0, 0, width, height);
        glassPane.setBounds(0, 0, width, height);
        updatePullPanelBounds();
    }

    private JPanel createGlassPane() {
        JPanel glass = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
            }

            @Override
            public boolean contains(int x, int y) {
                if (!enabled || isDragging) {
                    return isDragging;
                }
                Window window = SwingUtilities.getWindowAncestor(SwipeActionPanel.this);
                if (window != null) {
                    try {
                        Point screenPoint = new Point(x, y);
                        SwingUtilities.convertPointToScreen(screenPoint, this);
                        int windowLeft = window.getX();
                        int windowRight = window.getX() + window.getWidth();
                        if (screenPoint.x < windowLeft + WINDOW_RESIZE_ZONE
                                || screenPoint.x > windowRight - WINDOW_RESIZE_ZONE) {
                            return false;
                        }
                    } catch (Exception ignored) {
                        return false;
                    }
                }
                return x < EDGE_TRIGGER_ZONE || x > getWidth() - EDGE_TRIGGER_ZONE;
            }
        };
        glass.setOpaque(false);
        glass.setVisible(true);
        glass.setBorder(null);
        return glass;
    }

    private JPanel createPullPanel(String text, boolean isLeftPanel) {
        JPanel panel = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                float alpha = isLeftPanel ? leftPanelAlpha : rightPanelAlpha;
                if (alpha < 0.01f) {
                    return;
                }

                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                Color baseColor = isLeftPanel
                    ? themeManager.getCurrentTheme().getChangesRequestedColor()
                    : themeManager.getCurrentTheme().getApprovedColor();

                int width = getWidth();
                int height = getHeight();

                Color darkerColor = new Color(
                    Math.max(0, baseColor.getRed() - 50),
                    Math.max(0, baseColor.getGreen() - 50),
                    Math.max(0, baseColor.getBlue() - 50)
                );

                GradientPaint gradient;
                if (isLeftPanel) {
                    gradient = new GradientPaint(0, 0, baseColor, width * 0.7f, 0, darkerColor);
                } else {
                    gradient = new GradientPaint(width, 0, baseColor, width * 0.3f, 0, darkerColor);
                }

                float baseAlpha = width > HOVER_PEEK_WIDTH ? 0.92f : 0.88f;
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, baseAlpha * alpha));
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, width, height);

                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

                if (width > HOVER_PEEK_WIDTH) {
                    int fadeHeight = 80;
                    GradientPaint topFade = new GradientPaint(
                        0, 0, new Color(0, 0, 0, 50),
                        0, fadeHeight, new Color(0, 0, 0, 0)
                    );
                    g2d.setPaint(topFade);
                    g2d.fillRect(0, 0, width, fadeHeight);

                    GradientPaint bottomFade = new GradientPaint(
                        0, height - fadeHeight, new Color(0, 0, 0, 0),
                        0, height, new Color(0, 0, 0, 50)
                    );
                    g2d.setPaint(bottomFade);
                    g2d.fillRect(0, height - fadeHeight, width, fadeHeight);
                }

                Color textColor = getContrastingTextColor(baseColor);

                int fullPullThreshold = (int) (SwipeActionPanel.this.getWidth() * PANEL_WIDTH_RATIO * FULL_PULL_THRESHOLD_RATIO);
                int progress = isDragging ? Math.min(100, (int) ((currentDragOffset / (float) fullPullThreshold) * 100)) : 0;

                if (isDragging && currentDragOffset > HOVER_PEEK_WIDTH) {
                    drawDragState(g2d, text, progress, width, height, textColor, isLeftPanel);
                }

                g2d.dispose();
            }

            @Override
            public boolean contains(int x, int y) {
                return false;
            }
        };
        panel.setOpaque(false);
        panel.setVisible(false);
        panel.setBorder(null);
        return panel;
    }


    private void drawDragState(Graphics2D g2d, String text, int progress, int width, int height, Color textColor, boolean isLeftPanel) {
        int fixedCenterY = height / 2;
        int iconOffsetFromEdge = 100;
        int centerX = isLeftPanel ? iconOffsetFromEdge : width - iconOffsetFromEdge;

        int iconSize = 56;
        int alpha = Math.min(255, (int) (progress * 2.55 * 1.5));

        g2d.setColor(new Color(0, 0, 0, Math.min(80, alpha / 3)));
        g2d.fillOval(centerX - iconSize / 2 + 2, fixedCenterY - iconSize / 2 + 2, iconSize, iconSize);

        g2d.setColor(new Color(0, 0, 0, Math.min(240, alpha)));
        g2d.fillOval(centerX - iconSize / 2, fixedCenterY - iconSize / 2, iconSize, iconSize);

        g2d.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(new Color(255, 255, 255, Math.max(220, alpha)));

        if (isLeftPanel) {
            int size = 20;
            g2d.drawLine(centerX - size / 2, fixedCenterY - size / 2, centerX + size / 2, fixedCenterY + size / 2);
            g2d.drawLine(centerX + size / 2, fixedCenterY - size / 2, centerX - size / 2, fixedCenterY + size / 2);
        } else {
            int[] xPoints = {centerX - 11, centerX - 3, centerX + 13};
            int[] yPoints = {fixedCenterY - 2, fixedCenterY + 8, fixedCenterY - 12};
            g2d.drawPolyline(xPoints, yPoints, 3);
        }

        g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 16f));
        FontMetrics textFm = g2d.getFontMetrics();
        int textWidth = textFm.stringWidth(text);
        g2d.setColor(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), Math.max(220, alpha)));
        g2d.drawString(text, centerX - textWidth / 2, fixedCenterY + iconSize / 2 + 22);

        if (progress >= 90) {
            g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 13f));
            String releaseText = "Release to " + (isLeftPanel ? "reject" : "approve");
            textFm = g2d.getFontMetrics();
            textWidth = textFm.stringWidth(releaseText);

            float pulse = (float) (0.7 + 0.3 * Math.sin(System.currentTimeMillis() / 120.0));
            int pulseAlpha = (int) (255 * pulse);
            g2d.setColor(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), pulseAlpha));
            g2d.drawString(releaseText, centerX - textWidth / 2, fixedCenterY + iconSize / 2 + 42);
        } else {
            g2d.setFont(g2d.getFont().deriveFont(Font.PLAIN, 12f));
            String progressText = progress + "%";
            textFm = g2d.getFontMetrics();
            textWidth = textFm.stringWidth(progressText);
            g2d.setColor(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 200));
            g2d.drawString(progressText, centerX - textWidth / 2, fixedCenterY + iconSize / 2 + 42);
        }

        int barWidth = 140;
        int barHeight = 6;
        int barCornerRadius = 6;
        int barX = centerX - barWidth / 2;
        int barY = fixedCenterY + iconSize / 2 + 54;

        g2d.setStroke(new BasicStroke(1));
        g2d.setColor(new Color(0, 0, 0, 60));
        g2d.fillRoundRect(barX + 1, barY + 1, barWidth, barHeight, barCornerRadius, barCornerRadius);

        g2d.setColor(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 100));
        g2d.fillRoundRect(barX, barY, barWidth, barHeight, barCornerRadius, barCornerRadius);

        int fillWidth = (int) (barWidth * (progress / 100.0));
        if (fillWidth > 0) {
            g2d.setColor(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 240));
            g2d.fillRoundRect(barX, barY, fillWidth, barHeight, barCornerRadius, barCornerRadius);
        }
    }

    private Color getContrastingTextColor(Color backgroundColor) {
        int luminance = (int) (0.299 * backgroundColor.getRed() +
                               0.587 * backgroundColor.getGreen() +
                               0.114 * backgroundColor.getBlue());
        return luminance > 150 ? Color.BLACK : Color.WHITE;
    }

    private void setupListeners() {
        glassPane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!enabled) return;

                if (isDragging) return;

                int x = e.getX();
                int width = glassPane.getWidth();

                boolean wasLeftHover = isLeftHover;
                boolean wasRightHover = isRightHover;

                if (x < EDGE_TRIGGER_ZONE) {
                    glassPane.setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
                    isLeftHover = true;
                    isRightHover = false;
                    LOGGER.debug("Left edge hover at x={}", x);
                } else if (x > width - EDGE_TRIGGER_ZONE) {
                    glassPane.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                    isLeftHover = false;
                    isRightHover = true;
                    LOGGER.debug("Right edge hover at x={}", x);
                } else {
                    glassPane.setCursor(Cursor.getDefaultCursor());
                    isLeftHover = false;
                    isRightHover = false;
                }

                if (wasLeftHover != isLeftHover || wasRightHover != isRightHover) {
                    updatePullPanels();
                    if (!fadeTimer.isRunning()) {
                        fadeTimer.start();
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!enabled || !isDragging) return;

                int panelWidth = (int) (getWidth() * PANEL_WIDTH_RATIO);
                int deltaX = e.getX() - dragStartX;

                if (isLeftPull) {
                    currentDragOffset = Math.max(0, Math.min(panelWidth, deltaX));
                } else {
                    currentDragOffset = Math.max(0, Math.min(panelWidth, -deltaX));
                }

                LOGGER.debug("Dragging: offset={}", currentDragOffset);
                updatePullPanels();
            }
        });

        glassPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!enabled) {
                    redispatchMouseEvent(e);
                    return;
                }

                int x = e.getX();
                int width = glassPane.getWidth();

                if (x < EDGE_TRIGGER_ZONE) {
                    isDragging = true;
                    isLeftPull = true;
                    dragStartX = e.getX();
                    currentDragOffset = 0;
                    leftPanelAlpha = 0.8f;
                    LOGGER.info("Started left pull drag");
                } else if (x > width - EDGE_TRIGGER_ZONE) {
                    isDragging = true;
                    isLeftPull = false;
                    dragStartX = e.getX();
                    currentDragOffset = 0;
                    rightPanelAlpha = 0.8f;
                    LOGGER.info("Started right pull drag");
                } else {
                    redispatchMouseEvent(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!enabled) {
                    redispatchMouseEvent(e);
                    return;
                }

                if (!isDragging) {
                    redispatchMouseEvent(e);
                    return;
                }

                boolean actionTriggered = false;

                int fullPullThreshold = (int) (getWidth() * PANEL_WIDTH_RATIO * FULL_PULL_THRESHOLD_RATIO);
                if (currentDragOffset >= fullPullThreshold) {
                    if (isLeftPull && onRequestChanges != null) {
                        LOGGER.info("Request Changes triggered by left swipe");
                        SwingUtilities.invokeLater(onRequestChanges);
                        actionTriggered = true;
                    } else if (!isLeftPull && onApprove != null) {
                        LOGGER.info("Approve triggered by right swipe");
                        SwingUtilities.invokeLater(onApprove);
                        actionTriggered = true;
                    }
                }

                isDragging = false;
                currentDragOffset = 0;
                isLeftHover = false;
                isRightHover = false;
                updatePullPanels();
                glassPane.setCursor(Cursor.getDefaultCursor());

                if (!fadeTimer.isRunning()) {
                    fadeTimer.start();
                }

                if (actionTriggered) {
                    animateSuccess();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!enabled || !isInEdgeZone(e.getX(), glassPane.getWidth())) {
                    redispatchMouseEvent(e);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!enabled || isDragging) return;

                isLeftHover = false;
                isRightHover = false;
                glassPane.setCursor(Cursor.getDefaultCursor());
                updatePullPanels();

                if (!fadeTimer.isRunning()) {
                    fadeTimer.start();
                }
            }
        });
    }

    private boolean isInEdgeZone(int x, int width) {
        return x < EDGE_TRIGGER_ZONE || x > width - EDGE_TRIGGER_ZONE;
    }

    private void redispatchMouseEvent(MouseEvent e) {
        Point glassPoint = e.getPoint();
        Point containerPoint = SwingUtilities.convertPoint(glassPane, glassPoint, contentPanel);

        Component deepComponent = SwingUtilities.getDeepestComponentAt(contentPanel, containerPoint.x, containerPoint.y);
        if (deepComponent != null) {
            Point componentPoint = SwingUtilities.convertPoint(glassPane, glassPoint, deepComponent);
            deepComponent.dispatchEvent(new MouseEvent(
                deepComponent,
                e.getID(),
                e.getWhen(),
                e.getModifiersEx(),
                componentPoint.x,
                componentPoint.y,
                e.getClickCount(),
                e.isPopupTrigger(),
                e.getButton()
            ));
        }
    }

    private void updatePullPanels() {
        int leftWidth = 0;
        int rightWidth = 0;

        if (isDragging) {
            if (isLeftPull) {
                leftWidth = currentDragOffset;
            } else {
                rightWidth = currentDragOffset;
            }
        } else {
            if (isLeftHover) {
                leftWidth = HOVER_PEEK_WIDTH;
            } else if (isRightHover) {
                rightWidth = HOVER_PEEK_WIDTH;
            } else if (leftPanelAlpha > 0.01f) {
                leftWidth = HOVER_PEEK_WIDTH;
            } else if (rightPanelAlpha > 0.01f) {
                rightWidth = HOVER_PEEK_WIDTH;
            }
        }

        int fullHeight = getHeight();
        boolean needsRepaint = false;

        boolean shouldShowLeft = (leftWidth > 0 && leftPanelAlpha > 0.01f) || (isLeftHover || isDragging && isLeftPull);
        boolean shouldShowRight = (rightWidth > 0 && rightPanelAlpha > 0.01f) || (isRightHover || isDragging && !isLeftPull);

        if (shouldShowLeft) {
            if (leftPullPanel.getParent() == null) {
                layeredPane.add(leftPullPanel, JLayeredPane.POPUP_LAYER);
            }
            leftPullPanel.setBounds(0, 0, leftWidth, fullHeight);
            leftPullPanel.setVisible(true);
        } else {
            if (leftPullPanel.getParent() != null) {
                layeredPane.remove(leftPullPanel);
                needsRepaint = true;
            }
            leftPullPanel.setVisible(false);
        }

        if (shouldShowRight) {
            if (rightPullPanel.getParent() == null) {
                layeredPane.add(rightPullPanel, JLayeredPane.POPUP_LAYER);
            }
            rightPullPanel.setBounds(getWidth() - rightWidth, 0, rightWidth, fullHeight);
            rightPullPanel.setVisible(true);
        } else {
            if (rightPullPanel.getParent() != null) {
                layeredPane.remove(rightPullPanel);
                needsRepaint = true;
            }
            rightPullPanel.setVisible(false);
        }

        if (needsRepaint) {
            contentPanel.repaint();
        }

        layeredPane.revalidate();
        layeredPane.repaint();
    }

    private void updatePullPanelBounds() {
        int fullHeight = getHeight();

        if (leftPullPanel.isVisible() && leftPullPanel.getParent() != null) {
            leftPullPanel.setBounds(0, 0, leftPullPanel.getWidth(), fullHeight);
        }
        if (rightPullPanel.isVisible() && rightPullPanel.getParent() != null) {
            rightPullPanel.setBounds(getWidth() - rightPullPanel.getWidth(), 0, rightPullPanel.getWidth(), fullHeight);
        }
    }

    private void animateSuccess() {
        Timer timer = new Timer(30, null);
        final float[] alpha = {1.0f};

        timer.addActionListener(ignored -> {
            alpha[0] -= 0.08f;

            if (alpha[0] <= 0) {
                timer.stop();
                if (leftPullPanel.getParent() != null) {
                    layeredPane.remove(leftPullPanel);
                }
                if (rightPullPanel.getParent() != null) {
                    layeredPane.remove(rightPullPanel);
                }
                layeredPane.revalidate();
                layeredPane.repaint();
            } else {
                layeredPane.repaint();
            }
        });

        timer.start();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            isDragging = false;
            currentDragOffset = 0;
            isLeftHover = false;
            isRightHover = false;
            leftPanelAlpha = 0.0f;
            rightPanelAlpha = 0.0f;
            if (fadeTimer != null && fadeTimer.isRunning()) {
                fadeTimer.stop();
            }
            updatePullPanels();
            glassPane.setCursor(Cursor.getDefaultCursor());
        }
        glassPane.repaint();
        LOGGER.debug("Swipe actions {}", enabled ? "enabled" : "disabled");
    }

    public void setOnApprove(Runnable action) {
        this.onApprove = action;
    }

    public void setOnRequestChanges(Runnable action) {
        this.onRequestChanges = action;
    }
}














































































