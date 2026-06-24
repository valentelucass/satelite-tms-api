package com.example.satelite.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ImageDownloader {

    private static final Logger log = LoggerFactory.getLogger(ImageDownloader.class);

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxTentativas;
    private final long intervaloRetryMs;

    public ImageDownloader(
            @Value("${IMAGE_DOWNLOAD_CONNECT_TIMEOUT_SECONDS:10}") long connectTimeoutSeconds,
            @Value("${IMAGE_DOWNLOAD_REQUEST_TIMEOUT_SECONDS:30}") long requestTimeoutSeconds,
            @Value("${IMAGE_DOWNLOAD_MAX_ATTEMPTS:3}") int maxTentativas,
            @Value("${IMAGE_DOWNLOAD_RETRY_DELAY_MS:1000}") long intervaloRetryMs
    ) {
        Duration connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxTentativas = Math.max(1, maxTentativas);
        this.intervaloRetryMs = Math.max(0, intervaloRetryMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public byte[] baixarImagemDaUrl(String urlAssinada) throws IOException, InterruptedException {
        return baixarImagemDaUrl(urlAssinada, null);
    }

    public byte[] baixarImagemDaUrl(String urlAssinada, String cteKey) throws IOException, InterruptedException {
        if (urlAssinada == null || urlAssinada.isBlank()) {
            throw new IllegalArgumentException("URL do comprovante ausente");
        }

        URI uri = URI.create(urlAssinada);
        String cteLog = normalizarCteLog(cteKey);
        IOException ultimaFalha = null;

        log.info("Iniciando download da imagem para CTe {}. host={}", cteLog, obterHost(uri));

        for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
            try {
                byte[] imagemBytes = baixarImagem(uri);
                log.info("Download concluido, tamanho: {} bytes. cte={}", imagemBytes.length, cteLog);
                return imagemBytes;
            } catch (IOException e) {
                ultimaFalha = e;
                if (tentativa == maxTentativas || !deveTentarNovamente(e)) {
                    throw e;
                }

                log.warn(
                        "Falha transitoria ao baixar imagem do comprovante. cte={} tentativa={}/{} mensagem={}",
                        cteLog,
                        tentativa,
                        maxTentativas,
                        e.getMessage()
                );
                aguardarRetry();
            } catch (InterruptedException e) {
                log.warn("Download da imagem interrompido. cte={} mensagem={}", cteLog, e.getMessage());
                throw e;
            }
        }

        throw ultimaFalha != null ? ultimaFalha : new IOException("Falha desconhecida ao baixar imagem do comprovante");
    }

    private byte[] baixarImagem(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ImageDownloadHttpException(response.statusCode());
        }

        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new IOException("Imagem do comprovante retornou sem conteúdo");
        }

        return body;
    }

    private boolean deveTentarNovamente(IOException e) {
        if (e instanceof ImageDownloadHttpException httpException) {
            int statusCode = httpException.statusCode();
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }

        return true;
    }

    private void aguardarRetry() throws InterruptedException {
        if (intervaloRetryMs > 0) {
            Thread.sleep(intervaloRetryMs);
        }
    }

    private String normalizarCteLog(String cteKey) {
        return cteKey == null || cteKey.isBlank() ? "NAO_INFORMADO" : cteKey;
    }

    private String obterHost(URI uri) {
        return uri.getHost() != null ? uri.getHost() : "host_desconhecido";
    }

    private static class ImageDownloadHttpException extends IOException {
        private final int statusCode;

        private ImageDownloadHttpException(int statusCode) {
            super("Falha ao baixar imagem do comprovante. HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
