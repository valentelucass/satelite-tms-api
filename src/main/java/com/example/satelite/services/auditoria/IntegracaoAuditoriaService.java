package com.example.satelite.services.auditoria;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import com.example.satelite.dto.auditoria.AuditoriaIntegracoesClientesResponseDTO;
import com.example.satelite.dto.auditoria.MetricaConsolidadaDTO;
import com.example.satelite.dto.auditoria.PaginacaoDTO;
import com.example.satelite.dto.auditoria.PendenciasPaginadasDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository.Filtros;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository.PendenciasResultado;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.repositories.LogIntegracaoRepository.MetricaIntegracaoClienteProjection;

@Service
public class IntegracaoAuditoriaService {

    private static final int TAMANHO_PADRAO = 100;
    private static final int TAMANHO_MAXIMO = 500;
    private static final String DESTINO_PPG = "PPG";
    private static final String PARAM_TABELA_BUSCA = "f.tabelaBusca";
    private static final String PARAM_TABELA_CODIGO = "f.tabelaCodigo";
    private static final String PARAM_TABELA_STATUS = "f.tabelaStatus";
    private static final String PARAM_SORT_FIELD = "sortField";
    private static final String PARAM_SORT_DIRECTION = "sortDirection";
    private static final String PREFIXO_FILTRO_COLUNA = "f.tabelaColuna.";
    private static final List<String> CAMPOS_IMAGEM_CANHOTO = List.of(
            "foto",
            "imagemBase64",
            "imagem",
            "imageBase64",
            "canhotoBase64",
            "conteudoBase64"
    );

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final IntegracaoAuditoriaQueryRepository integracaoAuditoriaQueryRepository;

    public IntegracaoAuditoriaService(
            LogIntegracaoRepository logIntegracaoRepository,
            IntegracaoAuditoriaQueryRepository integracaoAuditoriaQueryRepository
    ) {
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.integracaoAuditoriaQueryRepository = integracaoAuditoriaQueryRepository;
    }

    public AuditoriaIntegracoesClientesResponseDTO consultarIntegracoesClientes(
            int pagina,
            int tamanho,
            MultiValueMap<String, String> params
    ) {
        int paginaNormalizada = normalizarPagina(pagina);
        int tamanhoNormalizado = normalizarTamanho(tamanho);

        List<MetricaConsolidadaDTO> metricas = logIntegracaoRepository.buscarMetricasIntegracoesClientes()
                .stream()
                .map(this::mapearMetrica)
                .toList();

        PendenciasResultado pendencias = integracaoAuditoriaQueryRepository.buscarPendencias(
                lerFiltros(params),
                paginaNormalizada,
                tamanhoNormalizado
        );
        int totalPaginas = calcularTotalPaginas(pendencias.totalElementos(), tamanhoNormalizado);

        PaginacaoDTO paginacao = new PaginacaoDTO(
                paginaNormalizada,
                tamanhoNormalizado,
                pendencias.totalElementos(),
                totalPaginas,
                paginaNormalizada == 0,
                totalPaginas == 0 || paginaNormalizada >= totalPaginas - 1
        );

        return new AuditoriaIntegracoesClientesResponseDTO(
                LocalDateTime.now(),
                metricas,
                new PendenciasPaginadasDTO(pendencias.itens(), paginacao)
        );
    }

    private MetricaConsolidadaDTO mapearMetrica(MetricaIntegracaoClienteProjection metrica) {
        return new MetricaConsolidadaDTO(
                metrica.getSistemaDestino(),
                valorOuZero(metrica.getTotalRegistros()),
                percentualOuZero(metrica.getPercentualXmlSucesso()),
                percentualOuZero(metrica.getPercentualCanhotoSucesso())
        );
    }

    public Optional<String> buscarImagemCanhoto(Long id) {
        if (id == null) {
            return Optional.empty();
        }

        return logIntegracaoRepository.findById(id)
                .filter(this::ehLogPpg)
                .map(LogIntegracaoModel::getRequestPayload)
                .flatMap(this::extrairImagemCanhoto);
    }

    private boolean ehLogPpg(LogIntegracaoModel log) {
        return log != null
                && log.getSistemaDestino() != null
                && DESTINO_PPG.equalsIgnoreCase(log.getSistemaDestino().trim());
    }

    private Optional<String> extrairImagemCanhoto(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return Optional.empty();
        }

        String payload = requestPayload.trim();
        if (payload.startsWith("data:image/")) {
            return Optional.of(payload);
        }

        for (String campo : CAMPOS_IMAGEM_CANHOTO) {
            Optional<String> imagem = extrairCampoTexto(payload, campo);
            if (imagem.isPresent()) {
                return imagem;
            }
        }

        return Optional.empty();
    }

    private Optional<String> extrairCampoTexto(String payload, String campo) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(campo) + "\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(payload);

        while (matcher.find()) {
            String valor = normalizarValorJson(matcher.group(1));
            if (valor != null) {
                return Optional.of(valor);
            }
        }

        return Optional.empty();
    }

    private String normalizarValorJson(String valorJson) {
        if (valorJson == null) {
            return null;
        }

        String valor = valorJson
                .replace("\\/", "/")
                .trim();
        return valor.isEmpty() || "null".equalsIgnoreCase(valor) ? null : valor;
    }

    private int normalizarPagina(int pagina) {
        return Math.max(pagina, 0);
    }

    private int normalizarTamanho(int tamanho) {
        if (tamanho <= 0) {
            return TAMANHO_PADRAO;
        }

        return Math.min(tamanho, TAMANHO_MAXIMO);
    }

    private Filtros lerFiltros(MultiValueMap<String, String> params) {
        return new Filtros(
                primeiroValor(params, PARAM_TABELA_BUSCA),
                primeiroValor(params, PARAM_TABELA_CODIGO),
                valores(params, PARAM_TABELA_STATUS),
                filtrosColuna(params),
                primeiroValor(params, PARAM_SORT_FIELD),
                primeiroValor(params, PARAM_SORT_DIRECTION)
        );
    }

    private Map<String, List<String>> filtrosColuna(MultiValueMap<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> filtros = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String chave = entry.getKey();
            if (chave == null || !chave.startsWith(PREFIXO_FILTRO_COLUNA)) {
                continue;
            }

            String coluna = chave.substring(PREFIXO_FILTRO_COLUNA.length()).trim();
            List<String> valores = normalizarValores(entry.getValue());
            if (!coluna.isEmpty() && !valores.isEmpty()) {
                filtros.put(coluna, valores);
            }
        }

        return Map.copyOf(filtros);
    }

    private String primeiroValor(MultiValueMap<String, String> params, String chave) {
        if (params == null) {
            return null;
        }

        return normalizarTexto(params.getFirst(chave));
    }

    private List<String> valores(MultiValueMap<String, String> params, String chave) {
        if (params == null) {
            return List.of();
        }

        return normalizarValores(params.get(chave));
    }

    private List<String> normalizarValores(List<String> valores) {
        if (valores == null || valores.isEmpty()) {
            return List.of();
        }

        return valores.stream()
                .map(this::normalizarTexto)
                .filter(valor -> valor != null)
                .distinct()
                .toList();
    }

    private String normalizarTexto(String valor) {
        if (valor == null) {
            return null;
        }

        String texto = valor.trim();
        return texto.isEmpty() ? null : texto;
    }

    private int calcularTotalPaginas(long totalElementos, int tamanho) {
        if (totalElementos <= 0 || tamanho <= 0) {
            return 0;
        }

        return (int) Math.ceil((double) totalElementos / tamanho);
    }

    private long valorOuZero(Long valor) {
        return valor != null ? valor : 0L;
    }

    private BigDecimal percentualOuZero(BigDecimal percentual) {
        return percentual != null ? percentual : BigDecimal.ZERO;
    }
}
