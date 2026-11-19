package com.example.satelite.services.etl;

import com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.Candidato;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import org.springframework.stereotype.Service;

/** Acoes usam os mesmos locks e releitura do worker normal; arquivados nunca sao reativados aqui. */
@Service
public class ReconciliacaoVedacitAcoesService {
    private final SftpDocumentoLockService locks;
    private final EtlEstadoIntegracaoService estado;
    private final EtlRegistroService registros;
    private final ReconciliacaoVedacitRepository auditoria;
    public ReconciliacaoVedacitAcoesService(SftpDocumentoLockService locks,EtlEstadoIntegracaoService estado,
            EtlRegistroService registros,ReconciliacaoVedacitRepository auditoria) {
        this.locks=locks;this.estado=estado;this.registros=registros;this.auditoria=auditoria;
    }
    public ResultadoAcao recuperarXml(Candidato c) {
        if(c.arquivado() || !c.identidadeValida()) return new ResultadoAcao("RETIDO",null);
        return locks.executarComLock("VEDACIT_XML",c.cte(),c.cte(),()->
                locks.executarComLock("VEDACIT",c.nfe(),c.cte(),()->{
                    var atual=estado.buscarAtivoPorId(c.id());
                    if(atual.isEmpty() || !c.nfe().equals(atual.get().getChaveNfe()) || !c.cte().equals(atual.get().getChaveCte()))
                        return new ResultadoAcao("ESTADO_ALTERADO",null);
                    var l=atual.get();
                    if(estado.xmlVedacitConfirmado(c.cte()) || estado.xmlVedacitSucessoSemData(c.cte())
                            || auditoria.xmlRetidoPorOutroHistorico(c.cte(),c.id())) return new ResultadoAcao("HISTORICO_PROTEGIDO",null);
                    var resultado=registros.reprocessarXmlCteVedacitPorChave(l,true);
                    return new ResultadoAcao(resultado.name(),l.getMensagemErroDados());
                }).orElse(new ResultadoAcao("LOCK_OCUPADO",null))).orElse(new ResultadoAcao("LOCK_OCUPADO",null));
    }
    public ResultadoAcao recuperarComprovante(Candidato c,VedacitSftpDocumentSource fonte) {
        if(c.arquivado() || !c.identidadeValida() || fonte==null) return new ResultadoAcao("RETIDO",null);
        return locks.executarComLock("VEDACIT",c.nfe(),c.ctePod(),()->{
            var atual=estado.buscarAtivoPorId(c.id());
            if(atual.isEmpty() || !c.nfe().equals(atual.get().getChaveNfe()) || !c.cte().equals(atual.get().getChaveCte()))
                return new ResultadoAcao("ESTADO_ALTERADO",null);
            var l=atual.get();
            String classe=l.getCanhotoClassificacaoOperacional();
            if(!"PENDENTE_ENVIO".equals(classe) && !"PENDENTE_TECNICO".equals(classe))
                return new ResultadoAcao("HISTORICO_PROTEGIDO",null);
            var resultado=registros.reprocessarCanhotoVedacitPorCte(l,fonte);
            return new ResultadoAcao(resultado.name(),l.getMensagemErroCanhoto());
        }).orElse(new ResultadoAcao("LOCK_OCUPADO",null));
    }
    public record ResultadoAcao(String codigo,String erro) { }

    public boolean conciliarAceiteLocal(Candidato c) {
        if(c.arquivado() || !c.identidadeValida() || !c.podConfirmado()) return false;
        return locks.executarComLock("VEDACIT",c.nfe(),c.ctePod(),()->{
            var atual=estado.buscarAtivoPorId(c.id());
            if(atual.isEmpty()) return false;
            var l=atual.get();
            String efetivo=l.getCanhotoChaveCteEfetiva()==null || l.getCanhotoChaveCteEfetiva().isBlank()
                    ?l.getChaveCte():l.getCanhotoChaveCteEfetiva();
            if(!c.nfe().equals(l.getChaveNfe()) || !c.ctePod().equals(efetivo)) return false;
            if("SUCESSO".equals(l.getStatusCanhoto()) && "SUCESSO".equals(l.getCanhotoClassificacaoOperacional())) return false;
            boolean aceito="SUCESSO".equals(l.getStatusCanhoto());
            l.setStatusCanhoto("SUCESSO");
            l.setStatus("SUCESSO".equals(l.getStatusDados())?"ENVIADO":"PARCIAL");
            l.setCanhotoClassificacaoOperacional("SUCESSO");
            if(!aceito) l.setDataProcessamentoCanhoto(c.podConfirmadoEm());
            estado.salvar(l);
            return true;
        }).orElse(false);
    }
}
