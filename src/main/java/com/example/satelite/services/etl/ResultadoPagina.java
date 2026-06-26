package com.example.satelite.services.etl;

record ResultadoPagina(
        int recebidos,
        int enviados,
        int ignorados,
        int pendentesFoto,
        int jaProcessados,
        int erros,
        boolean interromperCiclo,
        boolean fimJanelaRetroativa
) {
    static ResultadoPagina vazio() {
        return new ResultadoPagina(0, 0, 0, 0, 0, 0, false, false);
    }

    ResultadoPagina com(ResultadoRegistro registro) {
        return new ResultadoPagina(
                recebidos + 1,
                enviados + (registro == ResultadoRegistro.ENVIADO ? 1 : 0),
                ignorados + (registro == ResultadoRegistro.IGNORADO ? 1 : 0),
                pendentesFoto + (registro == ResultadoRegistro.PENDENTE_FOTO ? 1 : 0),
                jaProcessados + (registro == ResultadoRegistro.JA_PROCESSADO ? 1 : 0),
                erros + (registro == ResultadoRegistro.ERRO ? 1 : 0),
                interromperCiclo,
                fimJanelaRetroativa
        );
    }

    ResultadoPagina comInterrupcaoDeCiclo() {
        return new ResultadoPagina(
                recebidos,
                enviados,
                ignorados,
                pendentesFoto,
                jaProcessados,
                erros,
                true,
                fimJanelaRetroativa
        );
    }

    ResultadoPagina comFimJanelaRetroativa() {
        return new ResultadoPagina(
                recebidos,
                enviados,
                ignorados,
                pendentesFoto,
                jaProcessados,
                erros,
                interromperCiclo,
                true
        );
    }
}
