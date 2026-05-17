package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.reviewformdialog;

import com.kalynx.swingtheme.ComponentModel;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.utils.Validator;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ReviewDetailsPanel extends ThemedPanel {

    private static final int GAP = 12;
    private static final int FIELD_H = 28;
    private static final int SUMMARY_H = 120;

    private final ThemeManager themeManager;
    private final ThemedTextField titleField;
    private final ThemedSearchableComboBox authorCombo;
    private final ThemedRichTextEditor summaryArea;

    private boolean updatingFromModel = false;
    private transient String authorValueOnFocusGained;

    public ReviewDetailsPanel(ComponentModel<String> titleModel,
                              ComponentModel<String> authorModel,
                              ComponentModel<String> summaryModel,
                              ComponentModel<List<String>> availableAuthorsModel) {
        this.themeManager = ThemeManager.getInstance();

        setLayout(new MigLayout(
            "insets 10 12 12 12, gap " + GAP + " 8",
            "[90!][grow,fill]",
            "[]8[]8[grow, fill]"
        ));
        setBorder(ThemedTitledBorder.create("Review Details"));

        titleField = new ThemedTextField(20);
        titleField.setPreferredSize(new Dimension(0, themeManager.scale(FIELD_H)));

        authorCombo = new ThemedSearchableComboBox(new ArrayList<>());
        authorCombo.setPreferredSize(new Dimension(0, themeManager.scale(FIELD_H)));
        authorCombo.setToolTipText("Search for the review author");
        authorCombo.bindTo(availableAuthorsModel);

        summaryArea = new ThemedRichTextEditor();

        configureLayout();
        bindToModels(titleModel, authorModel, summaryModel);
    }

    private void bindToModels(ComponentModel<String> titleModel,
                              ComponentModel<String> authorModel,
                              ComponentModel<String> summaryModel) {
        titleModel.addChangeListener(value -> SwingUtilities.invokeLater(() -> {
            updatingFromModel = true;
            titleField.setText(value != null ? value : "");
            updatingFromModel = false;
        }));

        authorModel.addChangeListener(value -> SwingUtilities.invokeLater(() -> {
            updatingFromModel = true;
            authorCombo.setSelectedItem(value != null ? value : "");
            updatingFromModel = false;
        }));

        summaryModel.addChangeListener(value -> SwingUtilities.invokeLater(() -> {
            String safeValue = value != null ? value : "";
            if (safeValue.equals(summaryArea.getHtml())) {
                return;
            }
            updatingFromModel = true;
            summaryArea.setHtml(safeValue);
            updatingFromModel = false;
        }));

        titleField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateModel(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateModel(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateModel(); }
            private void updateModel() {
                if (!updatingFromModel) {
                    titleModel.setValue(titleField.getText());
                }
            }
        });

        JTextField authorEditor = (JTextField) authorCombo.getEditor().getEditorComponent();
        authorEditor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateModel(); }
            public void removeUpdate(DocumentEvent e) { updateModel(); }
            public void changedUpdate(DocumentEvent e) { updateModel(); }
            private void updateModel() {
                if (!updatingFromModel) {
                    authorModel.setValue(authorEditor.getText());
                }
            }
        });

        authorCombo.addActionListener(ignored -> {
            if (!updatingFromModel) {
                authorModel.setValue(getAuthor());
            }
        });

        summaryArea.getEditorPane().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateModel(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateModel(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateModel(); }
            private void updateModel() {
                if (!updatingFromModel) {
                    summaryModel.setValue(summaryArea.getHtml());
                }
            }
        });

        if (titleModel.getValue() != null) titleField.setText(titleModel.getValue());
        if (authorModel.getValue() != null) authorCombo.setSelectedItem(authorModel.getValue());
        if (summaryModel.getValue() != null) summaryArea.setHtml(summaryModel.getValue());
    }

    private void configureLayout() {
        add(rightLabel("Title:"));
        add(titleField, "growx, wmin 0, wrap");

        add(rightLabel("Author:"));
        add(authorCombo, "growx, wmin 0, wrap");

        summaryArea.setPreferredSize(new Dimension(0, themeManager.scale(SUMMARY_H)));

        add(rightLabel("Summary:"), "aligny top, gaptop 4");
        add(summaryArea, "grow, wmin 0");
    }

    private ThemedLabel rightLabel(String text) {
        ThemedLabel label = new ThemedLabel(text);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    public String getTitle() {
        return titleField.getText();
    }

    public String getAuthor() {
        Object selected = authorCombo.getSelectedItem();
        if (selected != null) {
            return selected.toString();
        }
        JTextField authorEditor = (JTextField) authorCombo.getEditor().getEditorComponent();
        return authorEditor.getText();
    }

    public String getSummary() {
        return summaryArea.getHtml();
    }

    public void setupValidation(Validator validator, Consumer<String> onValidValueSaved) {
        titleField.setupValidation(validator, onValidValueSaved);
    }

    public void setupAuthorValidation(Validator validator, Consumer<String> onValidValueSaved) {
        JTextField authorEditor = (JTextField) authorCombo.getEditor().getEditorComponent();
        authorEditor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                authorValueOnFocusGained = authorEditor.getText();
            }

            @Override
            public void focusLost(FocusEvent e) {
                String currentValue = authorEditor.getText().trim();
                if (!currentValue.equals(authorValueOnFocusGained)) {
                    Validator.ValidationResult result = validator.validate(currentValue);
                    if (result.isValid()) {
                        onValidValueSaved.accept(currentValue);
                    }
                }
            }
        });

        authorCombo.setOnApply(value -> {
            String appliedValue = value == null ? "" : value.trim();
            Validator.ValidationResult result = validator.validate(appliedValue);
            if (result.isValid()) {
                onValidValueSaved.accept(appliedValue);
            }
        });
    }

    public void setupSummaryValidation(Validator validator, Consumer<String> onValidValueSaved) {
        summaryArea.getEditorPane().addFocusListener(new FocusAdapter() {
            private String valueOnFocusGained;

            @Override
            public void focusGained(FocusEvent e) {
                valueOnFocusGained = summaryArea.getHtml();
            }

            @Override
            public void focusLost(FocusEvent e) {
                String currentValue = summaryArea.getHtml();
                if (!currentValue.equals(valueOnFocusGained)) {
                    Validator.ValidationResult result = validator.validate(currentValue);
                    if (result.isValid()) {
                        onValidValueSaved.accept(currentValue);
                    }
                }
            }
        });
    }
}
