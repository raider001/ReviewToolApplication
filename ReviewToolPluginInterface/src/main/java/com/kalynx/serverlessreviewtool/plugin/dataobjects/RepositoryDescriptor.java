package com.kalynx.serverlessreviewtool.plugin.dataobjects;

/**
 * Describes a repository tracked by a notification plugin.
 *
 * @param name repository identifier
 * @param location repository URL or filesystem location
 */
public record RepositoryDescriptor(
    String name,
    String location) {
}


