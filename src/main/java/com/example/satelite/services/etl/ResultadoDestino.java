package com.example.satelite.services.etl;

record ResultadoDestino(
        String destino,
        int paginasProcessadas,
        int recebidos,
        int enviados,
        int ignorados,
        int pendentesFoto,
        int jaProcessados,
        int erros,
        boolean erroCritico,
        String mensagemEncerramento
) {
    static ResultadoDestino vazio(String destino) {
        return new ResultadoDestino(destino, 0, 0, 0, 0, 0, 0, 0, false, "Sem paginas processadas");
    }

    static ResultadoDestino desabilitado(String destino) {
        return new ResultadoDestino(destino, 0, 0, 0, 0, 0, 0, 0, false, "Destino desabilitado por feature toggle");
    }

    static ResultadoDestino naoSelecionado(String destino) {
        return new ResultadoDestino(destino, 0, 0, 0, 0, 0, 0, 0, false, "Destino nao selecionado");
    }

    ResultadoDestino comPagina(ResultadoPagina pagina) {
        return new ResultadoDestino(
                destino,
                paginasProcessadas + 1,
                recebidos + pagina.recebidos(),
                enviados + pagina.enviados(),
                ignorados + pagina.ignorados(),
                pendentesFoto + pagina.pendentesFoto(),
                jaProcessados + pagina.jaProcessados(),
                erros + pagina.erros(),
                erroCritico,
                mensagemEncerramento
        );
    }

    ResultadoDestino comRegistros(ResultadoPagina pagina) {
        return new ResultadoDestino(
                destino,
                paginasProcessadas,
                recebidos + pagina.recebidos(),
                enviados + pagina.enviados(),
                ignorados + pagina.ignorados(),
                pendentesFoto + pagina.pendentesFoto(),
                jaProcessados + pagina.jaProcessados(),
                erros + pagina.erros(),
                erroCritico,
                mensagemEncerramento
        );
    }

    ResultadoDestino encerrar(String mensagemEncerramento) {
        return new ResultadoDestino(
                destino,
                paginasProcessadas,
                recebidos,
                enviados,
                ignorados,
                pendentesFoto,
                jaProcessados,
                erros,
                erroCritico,
                mensagemEncerramento
        );
    }

    ResultadoDestino comErroCritico(Exception e) {
        String mensagemErro = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new ResultadoDestino(
                destino,
                paginasProcessadas,
                recebidos,
                enviados,
                ignorados,
                pendentesFoto,
                jaProcessados,
                erros + 1,
                true,
                "Erro critico: " + mensagemErro
        );
    }

    ResultadoDestino comErroCritico(String mensagemErro) {
        return new ResultadoDestino(
                destino,
                paginasProcessadas,
                recebidos,
                enviados,
                ignorados,
                pendentesFoto,
                jaProcessados,
                erros + 1,
                true,
                mensagemErro
        );
    }
}
