package com.example.satelite.services.etl;

enum EtapaVedacit {
    ENTREGA(1, true),
    EMISSAO_XML(110, false);

    private final int codigoOcorrencia;
    private final boolean consultaComprovante;

    EtapaVedacit(int codigoOcorrencia, boolean consultaComprovante) {
        this.codigoOcorrencia = codigoOcorrencia;
        this.consultaComprovante = consultaComprovante;
    }

    int codigoOcorrencia() {
        return codigoOcorrencia;
    }

    boolean consultaComprovante() {
        return consultaComprovante;
    }
}
