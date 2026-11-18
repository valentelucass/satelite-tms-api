package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.IntegracaoEventoRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import com.example.satelite.services.vedacit.VedacitDataEntregaService;
import com.example.satelite.utils.CteXmlValidator;

/** Preparação manual explícita; não é runner, bean automático ou autorização de envio. */
public final class VedacitVerifiedPairPreparation {
    private final LogIntegracaoRepository repository;
    private final EtlEstadoIntegracaoService state;
    private final VedacitDataEntregaService delivery;
    private final SftpDocumentoLockService locks;
    private final IntegracaoEventoRepository events;

    public VedacitVerifiedPairPreparation(LogIntegracaoRepository repository, EtlEstadoIntegracaoService state,
            VedacitDataEntregaService delivery, SftpDocumentoLockService locks, IntegracaoEventoRepository events) {
        this.repository=Objects.requireNonNull(repository); this.state=Objects.requireNonNull(state);
        this.delivery=Objects.requireNonNull(delivery); this.locks=Objects.requireNonNull(locks);
        this.events=Objects.requireNonNull(events);
    }

    public record Resultado(String categoria, Long logId, Long logFonteXml, Long eventoEntrega,
            LocalDateTime dataXml) {
        static Resultado retido(String motivo) { return new Resultado(motivo,null,null,null,null); }
    }

    public Resultado preparar(AlvosComprovanteVedacit.Alvo alvo, byte[] xml, VedacitSftpDocumentSource source) {
        Objects.requireNonNull(alvo); Objects.requireNonNull(source);
        AlvosComprovanteVedacit.validarEnvio(alvo.chaveNfe(), alvo.chaveCte());
        if (xml==null || xml.length==0 || xml.length>20*1024*1024
                || !CteXmlValidator.corresponde(xml, alvo.chaveCte(), alvo.chaveNfe()))
            return Resultado.retido("XML_NAO_COMPROVA_PAR_EXATO");
        return locks.executarComLock("VEDACIT_XML",alvo.chaveCte(),alvo.chaveCte(),
                () -> locks.executarComLock("VEDACIT",alvo.chaveNfe(),alvo.chaveCte(),
                        () -> prepararSobLock(alvo,source)).orElse(Resultado.retido("LOCK_PAR_INDISPONIVEL")))
                .orElse(Resultado.retido("LOCK_XML_INDISPONIVEL"));
    }

    private Resultado prepararSobLock(AlvosComprovanteVedacit.Alvo alvo, VedacitSftpDocumentSource source) {
        String nf=alvo.chaveNfe(), ct=alvo.chaveCte();
        if (state.canhotoVedacitSucessoRegistradoPorPar(nf,ct)) return Resultado.retido("COMPROVANTE_JA_CONFIRMADO");
        if (state.canhotoVedacitRetidoPorPar(nf,ct)
                || events.existeInicioEnvio("COMPROVANTE","POD:"+nf+":"+ct,null))
            return Resultado.retido("HISTORICO_COMPROVANTE_PROTEGIDO");
        var active=repository.findAtivosVedacitPorParExato(nf,ct,PageRequest.of(0,2));
        if (active.size()>1) return Resultado.retido("AUDITORIA_ATIVA_NAO_UNICA");
        LogIntegracaoModel current=active.isEmpty()?null:active.get(0);
        if (current!=null && (!"VEDACIT".equals(current.getSistemaDestino()) || Boolean.TRUE.equals(current.getArquivado())
                || !nf.equals(current.getChaveNfe()) || !ct.equals(current.getChaveCte())))
            return Resultado.retido("AUDITORIA_IDENTIDADE_DIVERGENTE");
        if (current!=null && "SUCESSO".equals(current.getStatusDados()))
            return current.getDataProcessamentoDados()==null?Resultado.retido("SUCESSO_SEM_DATA_PRESERVADO")
                    :new Resultado("JA_PREPARADO",current.getId(),current.getId(),null,current.getDataProcessamentoDados());
        if (current!=null && (current.getDataProcessamentoCanhoto()!=null
                || current.getTentativasCanhoto()!=null && current.getTentativasCanhoto()>0
                || Set.of("RETER","IGNORAR","SUPRIMIR","RETIDO","PAUSAR","INTERVENCAO","AGUARDANDO_CONFIRMACAO","A_CONFERIR")
                    .contains(String.valueOf(current.getCanhotoAcaoPainel()))
                || current.getCanhotoProximaAvaliacaoEm()!=null && current.getCanhotoProximaAvaliacaoEm().isAfter(state.agoraAuditoria())
                || current.getCanhotoChaveCteEfetiva()!=null && !current.getCanhotoChaveCteEfetiva().isBlank()
                    && !ct.equals(current.getCanhotoChaveCteEfetiva())
                || !(Set.of("PENDENTE_ORIGEM","RECEBIDO","NAO_APLICAVEL").contains(String.valueOf(current.getStatusDados()))
                    || recusaControladaOrigem(current))
                || events.existeInicioEnvio("DADOS","CTE:"+ct,current.getId())))
            return Resultado.retido("ESTADO_EXISTENTE_PROTEGIDO");
        var confirmations=repository.findXmlVedacitConfirmadoDatado(ct,PageRequest.of(0,1));
        if (confirmations.isEmpty()) return Resultado.retido("XML_SEM_CONFIRMACAO_DATADA");
        LogIntegracaoModel proof=confirmations.get(0); LocalDateTime now=state.agoraAuditoria();
        if (proof.getId()==null || !"VEDACIT".equals(proof.getSistemaDestino()) || !ct.equals(proof.getChaveCte())
                || !"SUCESSO".equals(proof.getStatusDados()) || proof.getDataProcessamentoDados()==null
                || proof.getDataProcessamentoDados().isAfter(now)) return Resultado.retido("PROVA_XML_INCONSISTENTE");
        var document=source.buscarComprovante(ct,nf);
        if (document.isEmpty() || !nf.equals(document.get().chaveNfe()) || !ct.equals(document.get().chaveCte())
                || document.get().conteudo()==null || document.get().conteudo().length==0
                || document.get().conteudo().length>20*1024*1024) return Resultado.retido("FONTE_COMPROVANTE_INDISPONIVEL");
        var real=delivery.resolver(nf,ct);
        if (!real.encontrada() || real.idEvento()==null || real.dataEntrega()==null)
            return Resultado.retido("ENTREGA_REAL_NAO_CONFIRMADA");
        boolean created=current==null;
        if (created) current=LogIntegracaoModel.builder().sistemaDestino("VEDACIT").sftpCliente("VEDACIT")
                .chaveNfe(nf).chaveCte(ct).occurrenceId(real.idEvento()).status("PARCIAL")
                .statusCanhoto("PENDENTE_FOTO").tentativasDados(0).tentativasCanhoto(0)
                .canhotoOrigem(source.origemComprovante()).canhotoReferencia(source.referenciaComprovante(ct,nf))
                .canhotoClassificacaoOperacional("PENDENTE_ENVIO").dataProcessamento(now).build();
        current.setStatusDados("SUCESSO");
        current.setDataProcessamentoDados(proof.getDataProcessamentoDados());
        // Não altera tentativas, datas de comprovante, prazo, classificação ou chaves existentes.
        var saved=state.salvarConciliacaoXmlSemEnvio(current,proof.getId(),real.idEvento());
        return new Resultado(created?"CRIADO_COM_PROVAS_EXATAS":"XML_CONCILIADO_SEM_ENVIO",saved.getId(),
                proof.getId(),real.idEvento(),proof.getDataProcessamentoDados());
    }

    private static boolean recusaControladaOrigem(LogIntegracaoModel registro) {
        return "ERRO_DESTINO".equals(registro.getStatusDados()) && Set.of(
                "ORIGEM_XML_AUTENTICACAO_EM_ESPERA", "ORIGEM_XML_HTTP_401", "ORIGEM_XML_HTTP_403")
                .contains(String.valueOf(registro.getMensagemErroDados()));
    }
}
