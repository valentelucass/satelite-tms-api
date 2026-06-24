package com.example.satelite.services.auditoria;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.satelite.dto.auditoria.AuditoriaIntegracoesClientesResponseDTO;
import com.example.satelite.dto.auditoria.MetricaConsolidadaDTO;
import com.example.satelite.dto.auditoria.PaginacaoDTO;
import com.example.satelite.dto.auditoria.PendenciaDTO;
import com.example.satelite.dto.auditoria.PendenciasPaginadasDTO;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.repositories.LogIntegracaoRepository.MetricaIntegracaoClienteProjection;
import com.example.satelite.repositories.LogIntegracaoRepository.PendenciaIntegracaoClienteProjection;

@Service
public class IntegracaoAuditoriaService {

    private static final int TAMANHO_PADRAO = 100;
    private static final int TAMANHO_MAXIMO = 500;

    private final LogIntegracaoRepository logIntegracaoRepository;

    public IntegracaoAuditoriaService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    public AuditoriaIntegracoesClientesResponseDTO consultarIntegracoesClientes(int pagina, int tamanho) {
        PageRequest pageable = PageRequest.of(normalizarPagina(pagina), normalizarTamanho(tamanho));

        List<MetricaConsolidadaDTO> metricas = logIntegracaoRepository.buscarMetricasIntegracoesClientes()
                .stream()
                .map(this::mapearMetrica)
                .toList();

        Page<PendenciaIntegracaoClienteProjection> paginaPendencias =
                logIntegracaoRepository.buscarPendenciasIntegracoesClientes(pageable);

        List<PendenciaDTO> pendencias = paginaPendencias.getContent()
                .stream()
                .map(this::mapearPendencia)
                .toList();

        PaginacaoDTO paginacao = new PaginacaoDTO(
                paginaPendencias.getNumber(),
                paginaPendencias.getSize(),
                paginaPendencias.getTotalElements(),
                paginaPendencias.getTotalPages(),
                paginaPendencias.isFirst(),
                paginaPendencias.isLast()
        );

        return new AuditoriaIntegracoesClientesResponseDTO(
                LocalDateTime.now(),
                metricas,
                new PendenciasPaginadasDTO(pendencias, paginacao)
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

    private PendenciaDTO mapearPendencia(PendenciaIntegracaoClienteProjection pendencia) {
        return new PendenciaDTO(
                pendencia.getId(),
                pendencia.getSistemaDestino(),
                pendencia.getOccurrenceId(),
                pendencia.getFreightId(),
                pendencia.getChaveNfe(),
                pendencia.getNumeroNf(),
                pendencia.getSerieNf(),
                pendencia.getStatusDados(),
                pendencia.getStatusCanhoto(),
                pendencia.getMensagemErroDados(),
                pendencia.getMensagemErroCanhoto(),
                pendencia.getDataProcessamento(),
                pendencia.getDataProcessamentoDados(),
                pendencia.getDataProcessamentoCanhoto()
        );
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

    private long valorOuZero(Long valor) {
        return valor != null ? valor : 0L;
    }

    private BigDecimal percentualOuZero(BigDecimal percentual) {
        return percentual != null ? percentual : BigDecimal.ZERO;
    }
}
