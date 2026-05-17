package com.kalynx.serverlessreviewtool.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * EasterEggSpritePanel renders an animated sprite that walks back and forth
 * across the title bar. Frames are loaded from the classpath and played at
 * approximately 7 fps. The sprite flips horizontally when it reverses direction.
 * The panel starts invisible; call {@link #activate()} to reveal it.
 */
public class EasterEggSpritePanel extends JPanel {

    private static final int FRAME_COUNT = 5;
    private static final int FRAME_DELAY_MS = 143;
    private static final int MOVE_SPEED = 3;
    private static final int VERTICAL_PADDING = 4;

    private final List<BufferedImage> frames = new ArrayList<>();
    private int currentFrame = 0;
    private int xPos = 0;
    private boolean movingRight = true;
    private boolean activated = false;

    /**
     * Creates the panel in a hidden, inactive state.
     */
    public EasterEggSpritePanel() {
        setOpaque(false);
        setVisible(false);
        loadFrames();
    }

    /**
     * Makes the sprite visible and starts the animation timer.
     * Safe to call multiple times — only activates once.
     */
    public void activate() {
        if (activated) return;
        activated = true;
        setVisible(true);
        startTimer();
    }

    private void loadFrames() {
        for (int i = 0; i < FRAME_COUNT; i++) {
            String path = String.format("/frame%04d.png", i);
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    frames.add(ImageIO.read(is));
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void startTimer() {
        new Timer(FRAME_DELAY_MS, _ -> {
            if (frames.isEmpty()) return;
            currentFrame = (currentFrame + 1) % frames.size();
            advancePosition();
            repaint();
        }).start();
    }

    private void advancePosition() {
        int spriteW = scaledSpriteWidth();
        int maxX = getWidth() - spriteW;
        if (maxX <= 0) return;

        xPos += movingRight ? MOVE_SPEED : -MOVE_SPEED;

        if (xPos >= maxX) {
            xPos = maxX;
            movingRight = false;
        } else if (xPos <= 0) {
            xPos = 0;
            movingRight = true;
        }
    }

    private int scaledSpriteHeight() {
        return Math.max(1, getHeight() - VERTICAL_PADDING);
    }

    private int scaledSpriteWidth() {
        if (frames.isEmpty()) return 0;
        BufferedImage img = frames.getFirst();
        int h = scaledSpriteHeight();
        return (int) ((double) h / img.getHeight() * img.getWidth());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (frames.isEmpty()) return;

        BufferedImage frame = frames.get(currentFrame);
        int spriteW = scaledSpriteWidth();
        int spriteH = scaledSpriteHeight();
        int spriteY = (getHeight() - spriteH) / 2;

        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        if (movingRight) {
            g2d.drawImage(frame, xPos, spriteY, xPos + spriteW, spriteY + spriteH,
                    0, 0, frame.getWidth(), frame.getHeight(), null);
        } else {
            g2d.drawImage(frame, xPos + spriteW, spriteY, xPos, spriteY + spriteH,
                    0, 0, frame.getWidth(), frame.getHeight(), null);
        }

        g2d.dispose();
    }
}
