package com.example.satelite.services.ppg;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.clients.PpgClient;
import com.example.satelite.dto.ppg.PpgLoginRequestDTO;
import com.example.satelite.dto.ppg.PpgLoginResponseDTO;

class PpgAuthServiceTest {
    private final PpgClient client = mock(PpgClient.class);
    private final PpgAuthService auth = new PpgAuthService(client);

    @Test
    void reusesTokenWithoutExtraLogin() {
        ReflectionTestUtils.setField(auth, "email", "unit@example.invalid");
        ReflectionTestUtils.setField(auth, "password", "unit-password");
        when(client.login(any())).thenReturn(token("first"));
        assertEquals("first", auth.obterTokenValido());
        assertEquals("first", auth.obterTokenValido());
        verify(client).login(new PpgLoginRequestDTO("unit@example.invalid", "unit-password"));
    }

    @Test
    void expiredCachedTokenIsReplaced() {
        when(client.login(any())).thenReturn(token("first"), token("second"));
        auth.obterTokenValido();
        ReflectionTestUtils.setField(auth, "dataExpiracao", LocalDateTime.now().minusSeconds(1));
        assertEquals("second", auth.obterTokenValido());
        assertEquals("second", auth.obterTokenValido());
        verify(client, times(2)).login(any());
    }

    @Test
    void failedLoginIsNotCached() {
        when(client.login(any())).thenThrow(new IllegalStateException("unit unavailable")).thenReturn(token("recovered"));
        assertThrows(IllegalStateException.class, auth::obterTokenValido);
        assertEquals("recovered", auth.obterTokenValido());
    }

    @Test
    void concurrentRequestsShareSingleLogin() throws Exception {
        when(client.login(any())).thenReturn(token("shared"));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var requests = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) requests.add(executor.submit(auth::obterTokenValido));
            for (Future<String> request : requests) assertEquals("shared", request.get(3, TimeUnit.SECONDS));
            verify(client).login(any());
        } finally { executor.shutdownNow(); }
    }

    private PpgLoginResponseDTO token(String id) {
        return new PpgLoginResponseDTO(id, 1209600, "2026-09-09T00:00:00Z", 1);
    }
}
