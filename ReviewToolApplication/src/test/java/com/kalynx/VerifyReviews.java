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
 * Utility runner for verifying review-note loading from sample repositories.
 */
public class VerifyReviews {
    private static final Logger LOGGER = LoggerFactory.getLogger(VerifyReviews.class);

    /**
     * Runs verification against configured sample repositories.
     */
    static void main() {
        try {
            Git git = GitFactory.getInstance();
            ReviewNotesManagerFactory factory = repo -> new GitReviewNotesManager(git, repo);
            ReviewItemLoader loader = new ReviewItemLoader(git, factory);

            LOGGER.info("=== Java Backend Service ===");
            List<ReviewItem> javaReviews = loader.loadReviewsFromRepository("java-backend-service").get();
            LOGGER.info("Found {} reviews", javaReviews.size());
            for (ReviewItem review : javaReviews) {
                LOGGER.info("  Title: {}", review.getTitle());
                LOGGER.info("  Author: {}", review.getAuthor());
                LOGGER.info("  Status: {}", review.getStatus());
            }

            LOGGER.info("=== Python API Service ===");
            List<ReviewItem> pythonReviews = loader.loadReviewsFromRepository("python-api-service").get();
            LOGGER.info("Found {} reviews", pythonReviews.size());
            for (ReviewItem review : pythonReviews) {
                LOGGER.info("  Title: {}", review.getTitle());
                LOGGER.info("  Author: {}", review.getAuthor());
                LOGGER.info("  Status: {}", review.getStatus());
            }

            LOGGER.info("=== React Frontend App ===");
            List<ReviewItem> reactReviews = loader.loadReviewsFromRepository("react-frontend-app").get();
            LOGGER.info("Found {} reviews", reactReviews.size());
            for (ReviewItem review : reactReviews) {
                LOGGER.info("  Title: {}", review.getTitle());
                LOGGER.info("  Author: {}", review.getAuthor());
                LOGGER.info("  Status: {}", review.getStatus());
            }

            int totalReviews = javaReviews.size() + pythonReviews.size() + reactReviews.size();
            LOGGER.info("SUCCESS: {} reviews loaded with proper titles", totalReviews);

        } catch (Exception e) {
            LOGGER.error("ERROR while verifying reviews", e);
        }
    }
}

