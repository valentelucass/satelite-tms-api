package com.example.satelite.services.auditoria;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

import com.example.satelite.dto.auditoria.AuditoriaIntegracoesClientesResponseDTO;
import com.example.satelite.dto.auditoria.IntegracaoEvolucaoDiariaDTO;
import com.example.satelite.dto.auditoria.MetricaConsolidadaDTO;
import com.example.satelite.dto.auditoria.PaginacaoDTO;
import com.example.satelite.dto.auditoria.PendenciasPaginadasDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository.Filtros;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository.PendenciasResultado;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.repositories.LogIntegracaoRepository.IntegracaoEvolucaoDiariaProjection;
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
    private static final String PARAM_ESCOPO = "escopo";
    private static final String PARAM_DATA_INICIAL = "dataInicial";
    private static final String PARAM_DATA_FINAL = "dataFinal";
    private static final String PREFIXO_FILTRO_COLUNA = "f.tabelaColuna.";
    private static final LocalDateTime SQL_DATA_INICIAL_PADRAO = LocalDate.of(1900, 1, 1).atStartOfDay();
    private static final LocalDateTime SQL_DATA_FINAL_LIMITE_PADRAO =
            LocalDateTime.of(LocalDate.of(9999, 12, 31), LocalTime.of(23, 59, 59, 999_000_000));
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
            String dataInicial,
            String dataFinal,
            MultiValueMap<String, String> params
    ) {
        int paginaNormalizada = normalizarPagina(pagina);
        int tamanhoNormalizado = normalizarTamanho(tamanho);
        PeriodoFiltro periodo = lerPeriodoOpcional(
                primeiroTexto(dataInicial, primeiroValor(params, PARAM_DATA_INICIAL)),
                primeiroTexto(dataFinal, primeiroValor(params, PARAM_DATA_FINAL))
        );

        List<MetricaConsolidadaDTO> metricas = logIntegracaoRepository.buscarMetricasIntegracoesClientes(
                        periodo.dataInicialSql(),
                        periodo.dataFinalLimitSql()
                )
                .stream()
                .map(this::mapearMetrica)
                .toList();

        PendenciasResultado pendencias = integracaoAuditoriaQueryRepository.buscarPendencias(
                lerFiltros(params, periodo),
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

    public List<IntegracaoEvolucaoDiariaDTO> consultarEvolucaoDiaria(String dataInicial, String dataFinal) {
        PeriodoFiltro periodo = lerPeriodoObrigatorio(dataInicial, dataFinal);
        return logIntegracaoRepository.buscarEvolucaoDiariaIntegracoes(
                        periodo.dataInicialSql(),
                        periodo.dataFinalLimitSql()
                )
                .stream()
                .map(this::mapearEvolucaoDiaria)
                .toList();
    }

    private MetricaConsolidadaDTO mapearMetrica(MetricaIntegracaoClienteProjection metrica) {
        return new MetricaConsolidadaDTO(
                metrica.getSistemaDestino(),
                valorOuZero(metrica.getTotalRegistros()),
                percentualOuZero(metrica.getPercentualXmlSucesso()),
                percentualOuZero(metrica.getPercentualCanhotoSucesso())
        );
    }

    private IntegracaoEvolucaoDiariaDTO mapearEvolucaoDiaria(IntegracaoEvolucaoDiariaProjection item) {
        return new IntegracaoEvolucaoDiariaDTO(
                item.getData(),
                inteiroOuZero(item.getTotal()),
                inteiroOuZero(item.getSucessos()),
                inteiroOuZero(item.getErros())
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

    private Filtros lerFiltros(MultiValueMap<String, String> params, PeriodoFiltro periodo) {
        return new Filtros(
                primeiroValor(params, PARAM_TABELA_BUSCA),
                primeiroValor(params, PARAM_TABELA_CODIGO),
                valores(params, PARAM_TABELA_STATUS),
                filtrosColuna(params),
                primeiroValor(params, PARAM_ESCOPO),
                primeiroValor(params, PARAM_SORT_FIELD),
                primeiroValor(params, PARAM_SORT_DIRECTION),
                periodo.dataInicial(),
                periodo.dataFinal()
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

    private String primeiroTexto(String valorPreferencial, String valorFallback) {
        String valor = normalizarTexto(valorPreferencial);
        return valor != null ? valor : normalizarTexto(valorFallback);
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

    private int inteiroOuZero(Integer valor) {
        return valor != null ? valor : 0;
    }

    private PeriodoFiltro lerPeriodoOpcional(String dataInicial, String dataFinal) {
        String inicioTexto = normalizarTexto(dataInicial);
        String finalTexto = normalizarTexto(dataFinal);
        if (inicioTexto == null && finalTexto == null) {
            return PeriodoFiltro.semFiltro();
        }

        if (inicioTexto == null || finalTexto == null) {
            throw erroPeriodo("Informe dataInicial e dataFinal para filtrar a auditoria de integracoes.");
        }

        return montarPeriodo(inicioTexto, finalTexto);
    }

    private PeriodoFiltro lerPeriodoObrigatorio(String dataInicial, String dataFinal) {
        String inicioTexto = normalizarTexto(dataInicial);
        String finalTexto = normalizarTexto(dataFinal);
        if (inicioTexto == null || finalTexto == null) {
            throw erroPeriodo("Informe dataInicial e dataFinal para consultar a evolucao diaria.");
        }

        return montarPeriodo(inicioTexto, finalTexto);
    }

    private PeriodoFiltro montarPeriodo(String dataInicial, String dataFinal) {
        LocalDate inicio = parseData(dataInicial, "dataInicial");
        LocalDate fim = parseData(dataFinal, "dataFinal");
        if (fim.isBefore(inicio)) {
            throw erroPeriodo("dataFinal nao pode ser anterior a dataInicial.");
        }

        return new PeriodoFiltro(
                dataInicial,
                dataFinal,
                inicio.atStartOfDay(),
                fim.plusDays(1).atStartOfDay()
        );
    }

    private LocalDate parseData(String valor, String nomeParametro) {
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException ex) {
            throw erroPeriodo(nomeParametro + " deve estar no formato yyyy-MM-dd.");
        }
    }

    private ResponseStatusException erroPeriodo(String mensagem) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }

    private record PeriodoFiltro(
            String dataInicial,
            String dataFinal,
            LocalDateTime dataInicialSql,
            LocalDateTime dataFinalLimitSql
    ) {
        static PeriodoFiltro semFiltro() {
            return new PeriodoFiltro(null, null, SQL_DATA_INICIAL_PADRAO, SQL_DATA_FINAL_LIMITE_PADRAO);
        }
    }
}
