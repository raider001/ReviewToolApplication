package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import java.io.Serial;

import javax.swing.DefaultListModel;
import java.util.ArrayList;
import java.util.List;

/**
 * FilterableDefaultListModel - A DefaultListModel that supports filtering
 * Maintains both a full list and a filtered view
 * Automatically manages the internal list when elements are added/removed
 *
 * @param <E> The type of elements in this list model
 */
public class FilterableDefaultListModel<E> extends DefaultListModel<E> {
    @Serial
    private static final long serialVersionUID = 1L;

    private transient final List<E> allItems = new ArrayList<>();
    private String titleFilter = "";
    private String authorFilter = "";
    private transient List<String> repositoryFilter = new ArrayList<>();
    private transient final FilterPredicate<E> filterPredicate;

    /**
     * Create a filterable list model with a custom filter predicate
     *
     * @param filterPredicate Function that determines if an item matches the current filters
     */
    public FilterableDefaultListModel(FilterPredicate<E> filterPredicate) {
        this.filterPredicate = filterPredicate;
    }

    /**
     * Replaces all items in one operation, calling {@link #applyFilter()} only once.
     * Use this instead of {@link #removeAllElements()} followed by repeated
     * {@link #addElement(Object)} calls to avoid O(n²) filter reapplication.
     *
     * @param items the new full set of items
     */
    public void setItems(List<E> items) {
        allItems.clear();
        allItems.addAll(items);
        applyFilter();
    }

    @Override
    public void addElement(E element) {
        allItems.add(element);
        applyFilter();
    }

    @Override
    public void insertElementAt(E element, int index) {
        allItems.add(element);
        applyFilter();
    }

    @Override
    public void setElementAt(E element, int index) {
        if (index >= 0 && index < super.getSize()) {
            E oldElement = super.getElementAt(index);
            int allItemsIndex = allItems.indexOf(oldElement);
            if (allItemsIndex >= 0) {
                allItems.set(allItemsIndex, element);
            }
        }
        applyFilter();
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean removeElement(Object obj) {
        boolean removed = allItems.remove(obj);
        if (removed) {
            applyFilter();
        }
        return removed;
    }

    @Override
    public E remove(int index) {
        if (index >= 0 && index < super.getSize()) {
            E element = super.getElementAt(index);
            allItems.remove(element);
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

    /**
     * Filter the list based on title, author, and repositories
     * Uses fuzzy matching (case-insensitive contains) for title and author
     * For repositories, matches if the item's repository is in the given list
     * All filters are applied with AND logic (all conditions must match)
     *
     * @param title Title filter (null or empty = no filter)
     * @param author Author filter (null or empty = no filter)
     * @param repositories List of repositories to match (null or empty = no filter)
     */
    public void filter(String title, String author, List<String> repositories) {
        this.titleFilter = (title != null) ? title.toLowerCase().trim() : "";
        this.authorFilter = (author != null) ? author.toLowerCase().trim() : "";
        this.repositoryFilter = (repositories != null) ? new ArrayList<>(repositories) : new ArrayList<>();
        applyFilter();
    }

    /**
     * Clear all filters and show all items
     */
    @SuppressWarnings("unused")
    public void clearFilter() {
        this.titleFilter = "";
        this.authorFilter = "";
        this.repositoryFilter.clear();
        applyFilter();
    }

    /**
     * Apply the current filters to the list
     */
    private void applyFilter() {
        super.clear();

        boolean hasRepositoryFilter = !repositoryFilter.isEmpty();

        for (E item : allItems) {
            if (filterPredicate.matches(item, titleFilter, authorFilter,
                                       hasRepositoryFilter ? repositoryFilter : null)) {
                super.addElement(item);
            }
        }
    }


    /**
     * Functional interface for filtering items
     *
     * @param <E> The type of elements to filter
     */
    @FunctionalInterface
    public interface FilterPredicate<E> {
        /**
         * Check if an item matches the given filters
         *
         * @param item The item to check
         * @param titleFilter Title filter (lowercase, empty = no filter)
         * @param authorFilter Author filter (lowercase, empty = no filter)
         * @param repositoryFilter Repository filter (null = no filter)
         * @return true if item matches all filters
         */
        boolean matches(E item, String titleFilter, String authorFilter, List<String> repositoryFilter);
    }
}

