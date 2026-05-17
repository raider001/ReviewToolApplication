package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import com.kalynx.swingtheme.themedcomponents.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * DraggableTabbedPane - a themed tabbed pane that supports drag-to-reorder, double-click rename,
 * right-click delete, and a persistent "+" tab at the far right for adding new tabs.
 * <p>
 * Consumers of this component interact through callbacks rather than direct manipulation;
 * all persistence is the responsibility of the owning panel.
 */
public class DraggableTabbedPane extends ThemedTabbedPane {

    private static final String ADD_TAB_TITLE = "  +  ";

    private int dragSourceIndex = -1;
    private int lastUserSelectedIndex = 0;

    private Runnable onAddRequested;
    private BiConsumer<Integer, String> onTabRenamed;
    private Consumer<Integer> onTabRemoved;
    private Consumer<Integer> onEditFilterRequested;
    private Runnable onOrderChanged;

    public DraggableTabbedPane() {
        super(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        insertAddTab();
        setupDragAndDrop();
        setupSelectionGuard();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Adds a user-created tab before the "+" tab.
     *
     * @param name    the tab title
     * @param content the component to display in the tab
     */
    public void addUserTab(String name, Component content) {
        int insertAt = getTabCount() - 1;
        insertTab(name, null, content, null, insertAt);
    }

    /**
     * Removes the user tab at {@code index}. The index must not be the "+" tab.
     *
     * @param index zero-based index of the user tab to remove
     */
    public void removeDraggableTab(int index) {
        if (index < 0 || index >= getUserTabCount()) {
            return;
        }
        remove(index);
        if (lastUserSelectedIndex >= getUserTabCount()) {
            lastUserSelectedIndex = Math.max(0, getUserTabCount() - 1);
        }
    }

    /**
     * Returns the number of user-defined tabs (excluding the "+" tab).
     *
     * @return user tab count
     */
    public int getUserTabCount() {
        return Math.max(0, getTabCount() - 1);
    }

    /**
     * Renames the user tab at {@code index}.
     *
     * @param index   zero-based index of the tab
     * @param newName the new title to apply
     */
    public void renameUserTab(int index, String newName) {
        if (index >= 0 && index < getUserTabCount()) {
            setTitleAt(index, newName);
        }
    }

    /**
     * Updates the displayed count suffix on the tab at {@code index}.
     *
     * @param index     tab index
     * @param baseName  the base tab name
     * @param count     the count to append
     */
    public void setUserTabCount(int index, String baseName, int count) {
        if (index >= 0 && index < getUserTabCount()) {
            setTitleAt(index, baseName + " (" + count + ")");
        }
    }

    /**
     * Registers a callback invoked when the "+" tab is clicked.
     *
     * @param listener the callback
     */
    public void setOnAddRequested(Runnable listener) {
        this.onAddRequested = listener;
    }

    /**
     * Registers a callback invoked when a tab is renamed.
     * Receives the zero-based tab index and the new name.
     *
     * @param listener the callback
     */
    public void setOnTabRenamed(BiConsumer<Integer, String> listener) {
        this.onTabRenamed = listener;
    }

    /**
     * Registers a callback invoked when a tab is removed via the context menu.
     * Receives the zero-based tab index at the time of removal.
     *
     * @param listener the callback
     */
    public void setOnTabRemoved(Consumer<Integer> listener) {
        this.onTabRemoved = listener;
    }

    /**
     * Registers a callback invoked when "Edit Filter…" is chosen from a tab's context menu.
     * Receives the zero-based tab index.
     *
     * @param listener the callback
     */
    public void setOnEditFilterRequested(Consumer<Integer> listener) {
        this.onEditFilterRequested = listener;
    }

    /**
     * Registers a callback invoked after a drag-reorder operation completes.
     *
     * @param listener the callback
     */
    public void setOnOrderChanged(Runnable listener) {
        this.onOrderChanged = listener;
    }

    // -------------------------------------------------------------------------
    // Internal setup
    // -------------------------------------------------------------------------

    private void insertAddTab() {
        addTab(ADD_TAB_TITLE, new JPanel());
    }

    private void setupSelectionGuard() {
        addChangeListener(_ -> {
            int selected = getSelectedIndex();
            if (selected < 0) return;
            if (selected == getTabCount() - 1) {
                SwingUtilities.invokeLater(() -> {
                    int restore = Math.min(lastUserSelectedIndex, Math.max(0, getUserTabCount() - 1));
                    setSelectedIndex(restore);
                });
            } else {
                lastUserSelectedIndex = selected;
            }
        });
    }

    private void setupDragAndDrop() {
        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int idx = indexAtLocation(e.getX(), e.getY());
                if (SwingUtilities.isLeftMouseButton(e) && idx == getTabCount() - 1) {
                    if (onAddRequested != null) {
                        onAddRequested.run();
                    }
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && idx >= 0 && idx < getUserTabCount()) {
                    dragSourceIndex = idx;
                }
                if (e.getClickCount() == 2 && idx >= 0 && idx < getUserTabCount()) {
                    promptRename(idx);
                } else if (SwingUtilities.isRightMouseButton(e) && idx >= 0 && idx < getUserTabCount()) {
                    showContextMenu(idx, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragSourceIndex >= 0) {
                    dragSourceIndex = -1;
                    if (onOrderChanged != null) {
                        onOrderChanged.run();
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragSourceIndex < 0) return;
                int targetIndex = indexAtLocation(e.getX(), e.getY());
                if (targetIndex < 0 || targetIndex >= getUserTabCount() || targetIndex == dragSourceIndex) {
                    return;
                }
                moveTab(dragSourceIndex, targetIndex);
                dragSourceIndex = targetIndex;
                setSelectedIndex(dragSourceIndex);
            }
        };

        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    private void moveTab(int from, int to) {
        Component content = getComponentAt(from);
        String    title   = getTitleAt(from);
        Icon      icon    = getIconAt(from);
        String    tip     = getToolTipTextAt(from);

        remove(from);
        insertTab(title, icon, content, tip, to);
        setSelectedIndex(to);
    }

    private void promptRename(int index) {
        String currentName = stripCount(getTitleAt(index));
        ThemedPopupDialog dialog = new ThemedPopupDialog(this, "Rename Tab");
        dialog.setDialogSize(360, 170);

        ThemedTextField nameField = new ThemedTextField(20);
        nameField.setText(currentName);
        ThemedButton okButton     = new ThemedButton("OK");
        ThemedButton cancelButton = new ThemedButton("Cancel");

        JPanel content = dialog.getContentPanel();
        content.setLayout(new MigLayout("", "[grow]", "[]8[]16[]"));
        content.add(new ThemedLabel("Enter new tab name:"), "wrap");
        content.add(nameField, "growx, wrap");

        ThemedPanel buttons = new ThemedPanel();
        buttons.setLayout(new MigLayout("insets 0", "[grow][]8[]", ""));
        buttons.add(new ThemedPanel(), "grow");
        buttons.add(cancelButton, "");
        buttons.add(okButton, "");
        content.add(buttons, "growx");

        okButton.addActionListener(_ -> {
            String newName = nameField.getText().trim();
            if (!newName.isBlank()) {
                setTitleAt(index, newName);
                if (onTabRenamed != null) {
                    onTabRenamed.accept(index, newName);
                }
            }
            dialog.dispose();
        });
        cancelButton.addActionListener(_ -> dialog.dispose());
        nameField.addActionListener(_ -> okButton.doClick());

        dialog.setVisible(true);
    }

    private void showContextMenu(int index, int x, int y) {
        ThemedPopupMenu menu = new ThemedPopupMenu();

        ThemedMenuItem editItem = new ThemedMenuItem("Edit Filter…");
        editItem.addActionListener(_ -> {
            if (onEditFilterRequested != null) {
                onEditFilterRequested.accept(index);
            }
        });
        menu.add(editItem);

        ThemedMenuItem renameItem = new ThemedMenuItem("Rename…");
        renameItem.addActionListener(_ -> promptRename(index));
        menu.add(renameItem);

        ThemedMenuItem deleteItem = new ThemedMenuItem("Delete Tab");
        deleteItem.addActionListener(_ -> {
            if (onTabRemoved != null) {
                onTabRemoved.accept(index);
            }
            removeDraggableTab(index);
        });
        menu.add(deleteItem);

        menu.show(this, x, y);
    }

    /**
     * Strips any trailing " (N)" count suffix from a tab title.
     *
     * @param title the raw tab title
     * @return the base name without count
     */
    private String stripCount(String title) {
        int parenIdx = title.lastIndexOf(" (");
        return parenIdx >= 0 ? title.substring(0, parenIdx) : title;
    }
}








