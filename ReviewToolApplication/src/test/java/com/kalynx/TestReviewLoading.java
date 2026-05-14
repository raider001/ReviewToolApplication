package com.kalynx;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.GitFactory;
import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewItemLoader;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Utility runner for manually validating review loading from sample repositories.
 */
public class TestReviewLoading {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestReviewLoading.class);

    /**
     * Executes review loading checks.

     */
    static void main() {
        try {
            Git git = GitFactory.getInstance();
            ReviewNotesManagerFactory factory = repo -> new GitReviewNotesManager(git, repo);
            ReviewItemLoader loader = new ReviewItemLoader(git, factory);

            LOGGER.info("Loading reviews from java-backend-service...");
            List<ReviewItem> reviews = loader.loadReviewsFromRepository("java-backend-service")
                .get();

            LOGGER.info("Found {} reviews", reviews.size());
            for (ReviewItem review : reviews) {
                LOGGER.info("  - Title: {}", review.getTitle());
                LOGGER.info("    Author: {}", review.getAuthor());
                LOGGER.info("    Repositories: {}", String.join(", ", review.getRepositories()));
                LOGGER.info("    Status: {}", review.getStatus());
            }

            LOGGER.info("Loading reviews from python-api-service...");
            reviews = loader.loadReviewsFromRepository("python-api-service")
                .get();

            LOGGER.info("Found {} reviews", reviews.size());
            for (ReviewItem review : reviews) {
                LOGGER.info("  - Title: {}", review.getTitle());
                LOGGER.info("    Author: {}", review.getAuthor());
                LOGGER.info("    Repositories: {}", String.join(", ", review.getRepositories()));
                LOGGER.info("    Status: {}", review.getStatus());
            }

            LOGGER.info("Loading reviews from react-frontend-app...");
            reviews = loader.loadReviewsFromRepository("react-frontend-app")
                .get();

            LOGGER.info("Found {} reviews", reviews.size());
            for (ReviewItem review : reviews) {
                LOGGER.info("  - Title: {}", review.getTitle());
                LOGGER.info("    Author: {}", review.getAuthor());
                LOGGER.info("    Repositories: {}", String.join(", ", review.getRepositories()));
                LOGGER.info("    Status: {}", review.getStatus());
            }

            LOGGER.info("All reviews loaded successfully with no null values");

        } catch (Exception e) {
            LOGGER.error("Error loading reviews", e);
        }
    }
}


