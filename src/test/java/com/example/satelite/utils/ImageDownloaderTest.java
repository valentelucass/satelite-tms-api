package com.example.satelite.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ImageDownloaderTest {
    private final HttpClient http = mock(HttpClient.class);
    private final ImageDownloader downloader = new ImageDownloader(1, 2, 3, 0);

    @BeforeEach
    void isolateHttp() { ReflectionTestUtils.setField(downloader, "httpClient", http); }

    @Test
    void downloadsBytesUsingGetAndConfiguredTimeout() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response(200, new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{1, 2, 3}, downloader.baixarImagemDaUrl("https://example.invalid/pod"));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertEquals("GET", request.getValue().method());
        assertEquals(Duration.ofSeconds(2), request.getValue().timeout().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503, 520})
    void retriesTransientHttpAndStopsOnSuccess(int status) throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(response(status, new byte[0])).thenReturn(response(200, new byte[]{3}));
        assertArrayEquals(new byte[]{3}, downloader.baixarImagemDaUrl("https://example.invalid/pod", "unit-document"));
        verify(http, times(2)).send(any(HttpRequest.class), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void neverRetriesPermanentHttpFailure(int status) throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response(status, new byte[0]));
        assertThrows(IOException.class, () -> downloader.baixarImagemDaUrl("https://example.invalid/pod"));
        verify(http, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void limitsIoRetriesAndPreservesFailure() throws Exception {
        var failure = new IOException("unit timeout");
        when(http.send(any(HttpRequest.class), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class, () -> downloader.baixarImagemDaUrl("https://example.invalid/pod")));
        verify(http, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    void rejectsSuccessfulHttpWithEmptyImage() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response(200, new byte[0]));
        assertThrows(IOException.class, () -> downloader.baixarImagemDaUrl("https://example.invalid/pod"));
        verify(http, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    void cancellationDoesNotStartAnotherAttempt() throws Exception {
        when(http.send(any(HttpRequest.class), any())).thenThrow(new InterruptedException("unit cancellation"));
        assertThrows(InterruptedException.class, () -> downloader.baixarImagemDaUrl("https://example.invalid/pod"));
        verify(http, times(1)).send(any(HttpRequest.class), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid url"})
    void invalidUrlNeverReachesHttpClient(String url) {
        assertThrows(IllegalArgumentException.class, () -> downloader.baixarImagemDaUrl(url));
        verifyNoInteractions(http);
    }

    private HttpResponse<byte[]> response(int status, byte[] body) {
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "statusCode" -> status;
            case "body" -> body;
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        return response;
    }
}
