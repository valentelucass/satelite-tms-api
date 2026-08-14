package com.example.satelite.services.etl;

public interface EslRequestTelemetryRecorder {

    void registrar(EslRequestContext contexto, Integer statusHttp, int tentativa, boolean retry, long duracaoMs);
}
