package com.example.satelite.services.etl;

import java.util.Locale;

/** Classificação operacional do canhoto, independente do texto técnico do erro. */
public enum ClassificacaoOperacionalCanhotoVedacit {
    SUCESSO,
    PENDENTE_ENVIO,
    PENDENTE_TECNICO,
    TIMEOUT_AMBIGUO,
    BLOQUEADO_ORIGEM,
    BLOQUEADO_DESTINO;

    public static ClassificacaoOperacionalCanhotoVedacit paraSucesso() {
        return SUCESSO;
    }

    public static ClassificacaoOperacionalCanhotoVedacit paraPendente(String mensagem) {
        String texto = normalizar(mensagem);
        if (texto.contains("não há comprovante") || texto.contains("nao ha comprovante")
                || texto.contains("chave") || texto.contains("arquivo inválido") || texto.contains("arquivo invalido")) {
            return BLOQUEADO_ORIGEM;
        }
        return PENDENTE_ENVIO;
    }

    public static ClassificacaoOperacionalCanhotoVedacit paraErro(String mensagem) {
        String texto = normalizar(mensagem);
        if (texto.contains("read timed out") || texto.contains("sockettimeoutexception")) {
            return TIMEOUT_AMBIGUO;
        }
        if (texto.contains("vedacit recusou") || texto.contains("digitalização") || texto.contains("digitalizacao")
                || texto.contains("canhoto compativel") || texto.contains("canhoto compatível")) {
            return BLOQUEADO_DESTINO;
        }
        if (texto.contains("formato de imagem") || texto.contains("chave") || texto.contains("arquivo inválido")
                || texto.contains("arquivo invalido")) {
            return BLOQUEADO_ORIGEM;
        }
        return PENDENTE_TECNICO;
    }

    private static String normalizar(String mensagem) {
        return mensagem == null ? "" : mensagem.toLowerCase(Locale.ROOT);
    }
}
