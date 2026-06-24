package com.example.satelite.services;

public record ResultadoIntegracao(
        String status,
        String statusDados,
        String statusCanhoto,
        String mensagemErroDados,
        String mensagemErroCanhoto
) {
    public static final String STATUS_RECEBIDO = "RECEBIDO";
    public static final String STATUS_IGNORADO = "IGNORADO";
    public static final String STATUS_ENVIADO = "ENVIADO";
    public static final String STATUS_PARCIAL = "PARCIAL";
    public static final String STATUS_PENDENTE_FOTO = "PENDENTE_FOTO";
    public static final String STATUS_ERRO_DESTINO = "ERRO_DESTINO";
    public static final String STATUS_SUCESSO = "SUCESSO";
    public static final String STATUS_NAO_APLICAVEL = "NAO_APLICAVEL";

    public static ResultadoIntegracao enviado() {
        return new ResultadoIntegracao(STATUS_ENVIADO, STATUS_SUCESSO, STATUS_SUCESSO, null, null);
    }

    public static ResultadoIntegracao ignorado() {
        return new ResultadoIntegracao(STATUS_IGNORADO, STATUS_IGNORADO, STATUS_IGNORADO, null, null);
    }

    public static ResultadoIntegracao pendenteFotoPpg(String mensagem) {
        return new ResultadoIntegracao(STATUS_PENDENTE_FOTO, STATUS_PENDENTE_FOTO, STATUS_PENDENTE_FOTO, null, mensagem);
    }

    public static ResultadoIntegracao parcialCanhotoPendente(String statusDados, String mensagem) {
        return new ResultadoIntegracao(STATUS_PARCIAL, statusDados, STATUS_PENDENTE_FOTO, null, mensagem);
    }

    public static ResultadoIntegracao vedacitConcluido(String statusDados, String statusCanhoto) {
        return new ResultadoIntegracao(STATUS_ENVIADO, statusDados, statusCanhoto, null, null);
    }

    public static ResultadoIntegracao erroDados(String mensagem) {
        return new ResultadoIntegracao(STATUS_ERRO_DESTINO, STATUS_ERRO_DESTINO, null, mensagem, null);
    }

    public static ResultadoIntegracao erroCanhoto(String statusDados, String mensagem) {
        return new ResultadoIntegracao(STATUS_ERRO_DESTINO, statusDados, STATUS_ERRO_DESTINO, null, mensagem);
    }

    public boolean enviadoComSucesso() {
        return STATUS_ENVIADO.equals(status);
    }

    public boolean foiIgnorado() {
        return STATUS_IGNORADO.equals(status);
    }

    public boolean pendenteFoto() {
        return STATUS_PENDENTE_FOTO.equals(statusCanhoto);
    }

    public boolean erro() {
        return STATUS_ERRO_DESTINO.equals(status);
    }
}
