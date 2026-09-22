package com.example.satelite.policy;

import java.util.Locale;
import java.util.Set;

/** Próxima ação documental; não substitui os estados que protegem envio/cursor. */
public enum AcaoEtapaIntegracao {
    CONFIRMADO, IGNORADO, AGUARDANDO_PROCESSAMENTO, NOVA_TENTATIVA,
    AGUARDANDO_ORIGEM, AGUARDANDO_CONFIRMACAO, INTERVENCAO, A_CONFERIR;

    public static AcaoEtapaIntegracao classificar(String status, String bloqueio, String mensagem) {
        String s = normalizar(status), b = normalizar(bloqueio), m = normalizar(mensagem);
        if (Set.of("SUCESSO", "ENVIADO", "PROCESSADO").contains(s)) return CONFIRMADO;
        if (Set.of("NAO_APLICAVEL", "IGNORADO").contains(s)) return IGNORADO;
        if (m.startsWith("SOAP_ANTERIOR_EM_ANDAMENTO")) return NOVA_TENTATIVA;
        if (b.equals("TIMEOUT_AMBIGUO") || m.contains("SEM CONFIRMA") || m.contains("SEM_CONFIRMA")
                || m.contains("RESULTADO_DESCONHECIDO") || m.contains("ENVIO_INICIADO_SEM_RESULTADO")
                || m.contains("READ TIMED OUT") || m.contains("SOCKETTIMEOUT") || m.contains("TIMEOUT TOTAL DA VEDACIT")
                || m.contains("INTERROMPIDO DURANTE ENVIO"))
            return AGUARDANDO_CONFIRMACAO;
        if (b.equals("BLOQUEADO_DESTINO") || s.equals("ERRO_VALIDACAO")
                || m.contains("CORRELACAO_INVALIDA") || m.contains("CHAVE_INVALIDA")
                || m.contains("VEDACIT RECUSOU")) return INTERVENCAO;
        if (b.equals("BLOQUEADO_ORIGEM") || s.equals("PENDENTE_ORIGEM") || s.equals("PENDENTE_FOTO")
                || m.equals("ORIGEM_XML_HTTP_401") || m.equals("ORIGEM_XML_HTTP_403")
                || m.equals("ORIGEM_XML_AUTENTICACAO_EM_ESPERA") || m.equals("ORIGEM_XML_AUSENTE")
                || m.startsWith("DATA_ENTREGA_")) return AGUARDANDO_ORIGEM;
        if (b.equals("PENDENTE_TECNICO") || s.equals("PENDENTE_TECNICO") || s.equals("ERRO_DESTINO"))
            return NOVA_TENTATIVA;
        if (Set.of("RECEBIDO", "PENDENTE", "PENDENTE_ENVIO", "EM_PROCESSAMENTO").contains(s))
            return AGUARDANDO_PROCESSAMENTO;
        return A_CONFERIR;
    }

    public boolean permiteReavaliacaoAutomatica() {
        return this == NOVA_TENTATIVA || this == AGUARDANDO_ORIGEM || this == AGUARDANDO_PROCESSAMENTO;
    }

    public static String motivo(String mensagem, AcaoEtapaIntegracao acao) {
        String m = normalizar(mensagem);
        if (m.equals("ORIGEM_XML_HTTP_401") || m.equals("ORIGEM_XML_HTTP_403")) return "ACESSO_ORIGEM_RECUSADO";
        if (m.equals("ORIGEM_XML_AUTENTICACAO_EM_ESPERA")) return "ESPERA_ACESSO_ORIGEM";
        if (m.startsWith("DATA_ENTREGA_")) return "METADADO_ENTREGA";
        if (m.equals("ORIGEM_XML_AUSENTE")) return "DOCUMENTO_AUSENTE";
        return acao.name(); // Nunca persistir/expor a mensagem externa como código de auditoria.
    }

    private static String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toUpperCase(Locale.ROOT);
    }
}
