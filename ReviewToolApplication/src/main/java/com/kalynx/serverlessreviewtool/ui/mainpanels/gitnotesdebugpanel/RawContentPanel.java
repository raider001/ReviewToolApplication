package com.kalynx.serverlessreviewtool.ui.mainpanels.gitnotesdebugpanel;

import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTextArea;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * RawContentPanel displays the raw NDJSON content of a selected git notes ref
 * and provides a button to copy that content to the system clipboard.
 */
public class RawContentPanel extends ThemedPanel {

    private final ThemedTextArea contentArea = new ThemedTextArea();
    private final ThemedScrollPane scrollPane = new ThemedScrollPane(contentArea);
    private final ThemedButton copyButton = new ThemedButton("Copy to Clipboard");

    /**
     * Constructs a RawContentPanel with a monospaced content area and a copy button.
     */
    public RawContentPanel() {
        setBorder(ThemedTitledBorder.create("Raw Notes Content"));
        contentArea.setEditable(false);
        contentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        contentArea.setLineWrap(false);
        configureLayout();
        setupListeners();
    }

    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 5", "[grow][]", "[][grow]"));
        add(copyButton, "skip 1, align right, wrap");
        add(scrollPane, "span, grow");
    }

    private void setupListeners() {
        copyButton.addActionListener(_ -> copyContentToClipboard());
    }

    private void copyContentToClipboard() {
        String text = contentArea.getText();
        if (text != null && !text.isBlank()) {
            Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
        }
    }

    /**
     * Replaces the displayed content with the provided text, scrolling back to the top.
     *
     * @param content the raw notes content to display
     */
    public void setContent(String content) {
        SwingUtilities.invokeLater(() -> {
            contentArea.setText(content);
            contentArea.setCaretPosition(0);
        });
    }

    /**
     * Clears the displayed content.
     */
    public void clear() {
        SwingUtilities.invokeLater(() -> contentArea.setText(""));
    }
}


