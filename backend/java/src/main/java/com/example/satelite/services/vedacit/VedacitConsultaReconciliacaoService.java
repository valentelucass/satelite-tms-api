package com.example.satelite.services.vedacit;

import com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.ResultadoConsulta;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Exclusivamente consultas dos contratos SOAP gerados. Nunca confirma por nome ou NF aproximada. */
@Service
public class VedacitConsultaReconciliacaoService {
    private final VedacitIntegrationService integracao;
    public VedacitConsultaReconciliacaoService(VedacitIntegrationService integracao) { this.integracao=integracao; }

    public ResultadoConsulta consultarXml(String cte) {
        try {
            var porta=integracao.criarPortaCte();
            var r=integracao.executarSoapComPrazo(() -> porta.buscarCTePorChave(cte),"Consulta de CT-e");
            if(r==null) return falha("RESPOSTA_VAZIA");
            if(!Boolean.TRUE.equals(r.isStatus())) return classificar(r.getMensagem()==null?null:r.getMensagem().getValue());
            var objeto=r.getObjeto()==null?null:r.getObjeto().getValue();
            if(objeto==null) return new ResultadoConsulta("NAO_LOCALIZADO_NO_DESTINO",false);
            if(objeto.getChave()==null || !cte.equals(objeto.getChave().getValue()))
                return new ResultadoConsulta("RESPOSTA_COM_IDENTIDADE_DIVERGENTE",true);
            // DataRetorno e DataEmissao nao sao a data historica de aceite do XML.
            return new ResultadoConsulta("XML_PRESENTE_NO_DESTINO_DATA_A_CONFERIR",false);
        } catch(Exception ex) {
            if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return classificar(ex.getMessage());
        }
    }

    public ResultadoConsulta consultarComprovante(String nfe) {
        try {
            var porta=integracao.criarPortaNFe();
            var r=integracao.executarSoapComPrazo(() -> porta.buscarCanhotoPorChaveNFe(nfe),"Consulta de comprovante");
            if(r==null) return falha("RESPOSTA_VAZIA");
            if(!Boolean.TRUE.equals(r.isStatus())) return classificar(r.getMensagem()==null?null:r.getMensagem().getValue());
            var objeto=r.getObjeto()==null?null:r.getObjeto().getValue();
            if(objeto==null || objeto.getArquivo()==null || objeto.getArquivo().getValue()==null
                    || objeto.getArquivo().getValue().isBlank()) return new ResultadoConsulta("COMPROVANTE_NAO_LOCALIZADO_NO_DESTINO",false);
            // Este contrato retorna arquivo/extensao, sem chave CT-e ou primeira data. Nao libera timeout.
            return new ResultadoConsulta("COMPROVANTE_NFE_PRESENTE_VINCULO_CTE_A_CONFERIR",false);
        } catch(Exception ex) {
            if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return classificar(ex.getMessage());
        }
    }
    static ResultadoConsulta classificar(String mensagem) {
        String m=Normalizer.normalize(mensagem==null?"":mensagem,Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        if(m.contains("autoriza") || m.contains("unauthor") || m.contains("forbidden") || m.contains("401") || m.contains("403")
                || m.contains("sem permiss") || m.contains("permissao negada") || m.contains("acesso negado"))
            return falha("CONSULTA_SEM_PERMISSAO");
        if(m.contains("nao encontrad") || m.contains("nao localizad") || m.contains("nao existe"))
            return new ResultadoConsulta("NAO_LOCALIZADO_NO_DESTINO",false);
        return falha("CONSULTA_INDISPONIVEL");
    }
    private static ResultadoConsulta falha(String codigo) { return new ResultadoConsulta(codigo,true); }
}
