package com.example.satelite.services.selia;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.satelite.dto.selia.SeliaPreShipmentInvoiceDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentListRequestDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentListResponseDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentMessageDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentOrderDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentResponseOrderDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentResponseVolumeDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentVolumeDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;

@Service
public class SeliaPreShipmentListService {

    private static final String DESTINO_PLP = "SELIA_PLP";
    private static final String STATUS_ACEITO_PLP = SeliaPlpCorrelationService.STATUS_ACEITO_PLP;
    private static final String STATUS_SUCESSO = "SUCESSO";
    private static final String STATUS_NAO_APLICAVEL = "NAO_APLICAVEL";
    private static final ZoneId FUSO_HORARIO_OPERACIONAL = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_RESPOSTA = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final LogIntegracaoRepository logIntegracaoRepository;

    @Value("${SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY:}")
    private String logisticProviderApiKey;

    @Value("${SELIA_INTELIPOST_PLP_ENABLED:false}")
    private boolean plpEnabled;

    public SeliaPreShipmentListService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    @Transactional
    public SeliaPreShipmentListResponseDTO receber(
            String chaveLogisticProvider,
            SeliaPreShipmentListRequestDTO requisicao
    ) {
        validarChave(chaveLogisticProvider);
        if (!plpEnabled) {
            throw new SeliaPlpProcessingException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Recebimento de PLP indisponível para homologação.");
        }

        DadosPlp dados = validarRequisicao(requisicao);
        LogIntegracaoModel existente = logIntegracaoRepository
                .findTopBySistemaDestinoAndIntelipostPreShipmentListOrderByDataProcessamentoAscIdAsc(
                        DESTINO_PLP,
                        dados.intelipostPreShipmentList()
                )
                .orElse(null);
        if (existente != null) {
            return respostaAceite(dados, existente);
        }

        LocalDateTime agora = LocalDateTime.now();
        LogIntegracaoModel cabecalho = logIntegracaoRepository.save(LogIntegracaoModel.builder()
                .intelipostPreShipmentList(dados.intelipostPreShipmentList())
                .status(STATUS_ACEITO_PLP)
                .statusDados(STATUS_SUCESSO)
                .statusCanhoto(STATUS_NAO_APLICAVEL)
                .tentativasDados(0)
                .tentativasCanhoto(0)
                .sistemaDestino(DESTINO_PLP)
                .dataProcessamento(agora)
                .build());
        cabecalho.setLogisticsProviderShipmentList(cabecalho.getId());
        logIntegracaoRepository.save(cabecalho);

        List<LogIntegracaoModel> correlacoes = new ArrayList<>();
        for (CorrelacaoPlp correlacao : dados.correlacoes()) {
            correlacoes.add(LogIntegracaoModel.builder()
                    .chaveNfe(correlacao.chaveNfe())
                    .intelipostPreShipmentList(dados.intelipostPreShipmentList())
                    .logisticsProviderShipmentList(cabecalho.getLogisticsProviderShipmentList())
                    .orderNumber(correlacao.orderNumber())
                    .volumeNumber(correlacao.volumeNumber())
                    .status(STATUS_ACEITO_PLP)
                    .statusDados(STATUS_SUCESSO)
                    .statusCanhoto(STATUS_NAO_APLICAVEL)
                    .tentativasDados(0)
                    .tentativasCanhoto(0)
                    .sistemaDestino(SeliaPlpCorrelationService.DESTINO_PLP_MAPA)
                    .dataProcessamento(agora)
                    .build());
        }
        logIntegracaoRepository.saveAll(correlacoes);
        return respostaAceite(dados, cabecalho);
    }

    public SeliaPreShipmentListResponseDTO respostaErro(Long intelipostPreShipmentList, String mensagem) {
        return new SeliaPreShipmentListResponseDTO(
                intelipostPreShipmentList,
                null,
                null,
                List.of(),
                "ERROR",
                List.of(new SeliaPreShipmentMessageDTO("ERROR", mensagem, "selia.plp.error")),
                null
        );
    }

    private void validarChave(String chaveRecebida) {
        if (!textoPreenchido(logisticProviderApiKey)) {
            throw new SeliaPlpProcessingException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Autenticação de PLP não configurada.");
        }
        if (!textoPreenchido(chaveRecebida) || !MessageDigest.isEqual(
                logisticProviderApiKey.trim().getBytes(StandardCharsets.UTF_8),
                chaveRecebida.trim().getBytes(StandardCharsets.UTF_8)
        )) {
            throw new SeliaPlpProcessingException(HttpStatus.UNAUTHORIZED, "Não autorizado.");
        }
    }

    private DadosPlp validarRequisicao(SeliaPreShipmentListRequestDTO requisicao) {
        if (requisicao == null || requisicao.intelipostPreShipmentList() == null
                || requisicao.intelipostPreShipmentList() <= 0) {
            throw dadosInvalidos();
        }
        if (requisicao.shipmentOrders() == null || requisicao.shipmentOrders().isEmpty()) {
            throw dadosInvalidos();
        }

        List<RespostaPedido> pedidos = new ArrayList<>();
        Set<CorrelacaoPlp> correlacoes = new LinkedHashSet<>();
        for (SeliaPreShipmentOrderDTO pedido : requisicao.shipmentOrders()) {
            String numeroPedido = textoObrigatorio(pedido == null ? null : pedido.orderNumber());
            List<SeliaPreShipmentVolumeDTO> volumes = pedido == null ? null : pedido.shipmentOrderVolumes();
            if (volumes == null || volumes.isEmpty()) {
                throw dadosInvalidos();
            }

            List<String> numerosVolume = new ArrayList<>();
            for (SeliaPreShipmentVolumeDTO volume : volumes) {
                String numeroVolume = textoObrigatorio(volume == null ? null : volume.shipmentOrderVolumeNumber());
                List<SeliaPreShipmentInvoiceDTO> notas = volume == null ? null : volume.invoices();
                if (notas == null || notas.isEmpty()) {
                    throw dadosInvalidos();
                }
                numerosVolume.add(numeroVolume);
                for (SeliaPreShipmentInvoiceDTO nota : notas) {
                    correlacoes.add(new CorrelacaoPlp(
                            chaveNfeObrigatoria(nota == null ? null : nota.invoiceKey()),
                            numeroPedido,
                            numeroVolume
                    ));
                }
            }
            pedidos.add(new RespostaPedido(numeroPedido, List.copyOf(numerosVolume)));
        }
        return new DadosPlp(requisicao.intelipostPreShipmentList(), List.copyOf(pedidos), List.copyOf(correlacoes));
    }

    private SeliaPreShipmentListResponseDTO respostaAceite(DadosPlp dados, LogIntegracaoModel cabecalho) {
        Long numeroListaTransportadora = cabecalho.getLogisticsProviderShipmentList();
        if (numeroListaTransportadora == null) {
            throw new SeliaPlpProcessingException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível gerar a lista técnica da transportadora.");
        }
        LocalDateTime dataCriacao = cabecalho.getDataProcessamento() == null
                ? LocalDateTime.now()
                : cabecalho.getDataProcessamento();
        List<SeliaPreShipmentResponseOrderDTO> pedidos = dados.pedidos().stream()
                .map(pedido -> new SeliaPreShipmentResponseOrderDTO(
                        pedido.orderNumber(),
                        pedido.volumeNumbers().stream()
                                .map(SeliaPreShipmentResponseVolumeDTO::new)
                                .toList()
                ))
                .toList();
        return new SeliaPreShipmentListResponseDTO(
                dados.intelipostPreShipmentList(),
                numeroListaTransportadora,
                DATA_RESPOSTA.format(dataCriacao.atZone(FUSO_HORARIO_OPERACIONAL).toOffsetDateTime()),
                pedidos,
                "OK",
                List.of(new SeliaPreShipmentMessageDTO("SUCCESS", "Operação realizada com sucesso.", "success.message")),
                "SELIA-PLP-" + numeroListaTransportadora
        );
    }

    private SeliaPlpProcessingException dadosInvalidos() {
        return new SeliaPlpProcessingException(HttpStatus.BAD_REQUEST,
                "PLP rejeitada por dados obrigatórios inválidos.");
    }

    private String chaveNfeObrigatoria(String chaveNfe) {
        String normalizada = textoObrigatorio(chaveNfe);
        if (!normalizada.matches("\\d{44}")) {
            throw dadosInvalidos();
        }
        return normalizada;
    }

    private String textoObrigatorio(String valor) {
        if (!textoPreenchido(valor)) {
            throw dadosInvalidos();
        }
        String normalizado = valor.trim();
        if (normalizado.length() > 100) {
            throw dadosInvalidos();
        }
        return normalizado;
    }

    private boolean textoPreenchido(String valor) {
        return valor != null && !valor.isBlank();
    }

    private record DadosPlp(
            Long intelipostPreShipmentList,
            List<RespostaPedido> pedidos,
            List<CorrelacaoPlp> correlacoes
    ) {
    }

    private record RespostaPedido(String orderNumber, List<String> volumeNumbers) {
    }

    private record CorrelacaoPlp(String chaveNfe, String orderNumber, String volumeNumber) {
    }
}
