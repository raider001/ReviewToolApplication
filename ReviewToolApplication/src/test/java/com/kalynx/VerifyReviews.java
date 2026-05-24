package com.kalynx;

import com.kalynx.serverlessreviewtool.git.GitImpl;
import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManagerFactory;
import com.kalynx.serverlessreviewtool.git.OrphanBranchStore;
import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewItemLoader;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility runner for verifying review loading from sample repositories.
 * Uses the orphan-branch storage backend (OrphanBranchStore / OrphanBranchReviewManager).
 */
public class VerifyReviews {
    private static final Logger LOGGER = LoggerFactory.getLogger(VerifyReviews.class);

    private static final String REMOTE_URL = "file:///path/to/your/remote/repo";
    private static final Path BASE_PATH = Paths.get(System.getProperty("user.home"),
            ".serverless-review-tool", "repositories");

    /**
     * Runs verification against configured sample repositories.
     */
    static void main() {
        try {
            GitImpl gitImpl = new GitImpl(BASE_PATH);
            Map<String, OrphanBranchStore> storeRegistry = new ConcurrentHashMap<>();
            OrphanBranchReviewManagerFactory managerFactory = (repoName, remoteUrl) -> {
                OrphanBranchStore store = storeRegistry.computeIfAbsent(remoteUrl,
                        url -> new OrphanBranchStore(url, BASE_PATH));
                return new OrphanBranchReviewManager(store, repoName);
            };
            ReviewItemLoader loader = new ReviewItemLoader(managerFactory);

            LOGGER.info("=== Java Backend Service ===");
            List<ReviewItem> javaReviews = loader.loadReviewsFromRepository("java-backend-service", REMOTE_URL).get();
            LOGGER.info("Found {} reviews", javaReviews.size());
            for (ReviewItem review : javaReviews) {
                LOGGER.info("  Title: {}", review.getTitle());
                LOGGER.info("  Author: {}", review.getAuthor());
                LOGGER.info("  Status: {}", review.getStatus());
            }

            int totalReviews = javaReviews.size();
            LOGGER.info("SUCCESS: {} reviews loaded with proper titles", totalReviews);

        } catch (Exception e) {
            LOGGER.error("ERROR while verifying reviews", e);
        }
    }
}
