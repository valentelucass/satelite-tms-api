package com.example.satelite.services.vedacit;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.services.etl.EslRequestContext;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;

import feign.FeignException;

/**
 * Resolve a data real de entrega para o comprovante Vedacit sem tocar no
 * cursor produtivo. A imagem continua sendo responsabilidade da fonte SFTP.
 */
@Service
public class VedacitDataEntregaService {

    private static final int CODIGO_ENTREGA_REALIZADA = 1;
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String ROTA_OCORRENCIA_ENTREGA = "VEDACIT_DELIVERY_OCCURRENCE";

    private final RodogarciaClient rodogarciaClient;
    private final EslRequestPolicyService eslRequestPolicyService;

    @Value("${RODOGARCIA_TOKEN_VEDACIT:}")
    private String tokenVedacit;

    @Value("${VEDACIT_DELIVERY_OCCURRENCE_MAX_PAGES:20}")
    private int maximoPaginas = 20;

    public VedacitDataEntregaService(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
    }

    public Resolucao resolver(String chaveNfe, String chaveCteEfetiva) {
        if (!chaveFiscalValida(chaveNfe) || !chaveFiscalValida(chaveCteEfetiva)) {
            return Resolucao.incompleta("DATA_ENTREGA_IDENTIDADE_FISCAL_INVALIDA");
        }
        if (tokenVedacit == null || tokenVedacit.isBlank()) {
            return Resolucao.indisponivel("DATA_ENTREGA_CREDENCIAL_ESL_AUSENTE");
        }

        Set<Long> cursoresVisitados = new HashSet<>();
        Set<OffsetDateTime> datasEncontradas = new LinkedHashSet<>();
        Long idEventoEncontrado = null;
        Long inicio = null;
        int paginas = Math.max(1, maximoPaginas);

        try {
            for (int pagina = 0; pagina < paginas; pagina++) {
                if (inicio != null && !cursoresVisitados.add(inicio)) {
                    return Resolucao.incompleta("DATA_ENTREGA_PAGINACAO_CURSOR_REPETIDO");
                }
                Long cursorDaPagina = inicio;

                EslLoteResponseDTO lote = eslRequestPolicyService.executarComTelemetria(
                        EslRequestContext.criar(DESTINO_VEDACIT, ROTA_OCORRENCIA_ENTREGA),
                        () -> rodogarciaClient.buscarOcorrencias(
                                "Bearer " + tokenVedacit.trim(),
                                cursorDaPagina,
                                chaveNfe,
                                null,
                                CODIGO_ENTREGA_REALIZADA
                        )
                );

                if (lote == null || lote.data() == null || lote.paging() == null) {
                    return Resolucao.incompleta("DATA_ENTREGA_RESPOSTA_ESL_INCOMPLETA");
                }

                for (EslOcorrenciaDTO ocorrencia : lote.data()) {
                    if (ocorrencia == null) {
                        return Resolucao.incompleta("DATA_ENTREGA_RESPOSTA_ESL_INCONSISTENTE");
                    }
                    if (!correspondeAoComprovante(ocorrencia, chaveNfe, chaveCteEfetiva)) {
                        continue;
                    }
                    if (ocorrencia.occurrenceAt() == null) {
                        return Resolucao.semDataValida("DATA_ENTREGA_OCORRENCIA_SEM_DATA_VALIDA");
                    }
                    datasEncontradas.add(ocorrencia.occurrenceAt());
                    if (idEventoEncontrado == null) {
                        idEventoEncontrado = ocorrencia.id();
                    }
                }

                Long proximo = lote.paging().nextId();
                if (proximo == null) {
                    return concluir(datasEncontradas, idEventoEncontrado);
                }
                if (pagina + 1 >= paginas) {
                    return Resolucao.incompleta("DATA_ENTREGA_PAGINACAO_LIMITE_ATINGIDO");
                }
                inicio = proximo;
            }
        } catch (EslRequestTransientException e) {
            return Resolucao.indisponivel("DATA_ENTREGA_ESL_INDISPONIVEL_HTTP_" + e.status());
        } catch (FeignException e) {
            return Resolucao.indisponivel("DATA_ENTREGA_ESL_INDISPONIVEL_HTTP_" + normalizarStatus(e.status()));
        } catch (RuntimeException e) {
            return Resolucao.indisponivel("DATA_ENTREGA_ESL_INDISPONIVEL_SEM_RESPOSTA");
        }

        return Resolucao.incompleta("DATA_ENTREGA_PAGINACAO_INCOMPLETA");
    }

    private Resolucao concluir(Set<OffsetDateTime> datasEncontradas, Long idEventoEncontrado) {
        if (datasEncontradas.isEmpty()) {
            return Resolucao.ausente("DATA_ENTREGA_OCORRENCIA_NAO_ENCONTRADA");
        }
        if (datasEncontradas.size() > 1) {
            return Resolucao.ambigua("DATA_ENTREGA_OCORRENCIAS_CONFLITANTES");
        }
        return Resolucao.encontrada(datasEncontradas.iterator().next(), idEventoEncontrado);
    }

    private boolean correspondeAoComprovante(EslOcorrenciaDTO ocorrencia, String chaveNfe, String chaveCte) {
        return ocorrencia.occurrence() != null
                && Integer.valueOf(CODIGO_ENTREGA_REALIZADA).equals(ocorrencia.occurrence().code())
                && ocorrencia.invoice() != null
                && chaveNfe.equals(ocorrencia.invoice().key())
                && ocorrencia.freight() != null
                && chaveCte.equals(ocorrencia.freight().cteKey());
    }

    private boolean chaveFiscalValida(String chave) {
        return chave != null && chave.matches("\\d{44}");
    }

    private int normalizarStatus(int status) {
        return status > 0 ? status : EslRequestPolicyService.STATUS_SEM_RESPOSTA_HTTP;
    }

    public enum Situacao {
        ENCONTRADA,
        AUSENTE,
        AMBIGUA,
        SEM_DATA_VALIDA,
        CONSULTA_INCOMPLETA,
        INDISPONIVEL
    }

    public record Resolucao(Situacao situacao, OffsetDateTime dataEntrega, Long idEvento, String motivo) {

        public static Resolucao encontrada(OffsetDateTime dataEntrega, Long idEvento) {
            return new Resolucao(Situacao.ENCONTRADA, dataEntrega, idEvento, null);
        }

        public static Resolucao ausente(String motivo) {
            return new Resolucao(Situacao.AUSENTE, null, null, motivo);
        }

        public static Resolucao ambigua(String motivo) {
            return new Resolucao(Situacao.AMBIGUA, null, null, motivo);
        }

        public static Resolucao semDataValida(String motivo) {
            return new Resolucao(Situacao.SEM_DATA_VALIDA, null, null, motivo);
        }

        public static Resolucao incompleta(String motivo) {
            return new Resolucao(Situacao.CONSULTA_INCOMPLETA, null, null, motivo);
        }

        public static Resolucao indisponivel(String motivo) {
            return new Resolucao(Situacao.INDISPONIVEL, null, null, motivo);
        }

        public boolean encontrada() {
            return situacao == Situacao.ENCONTRADA && dataEntrega != null;
        }
    }
}
