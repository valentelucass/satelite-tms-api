package com.example.satelite.clients;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.example.satelite.config.SupporteFeignConfig;
import com.example.satelite.dto.supporte.SupporteOcorrenciaRequestDTO;
import com.example.satelite.dto.supporte.SupporteOcorrenciaResponseDTO;

@FeignClient(
        name = "supporteClient",
        url = "${SUPPORTE_API_BASE_URL}",
        configuration = SupporteFeignConfig.class
)
public interface SupporteClient {

    @PostMapping(
            value = "${SUPPORTE_OCCURRENCES_PATH:/V1/tracking/integracao/transp/ocorrencias}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    List<SupporteOcorrenciaResponseDTO> enviarOcorrencia(
            @RequestHeader("Authorization") String authorization,
            @RequestBody SupporteOcorrenciaRequestDTO request
    );
}
