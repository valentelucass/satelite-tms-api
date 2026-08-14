package com.example.satelite.config;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limita o payload da PLP antes da desserializacao JSON, inclusive em requisicoes chunked.
 */
@Component
public class SeliaPlpRequestSizeFilter extends OncePerRequestFilter {

    static final String CAMINHO_PLP = "/api/selia/intelipost/pre-shipment-list";
    private static final String ERRO_CORPO_EXCEDIDO = "{\"status\":\"ERROR\",\"messages\":[{\"type\":\"ERROR\",\"text\":\"Corpo da requisicao excede o limite permitido.\",\"code\":\"selia.plp.payload_too_large\"}]}";

    private final int maximoBytes;

    public SeliaPlpRequestSizeFilter(
            @Value("${SELIA_INTELIPOST_PLP_MAX_BODY_BYTES:5242880}") int maximoBytes
    ) {
        this.maximoBytes = Math.max(1_024, maximoBytes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !caminhoDaAplicacao(request).equals(CAMINHO_PLP);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maximoBytes) {
            responderCorpoExcedido(response);
            return;
        }

        byte[] corpo = lerCorpoLimitado(request.getInputStream());
        if (corpo == null) {
            responderCorpoExcedido(response);
            return;
        }

        filterChain.doFilter(new RequisicaoComCorpoEmMemoria(request, corpo), response);
    }

    private String caminhoDaAplicacao(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private byte[] lerCorpoLimitado(ServletInputStream input) throws IOException {
        try (input) {
            byte[] buffer = new byte[8_192];
            byte[] corpo = new byte[Math.min(8_192, maximoBytes)];
            int tamanho = 0;
            int lidos;
            while ((lidos = input.read(buffer)) != -1) {
                if (lidos > maximoBytes - tamanho) {
                    return null;
                }
                if (tamanho + lidos > corpo.length) {
                    int novoTamanho = Math.min(maximoBytes, Math.max(corpo.length * 2, tamanho + lidos));
                    byte[] expandido = new byte[novoTamanho];
                    System.arraycopy(corpo, 0, expandido, 0, tamanho);
                    corpo = expandido;
                }
                System.arraycopy(buffer, 0, corpo, tamanho, lidos);
                tamanho += lidos;
            }

            byte[] resultado = new byte[tamanho];
            System.arraycopy(corpo, 0, resultado, 0, tamanho);
            return resultado;
        }
    }

    private void responderCorpoExcedido(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ERRO_CORPO_EXCEDIDO);
    }

    private static final class RequisicaoComCorpoEmMemoria extends HttpServletRequestWrapper {

        private final byte[] corpo;

        private RequisicaoComCorpoEmMemoria(HttpServletRequest request, byte[] corpo) {
            super(request);
            this.corpo = corpo;
        }

        @Override
        public int getContentLength() {
            return corpo.length;
        }

        @Override
        public long getContentLengthLong() {
            return corpo.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(corpo);

                @Override
                public int read() throws IOException {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    throw new UnsupportedOperationException("Leitura assincrona nao e usada na PLP");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? java.nio.charset.StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
