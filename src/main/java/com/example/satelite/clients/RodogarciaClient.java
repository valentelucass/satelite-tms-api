package com.example.satelite.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;

import com.example.satelite.config.RodogarciaFeignConfig;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.CteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;

@FeignClient(name = "rodogarciaClient", url = "${RODOGARCIA_API_BASE_URL}", configuration = RodogarciaFeignConfig.class)
public interface RodogarciaClient {

    @GetMapping("${RODOGARCIA_CUSTOMER_OCCURRENCES_PATH:/api/customer/invoice_occurrences}")
    EslLoteResponseDTO buscarOcorrencias(
        @RequestHeader("Authorization") String bearerToken,
        @RequestParam(value = "start", required = false) Long start,
        @RequestParam(value = "invoice_key", required = false) String invoiceKey,
        @RequestParam(value = "since", required = false) String since,
        @RequestParam(value = "occurrence_code", required = false, defaultValue = "1") Integer occurrenceCode
    );

    @GetMapping("/api/customer/freight_delivery_receipts")
    ComprovanteEslDTO buscarComprovante(
        @RequestHeader("Authorization") String bearerToken,
        @RequestParam("cte_key") String cteKey
    );

    @GetMapping("${RODOGARCIA_CTE_XML_PATH:/api/ctes}")
    CteResponseDTO buscarXmlCte(
        @RequestHeader("Authorization") String bearerToken,
        @RequestParam("key") String cteKey
    );
}
