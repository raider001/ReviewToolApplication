package com.kalynx.serverlessreviewtool.indexer;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class CommentIndexerClientTest {

    private HttpClient http;
    private SettingsManager settingsManager;
    private CommentIndexerClient client;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        settingsManager = mock(SettingsManager.class);
        when(settingsManager.getIndexerUrl()).thenReturn("http://indexer");
        when(settingsManager.getIndexerBearerToken()).thenReturn("");
        client = new CommentIndexerClient(settingsManager, http);
    }

    // ── getCommentRouting — 200 ───────────────────────────────────────────────

    @Test
    void getCommentRouting_200WithEntries_returnsDeserializedList() throws Exception {
        String body = """
            [
              {"repository_url":"https://git/repo-a.git","comment_id":"c1","last_updated":"2024-01-01T00:00:00Z"},
              {"repository_url":"https://git/repo-b.git","comment_id":"c2","last_updated":"2024-01-02T00:00:00Z"}
            ]
            """;
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(body);
        doReturn(resp).when(http).send(any(), any());

        List<CommentRoutingEntry> result = client.getCommentRouting("rv-1");

        assertEquals(2, result.size());
        assertEquals("https://git/repo-a.git", result.get(0).repositoryUrl());
        assertEquals("c1", result.get(0).commentId());
        assertEquals("2024-01-01T00:00:00Z", result.get(0).lastUpdated());
        assertEquals("https://git/repo-b.git", result.get(1).repositoryUrl());
        assertEquals("c2", result.get(1).commentId());
    }

    @Test
    void getCommentRouting_200EmptyArray_returnsEmptyList() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("[]");
        doReturn(resp).when(http).send(any(), any());

        List<CommentRoutingEntry> result = client.getCommentRouting("rv-1");

        assertTrue(result.isEmpty());
    }

    // ── getCommentRouting — 404 ───────────────────────────────────────────────

    @Test
    void getCommentRouting_404_returnsEmptyList() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(404);
        doReturn(resp).when(http).send(any(), any());

        List<CommentRoutingEntry> result = client.getCommentRouting("rv-missing");

        assertTrue(result.isEmpty());
    }

    // ── getCommentRouting — 401 ───────────────────────────────────────────────

    @Test
    void getCommentRouting_401_throwsIndexerAuthException() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(401);
        doReturn(resp).when(http).send(any(), any());

        assertThrows(IndexerAuthException.class, () -> client.getCommentRouting("rv-1"));
    }

    // ── getCommentRouting — other errors ─────────────────────────────────────

    @Test
    void getCommentRouting_503_returnsEmptyList() throws Exception {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(503);
        doReturn(resp).when(http).send(any(), any());

        List<CommentRoutingEntry> result = client.getCommentRouting("rv-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void getCommentRouting_httpException_returnsEmptyList() throws Exception {
        when(http.send(any(), any())).thenThrow(new IOException("connection refused"));

        List<CommentRoutingEntry> result = client.getCommentRouting("rv-1");

        assertTrue(result.isEmpty());
    }

    // ── auth token forwarding ─────────────────────────────────────────────────

    @Test
    void getCommentRouting_withBearerToken_forwardsAuthHeader() throws Exception {
        when(settingsManager.getIndexerBearerToken()).thenReturn("my-secret-token");
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("[]");
        doReturn(resp).when(http).send(any(), any());

        client.getCommentRouting("rv-1");

        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        verify(http).send(argThat(req -> {
            captured.set(req);
            return true;
        }), any());
        assertTrue(captured.get().headers().firstValue("Authorization")
            .map(v -> v.equals("Bearer my-secret-token")).orElse(false),
            "Authorization header must contain the bearer token");
    }

    @Test
    void getCommentRouting_noToken_doesNotSendAuthHeader() throws Exception {
        when(settingsManager.getIndexerBearerToken()).thenReturn("");
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("[]");
        doReturn(resp).when(http).send(any(), any());

        client.getCommentRouting("rv-1");

        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        verify(http).send(argThat(req -> {
            captured.set(req);
            return true;
        }), any());
        assertFalse(captured.get().headers().firstValue("Authorization").isPresent(),
            "Authorization header must not be sent when token is blank");
    }

    // ── no indexer URL configured ─────────────────────────────────────────────

    @Test
    void getCommentRouting_blankIndexerUrl_returnsEmptyWithoutHttp() throws Exception {
        when(settingsManager.getIndexerUrl()).thenReturn("");

        List<CommentRoutingEntry> result = client.getCommentRouting("rv-1");

        assertTrue(result.isEmpty());
        verify(http, never()).send(any(), any());
    }
}
