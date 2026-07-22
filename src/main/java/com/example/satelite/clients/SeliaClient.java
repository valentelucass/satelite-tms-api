package com.example.satelite.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.example.satelite.config.SeliaFeignConfig;
import com.example.satelite.dto.selia.SeliaAddEventsRequestDTO;

@FeignClient(
        name = "seliaClient",
        url = "${SELIA_INTELIPOST_API_BASE_URL}",
        configuration = SeliaFeignConfig.class
)
public interface SeliaClient {

    @PostMapping(
            value = "/tracking/add/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    void adicionarEventos(
            @RequestHeader("api-key") String apiKey,
            @RequestHeader("logistic-provider-api-key") String logisticProviderApiKey,
            @RequestHeader("platform") String platform,
            @RequestHeader("platform-version") String platformVersion,
            @RequestHeader("plugin") String plugin,
            @RequestHeader("plugin-version") String pluginVersion,
            @RequestBody SeliaAddEventsRequestDTO request
    );
}
