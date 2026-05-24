package com.kalynx.serverlessreviewtool.indexer;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class IndexerEventSourceTests {

    private HttpClient http;
    private IndexerEventSource source;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        source = new IndexerEventSource(mock(SettingsManager.class), http);
    }

    // ── parseReviews ──────────────────────────────────────────────────────────

    @Test
    void parseReviews_emptyItemsArray_returnsEmptyList() {
        List<ReviewListUpdate> result = source.parseReviews("{\"items\":[]}");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseReviews_openStatus_producesCreatedType() {
        String body = """
            {"items":[{"review_id":"r1","status":"OPEN",
              "repositories":[{"repository":"repo-a","repository_url":"http://git/repo-a.git"}]}]}
            """;

        List<ReviewListUpdate> result = source.parseReviews(body);

        assertEquals(1, result.size());
        ReviewListUpdate u = result.getFirst();
        assertEquals("r1", u.reviewId());
        assertEquals(ReviewUpdateType.CREATED, u.updateType());
        assertEquals("repo-a", u.primaryRepository());
        assertEquals(List.of("repo-a"), u.repositories());
        assertEquals("http://git/repo-a.git", u.repositoryUrl());
        assertNull(u.branchName());
    }

    @Test
    void parseReviews_completedStatus_producesUpdatedType() {
        String body = """
            {"items":[{"review_id":"r2","status":"COMPLETED",
              "repositories":[{"repository":"repo-b","repository_url":"http://git/repo-b.git"}]}]}
            """;

        List<ReviewListUpdate> result = source.parseReviews(body);

        assertEquals(1, result.size());
        assertEquals(ReviewUpdateType.UPDATED, result.getFirst().updateType());
    }

    @Test
    void parseReviews_cancelledStatus_producesUpdatedType() {
        String body = """
            {"items":[{"review_id":"r3","status":"CANCELLED",
              "repositories":[{"repository":"repo-c","repository_url":"http://git/repo-c.git"}]}]}
            """;

        List<ReviewListUpdate> result = source.parseReviews(body);

        assertEquals(1, result.size());
        assertEquals(ReviewUpdateType.UPDATED, result.getFirst().updateType());
    }

    @Test
    void parseReviews_multipleRepositories_allCaptured() {
        String body = """
            {"items":[{"review_id":"r4","status":"OPEN","repositories":[
              {"repository":"repo-x","repository_url":"http://git/x.git"},
              {"repository":"repo-y","repository_url":"http://git/y.git"}
            ]}]}
            """;

        List<ReviewListUpdate> result = source.parseReviews(body);

        assertEquals(1, result.size());
        ReviewListUpdate u = result.getFirst();
        assertEquals("repo-x", u.primaryRepository());
        assertEquals(List.of("repo-x", "repo-y"), u.repositories());
        assertEquals("http://git/x.git", u.repositoryUrl());
    }

    @Test
    void parseReviews_missingReviewId_entrySkipped() {
        List<ReviewListUpdate> result = source.parseReviews(
                "{\"items\":[{\"status\":\"OPEN\",\"repositories\":[]}]}");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseReviews_malformedJson_returnsEmpty() {
        List<ReviewListUpdate> result = source.parseReviews("not json at all");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseReviews_multipleItems_allParsed() {
        String body = """
            {"items":[
              {"review_id":"ra","status":"OPEN","repositories":[{"repository":"r","repository_url":"u"}]},
              {"review_id":"rb","status":"COMPLETED","repositories":[{"repository":"r","repository_url":"u"}]}
            ]}
            """;

        List<ReviewListUpdate> result = source.parseReviews(body);

        assertEquals(2, result.size());
        assertEquals(ReviewUpdateType.CREATED, result.get(0).updateType());
        assertEquals(ReviewUpdateType.UPDATED, result.get(1).updateType());
    }

    // ── fetchAndFireInitialReviews ────────────────────────────────────────────

    @Test
    void fetchAndFireInitialReviews_non200_doesNotFireListener() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(503);
        doReturn(resp).when(http).send(any(), any());

        AtomicReference<ReviewListUpdate[]> captured = new AtomicReference<>();
        source.fetchAndFireInitialReviews("http://indexer", captured::set);

        assertNull(captured.get());
    }

    @Test
    void fetchAndFireInitialReviews_emptyItems_doesNotFireListener() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"items\":[]}");
        doReturn(resp).when(http).send(any(), any());

        AtomicReference<ReviewListUpdate[]> captured = new AtomicReference<>();
        source.fetchAndFireInitialReviews("http://indexer", captured::set);

        assertNull(captured.get());
    }

    @Test
    void fetchAndFireInitialReviews_withItems_firesListenerWithParsedData() throws Exception {
        String body = """
            {"items":[{"review_id":"rv-1","status":"OPEN",
              "repositories":[{"repository":"some-repo","repository_url":"http://git/some.git"}]}]}
            """;
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(body);
        doReturn(resp).when(http).send(any(), any());

        AtomicReference<ReviewListUpdate[]> captured = new AtomicReference<>();
        source.fetchAndFireInitialReviews("http://indexer", captured::set);

        assertNotNull(captured.get());
        assertEquals(1, captured.get().length);
        ReviewListUpdate u = captured.get()[0];
        assertEquals("rv-1", u.reviewId());
        assertEquals(ReviewUpdateType.CREATED, u.updateType());
        assertEquals("some-repo", u.primaryRepository());
        assertEquals("http://git/some.git", u.repositoryUrl());
    }

    @Test
    void fetchAndFireInitialReviews_httpException_doesNotFireListener() throws Exception {
        when(http.send(any(), any())).thenThrow(new IOException("connection refused"));

        AtomicReference<ReviewListUpdate[]> captured = new AtomicReference<>();
        source.fetchAndFireInitialReviews("http://indexer", captured::set);

        assertNull(captured.get());
    }

    // ── fetchBranches ─────────────────────────────────────────────────────────

    @Test
    void fetchBranches_200_returnsParsedNames() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"branches\":[\"main\",\"feature/foo\",\"release/1.0\"],\"next_cursor\":null}");
        doReturn(resp).when(http).send(any(), any());

        List<String> branches = source.fetchBranches("http://indexer", 50);

        assertEquals(List.of("main", "feature/foo", "release/1.0"), branches);
    }

    @Test
    void fetchBranches_non200_returnsEmpty() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(503);
        doReturn(resp).when(http).send(any(), any());

        List<String> branches = source.fetchBranches("http://indexer", 50);

        assertTrue(branches.isEmpty());
    }

    @Test
    void fetchBranches_emptyArray_returnsEmpty() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"branches\":[]}");
        doReturn(resp).when(http).send(any(), any());

        List<String> branches = source.fetchBranches("http://indexer", 50);

        assertTrue(branches.isEmpty());
    }

    @Test
    void fetchBranches_httpException_returnsEmpty() throws Exception {
        when(http.send(any(), any())).thenThrow(new IOException("timeout"));

        List<String> branches = source.fetchBranches("http://indexer", 50);

        assertTrue(branches.isEmpty());
    }

    @Test
    void addBranchListener_firesImmediatelyWithEmpty() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        source.addBranchListener(captured::set);

        assertNotNull(captured.get());
        assertTrue(captured.get().isEmpty());
    }
}
