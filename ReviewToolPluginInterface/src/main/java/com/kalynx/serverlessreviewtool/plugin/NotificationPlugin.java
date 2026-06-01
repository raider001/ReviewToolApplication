package com.kalynx.serverlessreviewtool.plugin;

import com.kalynx.serverlessreviewtool.plugin.dataobjects.BranchIndex;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.CommentIndex;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.NotificationPayload;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.requests.FetchRequests;

public abstract class NotificationPlugin
    extends Notifier<NotificationPayload, NotificationPlugin.NotificationType>
    implements Plugin, FetchRequests {

    public enum NotificationType {
        REVIEW_CREATED,
        REVIEW_UPDATED,
        BRANCH_UPDATED,
        BRANCH_DELETED,
        COMMENT_ADDED,
        COMMENT_UPDATED
    }

    @Override
    public void initialize() {}

    public final void onReviewCreated(ReviewListUpdate update) {
        if (update == null) {
            return;
        }
        notifyListeners(NotificationType.REVIEW_CREATED, update);
    }

    public final void onReviewUpdated(ReviewListUpdate update) {
        if (update == null) {
            return;
        }
        notifyListeners(NotificationType.REVIEW_UPDATED, update);
    }

    public void onBranchUpdated(BranchIndex update) {
        if (update == null) {
            return;
        }
        notifyListeners(NotificationType.BRANCH_UPDATED, update);
    }

    public void onBranchDeleted(BranchIndex update) {
        if (update == null) {
            return;
        }
        notifyListeners(NotificationType.BRANCH_DELETED, update);
    }

    public void onCommentAdded(CommentIndex update) {
        if (update == null) {
            return;
        }
        notifyListeners(NotificationType.COMMENT_ADDED, update);
    }

    public void onCommentUpdated(CommentIndex update) {
        if (update == null) {
            return;
        }
        notifyListeners(NotificationType.COMMENT_UPDATED, update);
    }
}
