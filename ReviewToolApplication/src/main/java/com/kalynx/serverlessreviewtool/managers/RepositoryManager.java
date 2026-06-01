package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.RepositoryDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * RepositoryManager - Manages repository data
 * Manages the list of repositories and notifies listeners of changes
 */
public class RepositoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryManager.class);
    private List<Repository> repositories = new ArrayList<>();
    private final Set<Consumer<List<Repository>>> listeners = new HashSet<>();

    public RepositoryManager() {
    }

    public List<Repository> getRepositories() {
        return List.copyOf(repositories);
    }

    public Repository getRepositoryByName(String name) {
        if (name == null) {
            return null;
        }
        return repositories.stream()
            .filter(repo -> repo.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Replaces the available repository list from notification plugin descriptors
     * without performing any git fetch/clone work.
     *
     * @param descriptors repositories reported by notification plugins
     */
    public void setRepositoriesFromNotification(List<RepositoryDescriptor> descriptors) {
        List<RepositoryDescriptor> safeDescriptors = descriptors == null ? List.of() : descriptors;
        Map<String, Repository> previousByName = repositories.stream()
            .collect(java.util.stream.Collectors.toMap(
                Repository::getName,
                repository -> repository,
                (first, ignored) -> first,
                LinkedHashMap::new));

        Map<String, RepositoryDescriptor> dedupedByName = safeDescriptors.stream()
            .filter(descriptor -> descriptor != null && descriptor.name() != null && !descriptor.name().isBlank())
            .collect(java.util.stream.Collectors.toMap(
                RepositoryDescriptor::name,
                descriptor -> descriptor,
                (_, second) -> second,
                LinkedHashMap::new));

        this.repositories = dedupedByName.values().stream()
            .map(descriptor -> {
                Repository existing = previousByName.get(descriptor.name());
                List<String> branches = existing == null ? Collections.emptyList() : new ArrayList<>(existing.getBranches());
                Repository repository = new Repository(descriptor.name(), "", descriptor.location());
                repository.setBranches(branches);
                return repository;
            })
            .sorted(Comparator.comparing(Repository::getName))
            .toList();
        notifyListeners();
    }

    /**
     * Updates branches for multiple repositories in a single operation, notifying listeners once.
     *
     * @param branchesByRepository map of repository name to branch list
     */
    public void updateBranchesForRepositories(Map<String, List<String>> branchesByRepository) {
        boolean changed = false;
        for (Repository repo : repositories) {
            List<String> branches = branchesByRepository.get(repo.getName());
            if (branches != null) {
                repo.setBranches(branches);
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void addListener(Consumer<List<Repository>> listener) {
        listeners.add(listener);
        listener.accept(List.copyOf(repositories));
    }

    public void notifyListeners() {
        LOGGER.info("Notifying listeners of repository list update: {} repositories", repositories.size());
        listeners.forEach(listener -> listener.accept(List.copyOf(repositories)));
    }
}

