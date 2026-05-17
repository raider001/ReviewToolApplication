package com.kalynx.serverlessreviewtool.models.review;

import com.google.gson.reflect.TypeToken;
import com.kalynx.serverlessreviewtool.utils.NdjsonReader;
import com.kalynx.serverlessreviewtool.utils.NdjsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;

public class ReviewStreamHelper {

    private static <T> void writeEntry(Path filePath, String editor, T data) throws IOException {
        StreamEntry<T> entry = StreamEntry.create(editor, data);
        NdjsonWriter.append(filePath, entry);
    }

    private static <T> List<StreamEntry<T>> readEntries(Path filePath, Class<T> dataType) throws IOException {
        return NdjsonReader.read(filePath, dataType);
    }

    public static void writeTitle(Path filePath, String editor, String title) throws IOException {
        writeEntry(filePath, editor, title);
    }

    public static List<StreamEntry<String>> readTitles(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writeDescription(Path filePath, String editor, String description) throws IOException {
        writeEntry(filePath, editor, description);
    }

    public static List<StreamEntry<String>> readDescriptions(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writeAuthor(Path filePath, String editor, String author) throws IOException {
        writeEntry(filePath, editor, author);
    }

    public static List<StreamEntry<String>> readAuthors(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writePrimaryRepository(Path filePath, String editor, String primaryRepository) throws IOException {
        writeEntry(filePath, editor, primaryRepository);
    }

    public static List<StreamEntry<String>> readPrimaryRepository(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writeBranch(Path filePath, String editor, String branch) throws IOException {
        writeEntry(filePath, editor, branch);
    }

    public static List<StreamEntry<String>> readBranch(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writeBaseBranch(Path filePath, String editor, String baseBranch) throws IOException {
        writeEntry(filePath, editor, baseBranch);
    }

    public static List<StreamEntry<String>> readBaseBranch(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writeStatus(Path filePath, String editor, String status) throws IOException {
        writeEntry(filePath, editor, status);
    }

    public static List<StreamEntry<String>> readStatuses(Path filePath) throws IOException {
        return readEntries(filePath, String.class);
    }

    public static void writeCommits(Path filePath, String editor, List<String> commits) throws IOException {
        writeEntry(filePath, editor, commits);
    }

    public static List<StreamEntry<List<String>>> readCommits(Path filePath) throws IOException {
        Type listStringType = new TypeToken<List<String>>() {}.getType();
        Type entryType = TypeToken.getParameterized(StreamEntry.class, listStringType).getType();
        return NdjsonReader.read(filePath, entryType);
    }

    public static void writeReviewer(Path filePath, String editor, ReviewerData reviewerData) throws IOException {
        writeEntry(filePath, editor, reviewerData);
    }

    public static List<StreamEntry<ReviewerData>> readReviewers(Path filePath) throws IOException {
        return readEntries(filePath, ReviewerData.class);
    }

    public static void writeCommentMetadata(Path filePath, String editor,
                                             com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentMetadata metadata) throws IOException {
        writeEntry(filePath, editor, metadata);
    }

    public static List<StreamEntry<com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentMetadata>> readCommentMetadata(Path filePath) throws IOException {
        return readEntries(filePath, com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentMetadata.class);
    }

    public static void writeCommentText(Path filePath, String editor,
                                         com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentTextData textData) throws IOException {
        writeEntry(filePath, editor, textData);
    }

    public static List<StreamEntry<com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentTextData>> readCommentText(Path filePath) throws IOException {
        return readEntries(filePath, com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentTextData.class);
    }

    public static void writeCommentStatus(Path filePath, String editor,
                                           com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentStatusData statusData) throws IOException {
        writeEntry(filePath, editor, statusData);
    }

    public static List<StreamEntry<com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentStatusData>> readCommentStatus(Path filePath) throws IOException {
        return readEntries(filePath, com.kalynx.serverlessreviewtool.git.GitReviewNotesManager.CommentStatusData.class);
    }

    public static void writeComment(Path filePath, String editor, CommentData commentData) throws IOException {
        writeEntry(filePath, editor, commentData);
    }

    public static List<StreamEntry<CommentData>> readComments(Path filePath) throws IOException {
        return readEntries(filePath, CommentData.class);
    }

    /**
     * Writes a repository active/inactive entry to the given stream file.
     * The actual editor (the person making the change) is stored in the stream entry,
     * and the repository name is embedded in the data payload.
     *
     * @param filePath the path to the stream file
     * @param editor the user performing the change
     * @param repositoryName the name of the repository
     * @param active whether the repository is active for the review
     * @throws IOException if the file cannot be written
     */
    public static void writeRepositoryActive(Path filePath, String editor, String repositoryName, boolean active) throws IOException {
        writeEntry(filePath, editor, new RepositoryActiveData(repositoryName, active));
    }

    /**
     * Reads all repository active/inactive entries from the given stream file.
     *
     * @param filePath the path to the stream file
     * @return list of entries, latest entry per repository name wins
     * @throws IOException if the file cannot be read
     */
    public static List<StreamEntry<RepositoryActiveData>> readRepositoryActive(Path filePath) throws IOException {
        return readEntries(filePath, RepositoryActiveData.class);
    }
}





