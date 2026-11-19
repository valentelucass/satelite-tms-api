package com.example.satelite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SeliaPlpRequestSizeFilterTest {

    @Test
    void deveRejeitarContentLengthAcimaDoLimiteSemEncaminhar() throws Exception {
        SeliaPlpRequestSizeFilter filter = new SeliaPlpRequestSizeFilter(1_024);
        String corpoExcedido = "x".repeat(1_025);
        MockHttpServletRequest request = requisicao(corpoExcedido);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("selia.plp.payload_too_large"));
        assertFalse(response.getContentAsString().contains(corpoExcedido));
        assertEquals(null, chain.getRequest());
    }

    @Test
    void deveRejeitarCorpoChunkedAcimaDoLimiteSemEncaminhar() throws Exception {
        SeliaPlpRequestSizeFilter filter = new SeliaPlpRequestSizeFilter(1_024);
        MockHttpServletRequest request = new SemContentLengthRequest("x".repeat(1_025).getBytes(StandardCharsets.UTF_8));
        request.setMethod("POST");
        request.setRequestURI(SeliaPlpRequestSizeFilter.CAMINHO_PLP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("selia.plp.payload_too_large"));
        assertEquals(null, chain.getRequest());
    }

    @Test
    void devePreservarCorpoValidoParaController() throws Exception {
        SeliaPlpRequestSizeFilter filter = new SeliaPlpRequestSizeFilter(64);
        MockHttpServletRequest request = requisicao("{\"lista\":1}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("{\"lista\":1}", new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private MockHttpServletRequest requisicao(String corpo) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SeliaPlpRequestSizeFilter.CAMINHO_PLP);
        request.setContent(corpo.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static final class SemContentLengthRequest extends MockHttpServletRequest {

        private SemContentLengthRequest(byte[] corpo) {
            setContent(corpo);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }
}
