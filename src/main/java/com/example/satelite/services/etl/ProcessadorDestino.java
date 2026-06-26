package com.example.satelite.services.etl;

import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ResultadoIntegracao;

@FunctionalInterface
interface ProcessadorDestino {
    ResultadoIntegracao processar(
            EslOcorrenciaDTO ocorrencia,
            ComprovanteEslDTO comprovante,
            LogIntegracaoModel logIntegracao
    );
}
