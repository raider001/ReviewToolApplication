package com.kalynx.serverlessreviewtool.plugin.requests;

import com.kalynx.serverlessreviewtool.plugin.dataobjects.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewSummary;

import java.util.List;

public interface FetchRequests {
    List<ReviewSummary> getAllReviews();
    List<String> getAllBranches();
    List<RepositoryDescriptor> getAllRepositories();
    List<String> getBranchesFromRepository(String repository);
}
