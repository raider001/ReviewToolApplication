package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.eventlisteners.SetOnFilterEvent;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.swingtheme.themedcomponents.ThemedComboBox;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.serverlessreviewtool.utils.DebounceTimer;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.*;

public class FilterPanel extends ThemedPanel {
    @Serial
    private static final long serialVersionUID = 1L;

    private transient final Set<SetOnFilterEvent> filterEventListeners = new HashSet<>();
    private transient DebounceTimer debounceTimer;
    private transient final RepositoryManager repositoryManager;

    private final ThemedLabel titleLabel = new ThemedLabel("Title:")
            .setThemedToolTipText("Filter reviews by title");
    private final ThemedTextField titleFilterTextField = new ThemedTextField(20);
    private final ThemedLabel authorLabel = new ThemedLabel("Author:")
            .setThemedToolTipText("Filter reviews by author");
    private final ThemedTextField authorFilterTextField = new ThemedTextField(20);
    private final ThemedLabel repositoryLabel = new ThemedLabel("Repository:")
            .setThemedToolTipText("Filter reviews by repository");
    private final ThemedComboBox<String> repositories = new ThemedComboBox<>();

    public FilterPanel(RepositoryManager repositoryManager) {
        this.repositoryManager = repositoryManager;
        configureLayout();
        setupEventListeners();
        setupRepositoryListener();
        loadRepositories();
    }

    private void setupEventListeners() {
        debounceTimer = new DebounceTimer(300, this::notifyFilterEventListener);

        DocumentListener textFieldListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounceTimer.trigger();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounceTimer.trigger();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounceTimer.trigger();
            }
        };

        titleFilterTextField.getDocument().addDocumentListener(textFieldListener);
        authorFilterTextField.getDocument().addDocumentListener(textFieldListener);

        repositories.addActionListener(debounceTimer::trigger);
    }

    private void configureLayout() {
        setLayout(new MigLayout("","[][][][][][]","[]"));
        add(titleLabel, "cell 0 0");
        add(titleFilterTextField, "cell 1 0");
        add(authorLabel, "cell 2 0");
        add(authorFilterTextField, "cell 3 0");
        add(repositoryLabel, "cell 4 0");
        add(repositories, "cell 5 0");
    }

    public FilterPanel addFilterEventListener( SetOnFilterEvent eventListener) {
        filterEventListeners.add(eventListener);
        return this;
    }

    public FilterPanel removeFilterEventListener( SetOnFilterEvent eventListener) {
        filterEventListeners.remove(eventListener);
        return this;
    }

    private void setupRepositoryListener() {
        repositoryManager.addListener(ignored -> loadRepositories());
    }

    private void loadRepositories() {
        SwingUtilities.invokeLater(() -> {
            String currentSelection = (String) repositories.getSelectedItem();

            repositories.removeAllItems();
            repositories.addItem("All Repositories");
            repositoryManager.getRepositories().forEach(repo -> repositories.addItem(repo.getName()));

            if (currentSelection != null) {
                repositories.setSelectedItem(currentSelection);
            }

            if (repositories.getSelectedItem() == null) {
                repositories.setSelectedIndex(0);
            }
        });
    }

    private void notifyFilterEventListener() {
        String selectedRepo = (String) repositories.getSelectedItem();
        List<String> repoFilter = null;

        if (selectedRepo != null && !selectedRepo.equals("All Repositories")) {
            repoFilter = Collections.singletonList(selectedRepo);
        }

        String titleFilter = titleFilterTextField.getText();
        String authorFilter = authorFilterTextField.getText();

        List<String> finalRepoFilter = repoFilter;
        filterEventListeners.forEach(listener ->
                listener.setFilterEventAction(titleFilter, authorFilter, finalRepoFilter));
    }

}
