package com.example.satelite.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.example.satelite.dto.auditoria.ReconciliacaoLocalDTO.Candidato;

class ReconciliacaoLocalPolicyTest {
    @Test void sucessoSemDataNaoViraConfirmacaoDatada() {
        var r=ReconciliacaoLocalPolicy.revisar(new Candidato(1,false,"SUCESSO","SUCESSO",null,LocalDateTime.now()));
        assertEquals("SUCESSO_SEM_DATA_A_CONFERIR",r.dados());
        assertEquals("SUCESSO_REGISTRADO_LOCAL",r.comprovante());
        assertEquals("CONFERENCIA_LOCAL_SEM_REENVIO",r.acao());
    }
    @Test void falhaEArquivamentoNaoLiberamReenvio() {
        var r=ReconciliacaoLocalPolicy.revisar(new Candidato(1,true,"ERRO_DESTINO","PENDENTE_FOTO",null,null));
        assertEquals("FALHA_REGISTRADA_SEM_CONFIRMACAO_EXTERNA",r.dados());
        assertEquals("PENDENCIA_REGISTRADA",r.comprovante());
        assertEquals("ARQUIVADO_CONFERIDO_SEM_REATIVACAO",r.acao());
    }
    @Test void ausenteOuDesconhecidoFicaAConferir() {
        assertEquals("ESTADO_A_CONFERIR",ReconciliacaoLocalPolicy.etapa(null,null));
        assertEquals("ESTADO_A_CONFERIR",ReconciliacaoLocalPolicy.etapa("TIMEOUT",null));
        assertEquals("NAO_APLICAVEL",ReconciliacaoLocalPolicy.etapa("NAO_APLICAVEL",null));
    }
}
