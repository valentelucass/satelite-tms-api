package com.example.satelite.services.selia;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Protege o recebimento de PLP sem reter a chave de integracao ou o IP em texto puro.
 */
@Service
public class SeliaPlpRateLimitService {

    private static final String IDENTIDADE_AUSENTE = "ausente";

    private final boolean habilitado;
    private final long janelaMs;
    private final int maximoPorChave;
    private final int maximoPorIp;
    private final int maximoBuckets;
    private final Clock clock;
    private final Map<String, Janela> buckets = new HashMap<>();
    private long ultimaLimpezaEmMs;

    public SeliaPlpRateLimitService(
            @Value("${SELIA_INTELIPOST_PLP_RATE_LIMIT_ENABLED:true}") boolean habilitado,
            @Value("${SELIA_INTELIPOST_PLP_RATE_LIMIT_WINDOW_MS:60000}") long janelaMs,
            @Value("${SELIA_INTELIPOST_PLP_RATE_LIMIT_MAX_REQUESTS_PER_KEY:60}") int maximoPorChave,
            @Value("${SELIA_INTELIPOST_PLP_RATE_LIMIT_MAX_REQUESTS_PER_IP:120}") int maximoPorIp,
            @Value("${SELIA_INTELIPOST_PLP_RATE_LIMIT_MAX_BUCKETS:10000}") int maximoBuckets
    ) {
        this(habilitado, janelaMs, maximoPorChave, maximoPorIp, maximoBuckets, Clock.systemUTC());
    }

    public SeliaPlpRateLimitService(
            boolean habilitado,
            long janelaMs,
            int maximoPorChave,
            int maximoPorIp,
            int maximoBuckets,
            Clock clock
    ) {
        this.habilitado = habilitado;
        this.janelaMs = Math.max(1_000L, janelaMs);
        this.maximoPorChave = Math.max(1, maximoPorChave);
        this.maximoPorIp = Math.max(1, maximoPorIp);
        this.maximoBuckets = Math.max(100, maximoBuckets);
        this.clock = clock;
    }

    public boolean permitirChaveAutenticada(String chaveApi, String enderecoIp) {
        if (!habilitado) {
            return true;
        }

        synchronized (buckets) {
            long agora = clock.millis();
            limparExpirados(agora);
            String bucketChave = criarBucket("chave", chaveApi);
            String bucketIp = criarBucket("ip", enderecoIp);
            if (!haEspacoParaNovosBuckets(bucketChave, bucketIp)
                    || !podeConsumir(bucketChave, maximoPorChave, agora)
                    || !podeConsumir(bucketIp, maximoPorIp, agora)) {
                return false;
            }

            consumir(bucketChave, agora);
            consumir(bucketIp, agora);
            return true;
        }
    }

    public boolean permitirIp(String enderecoIp) {
        if (!habilitado) {
            return true;
        }

        synchronized (buckets) {
            long agora = clock.millis();
            limparExpirados(agora);
            String bucketIp = criarBucket("ip", enderecoIp);
            if (!podeConsumir(bucketIp, maximoPorIp, agora)) {
                return false;
            }

            consumir(bucketIp, agora);
            return true;
        }
    }

    private boolean podeConsumir(String bucket, int limite, long agora) {
        Janela janela = buckets.get(bucket);
        if (janela == null) {
            return true;
        }

        return janela.expirada(agora, janelaMs) || janela.quantidade() < limite;
    }

    private boolean haEspacoParaNovosBuckets(String primeiroBucket, String segundoBucket) {
        int novosBuckets = buckets.containsKey(primeiroBucket) ? 0 : 1;
        if (!primeiroBucket.equals(segundoBucket) && !buckets.containsKey(segundoBucket)) {
            novosBuckets++;
        }
        return buckets.size() + novosBuckets <= maximoBuckets;
    }

    private void consumir(String bucket, long agora) {
        Janela janela = buckets.get(bucket);
        if (janela == null || janela.expirada(agora, janelaMs)) {
            buckets.put(bucket, new Janela(agora, 1));
            return;
        }

        buckets.put(bucket, new Janela(janela.inicioEmMs(), janela.quantidade() + 1));
    }

    private void limparExpirados(long agora) {
        if (agora - ultimaLimpezaEmMs < janelaMs) {
            return;
        }

        Iterator<Janela> iterator = buckets.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expirada(agora, janelaMs)) {
                iterator.remove();
            }
        }
        ultimaLimpezaEmMs = agora;
    }

    private String criarBucket(String categoria, String identificador) {
        String normalizado = identificador == null || identificador.isBlank()
                ? IDENTIDADE_AUSENTE
                : identificador.trim();
        return categoria + ':' + hash(normalizado);
    }

    private String hash(String valor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hexadecimal.append(String.format("%02x", item));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel para rate limit da PLP", e);
        }
    }

    private record Janela(long inicioEmMs, int quantidade) {
        private boolean expirada(long agora, long duracaoMs) {
            return agora - inicioEmMs >= duracaoMs;
        }
    }
}
