package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

import javax.swing.DefaultListModel;

/**
 * FilterableDefaultListModel - A DefaultListModel that supports filtering and surgical item updates.
 *
 * <p>Backs items in a {@link LinkedHashMap} keyed by a caller-supplied ID extractor so that
 * {@link #upsertItem} and {@link #removeItemById} run in O(1) without a full list rebuild.
 *
 * @param <E> The type of elements in this list model
 */
public class FilterableDefaultListModel<E> extends DefaultListModel<E> {
    @Serial
    private static final long serialVersionUID = 1L;

    private transient final LinkedHashMap<String, E> allItems = new LinkedHashMap<>();
    private transient final Function<E, String> idExtractor;
    private String titleFilter = "";
    private String authorFilter = "";
    private transient List<String> repositoryFilter = new ArrayList<>();
    private transient final FilterPredicate<E> filterPredicate;

    public FilterableDefaultListModel(Function<E, String> idExtractor, FilterPredicate<E> filterPredicate) {
        this.idExtractor = idExtractor;
        this.filterPredicate = filterPredicate;
    }

    /**
     * Replaces all items in one operation, calling {@link #applyFilter()} only once.
     */
    public void setItems(List<E> items) {
        allItems.clear();
        items.forEach(item -> allItems.put(idExtractor.apply(item), item));
        applyFilter();
    }

    /**
     * Inserts or updates a single item without rebuilding the full list.
     *
     * <p>If the item is already visible, it is updated in place. If it is new, it is appended.
     * The caller is responsible for deciding whether this item belongs in this list at all
     * (i.e. passes the tab's filter); this method does not re-evaluate tab-level filters.
     */
    public void upsertItem(E item) {
        String id = idExtractor.apply(item);
        boolean wasPresent = allItems.containsKey(id);
        allItems.put(id, item);

        int visibleIndex = findVisibleIndexById(id);
        if (visibleIndex >= 0) {
            super.setElementAt(item, visibleIndex);
        } else if (!wasPresent || passesLocalFilter(item)) {
            super.addElement(item);
        }
    }

    /**
     * Removes the item with the given ID from both the backing map and the visible list.
     * No-op if the ID is not present.
     */
    public void removeItemById(String id) {
        if (allItems.remove(id) == null) return;
        int visibleIndex = findVisibleIndexById(id);
        if (visibleIndex >= 0) {
            super.removeElementAt(visibleIndex);
        }
    }

    @Override
    public void addElement(E element) {
        allItems.put(idExtractor.apply(element), element);
        applyFilter();
    }

    @Override
    public void insertElementAt(E element, int index) {
        allItems.put(idExtractor.apply(element), element);
        applyFilter();
    }

    @Override
    public void setElementAt(E element, int index) {
        if (index >= 0 && index < super.getSize()) {
            allItems.put(idExtractor.apply(element), element);
        }
        applyFilter();
    }

    @Override
    @SuppressWarnings({"SuspiciousMethodCalls", "unchecked"})
    public boolean removeElement(Object obj) {
        try {
            String id = idExtractor.apply((E) obj);
            boolean removed = allItems.remove(id) != null;
            if (removed) applyFilter();
            return removed;
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public E remove(int index) {
        if (index >= 0 && index < super.getSize()) {
            E element = super.getElementAt(index);
            allItems.remove(idExtractor.apply(element));
            applyFilter();
            return element;
        }
        return null;
    }

    @Override
    public void removeElementAt(int index) {
        remove(index);
    }

    @Override
    public void removeAllElements() {
        allItems.clear();
        super.clear();
    }

    public void filter(String title, String author, List<String> repositories) {
        this.titleFilter = (title != null) ? title.toLowerCase().trim() : "";
        this.authorFilter = (author != null) ? author.toLowerCase().trim() : "";
        this.repositoryFilter = (repositories != null) ? new ArrayList<>(repositories) : new ArrayList<>();
        applyFilter();
    }

    @SuppressWarnings("unused")
    public void clearFilter() {
        this.titleFilter = "";
        this.authorFilter = "";
        this.repositoryFilter.clear();
        applyFilter();
    }

    private void applyFilter() {
        super.clear();
        for (E item : allItems.values()) {
            if (passesLocalFilter(item)) {
                super.addElement(item);
            }
        }
    }

    private boolean passesLocalFilter(E item) {
        boolean hasRepositoryFilter = !repositoryFilter.isEmpty();
        return filterPredicate.matches(item, titleFilter, authorFilter,
            hasRepositoryFilter ? repositoryFilter : null);
    }

    private int findVisibleIndexById(String id) {
        for (int i = 0; i < super.getSize(); i++) {
            if (id.equals(idExtractor.apply(super.getElementAt(i)))) {
                return i;
            }
        }
        return -1;
    }

    @FunctionalInterface
    public interface FilterPredicate<E> {
        boolean matches(E item, String titleFilter, String authorFilter, List<String> repositoryFilter);
    }
}