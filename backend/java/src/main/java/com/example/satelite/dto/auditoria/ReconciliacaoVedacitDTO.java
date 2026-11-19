package com.example.satelite.dto.auditoria;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Contratos de auditoria tecnica; nunca contêm imagem, XML ou retorno SOAP bruto. */
public final class ReconciliacaoVedacitDTO {
    private ReconciliacaoVedacitDTO() { }

    public record Execucao(long id, LocalDate dia, long ultimoId, long limiteId, String status,
            String bloqueioSftp, String bloqueioConsultaXml, String bloqueioConsultaPod, String bloqueioOrigemXml,
            String bloqueioEnvioXml, String bloqueioEnvioPod) { }

    public record Candidato(long id, String nfe, String cte, String ctePod, String cliente, boolean arquivado,
            String statusXml, String statusPod, String classificacao, LocalDateTime dataXml,
            String erroXml, int tentativasXml, LocalDateTime xmlConfirmadoEm, boolean xmlSucessoHistorico,
            boolean podConfirmado, LocalDateTime podConfirmadoEm, boolean dataPodConfiavel, String referencia) {
        public boolean identidadeValida() {
            return nfe != null && nfe.matches("\\d{44}") && cte != null && cte.matches("\\d{44}");
        }
    }

    public record Revisao(String xml, String comprovante, String acao, String detalhe) { }
    public record ResultadoConsulta(String codigo, boolean interromperFonte) { }
    public record Resumo(String etapa, String resultado, long quantidade) { }
}
