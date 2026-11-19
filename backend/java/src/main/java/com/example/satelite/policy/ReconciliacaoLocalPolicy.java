package com.example.satelite.policy;

import java.time.LocalDateTime;
import com.example.satelite.dto.auditoria.ReconciliacaoLocalDTO.Candidato;
import com.example.satelite.dto.auditoria.ReconciliacaoLocalDTO.Revisao;

/** Diagnóstico do estado registrado; não confirma recebimento externo nem autoriza envio. */
public final class ReconciliacaoLocalPolicy {
    private ReconciliacaoLocalPolicy() { }
    public static Revisao revisar(Candidato c) {
        return new Revisao(etapa(c.dados(), c.dataDados()), etapa(c.comprovante(), c.dataComprovante()),
                c.arquivado() ? "ARQUIVADO_CONFERIDO_SEM_REATIVACAO" : "CONFERENCIA_LOCAL_SEM_REENVIO");
    }
    static String etapa(String status, LocalDateTime data) {
        if ("NAO_APLICAVEL".equals(status)) return "NAO_APLICAVEL";
        if ("SUCESSO".equals(status)) return data != null ? "SUCESSO_REGISTRADO_LOCAL" : "SUCESSO_SEM_DATA_A_CONFERIR";
        if (status == null || status.isBlank()) return "ESTADO_A_CONFERIR";
        if (status.startsWith("PENDENTE")) return "PENDENCIA_REGISTRADA";
        if (status.startsWith("ERRO")) return "FALHA_REGISTRADA_SEM_CONFIRMACAO_EXTERNA";
        return "ESTADO_A_CONFERIR";
    }
}
