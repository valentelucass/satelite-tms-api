package com.example.satelite.clients;

import org.springframework.http.MediaType;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.satelite.dto.ppg.PpgLoginRequestDTO;
import com.example.satelite.dto.ppg.PpgLoginResponseDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaRequestDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaResponseDTO;
import com.example.satelite.config.PpgFeignConfig;

@FeignClient(name = "ppgClient", url = "${PPG_API_BASE_URL}", configuration = PpgFeignConfig.class)
public interface PpgClient {

    @PostMapping(
        value = "/assets/ws/ws.0.loginapp.php",
        consumes = MediaType.APPLICATION_JSON_VALUE
    )
    PpgLoginResponseDTO login(@RequestBody PpgLoginRequestDTO credentials);

    @PostMapping(
        value = "/assets/ws/ws.0.ocorrenciaentregacache_api.php",
        consumes = MediaType.APPLICATION_JSON_VALUE
    )
    PpgOcorrenciaResponseDTO enviarOcorrencia(
        @RequestParam("access_token") String accessToken,
        @RequestBody PpgOcorrenciaRequestDTO ocorrenciaRequest
    );

}
