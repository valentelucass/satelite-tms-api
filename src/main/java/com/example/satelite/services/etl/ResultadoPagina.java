package com.example.satelite.services.etl;

record ResultadoPagina(
        int recebidos,
        int enviados,
        int ignorados,
        int pendentesFoto,
        int jaProcessados,
        int erros,
        int falhasInfraestruturaConsecutivas,
        boolean interromperCiclo,
        boolean fimJanelaRetroativa,
        boolean circuitoAberto, int retidos, int pendentesOrigem
) {
    ResultadoPagina(int recebidos, int enviados, int ignorados, int pendentesFoto, int jaProcessados,
            int erros, int falhasInfraestruturaConsecutivas, boolean interromperCiclo,
            boolean fimJanelaRetroativa, boolean circuitoAberto) {
        this(recebidos, enviados, ignorados, pendentesFoto, jaProcessados, erros,
                falhasInfraestruturaConsecutivas, interromperCiclo, fimJanelaRetroativa, circuitoAberto, 0, 0);
    }
    static ResultadoPagina vazio() {
        return vazio(0);
    }

    static ResultadoPagina vazio(int falhasInfraestruturaConsecutivas) {
        return new ResultadoPagina(0, 0, 0, 0, 0, 0, falhasInfraestruturaConsecutivas, false, false, false);
    }

    static ResultadoPagina falhaInfraestruturaPagina(int falhasInfraestruturaConsecutivas) {
        return new ResultadoPagina(
                0,
                0,
                0,
                0,
                0,
                1,
                falhasInfraestruturaConsecutivas + 1,
                false,
                false,
                false
        );
    }

    ResultadoPagina com(ResultadoRegistro registro) {
        return new ResultadoPagina(
                recebidos + 1,
                enviados + (registro.enviado() ? 1 : 0),
                ignorados + (registro.ignorado() ? 1 : 0),
                pendentesFoto + (registro.pendenteFoto() ? 1 : 0),
                jaProcessados + (registro.jaProcessado() ? 1 : 0),
                erros + (registro.erro() ? 1 : 0),
                registro.falhaInfraestrutura() ? falhasInfraestruturaConsecutivas + 1 : 0,
                interromperCiclo,
                fimJanelaRetroativa,
                circuitoAberto, retidos + (registro == ResultadoRegistro.RETIDO ? 1 : 0),
                pendentesOrigem + (registro == ResultadoRegistro.PENDENTE_ORIGEM ? 1 : 0)
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
                falhasInfraestruturaConsecutivas,
                true,
                fimJanelaRetroativa,
                circuitoAberto, retidos, pendentesOrigem
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
                falhasInfraestruturaConsecutivas,
                interromperCiclo,
                true,
                circuitoAberto, retidos, pendentesOrigem
        );
    }

    ResultadoPagina comCircuitoAberto() {
        return new ResultadoPagina(
                recebidos,
                enviados,
                ignorados,
                pendentesFoto,
                jaProcessados,
                erros,
                falhasInfraestruturaConsecutivas,
                interromperCiclo,
                fimJanelaRetroativa,
                true, retidos, pendentesOrigem
        );
    }
}
