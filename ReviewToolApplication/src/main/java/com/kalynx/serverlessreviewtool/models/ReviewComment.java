package com.kalynx.serverlessreviewtool.models;

import com.kalynx.swingtheme.themedcomponents.CommentAnnotation;

/**
 * ReviewComment - An inline comment on a file with support for threading and resolution tracking
 */
public class ReviewComment implements CommentAnnotation {
    private final String id;
    private final String filePath;
    private final int lineNumber;
    private final String author;
    private final String text;
    private final String timestamp;
    private final String parentId;

    private boolean needsResolution;
    private boolean resolved;
    private String resolvedBy;
    private String resolvedAt;

    public ReviewComment(String id, String filePath, int lineNumber, String author, String text, String timestamp) {
        this(id, filePath, lineNumber, author, text, timestamp, null, false);
    }

    public ReviewComment(String id, String filePath, int lineNumber, String author, String text, String timestamp, String parentId, boolean needsResolution) {
        this.id = id;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.author = author;
        this.text = text;
        this.timestamp = timestamp;
        this.parentId = parentId;
        this.needsResolution = needsResolution;
        this.resolved = false;
        this.resolvedBy = null;
        this.resolvedAt = null;
    }

    public String getId() {
        return id;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getAuthor() {
        return author;
    }

    public String getText() {
        return text;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getParentId() {
        return parentId;
    }

    public boolean isReply() {
        return parentId != null;
    }

    public boolean needsResolution() {
        return needsResolution;
    }

    public void setNeedsResolution(boolean needsResolution) {
        this.needsResolution = needsResolution;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void markResolved(String resolvedBy) {
        this.resolved = true;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
    }

    public void markUnresolved() {
        this.resolved = false;
        this.resolvedBy = null;
        this.resolvedAt = null;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public String getResolvedAt() {
        return resolvedAt;
    }

    @Override
    public String toString() {
        return author + " (line " + lineNumber + "): " + text;
    }
}

