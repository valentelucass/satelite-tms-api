package com.example.satelite.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.satelite.dto.selia.SeliaPreShipmentListRequestDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentListResponseDTO;
import com.example.satelite.services.selia.SeliaPlpProcessingException;
import com.example.satelite.services.selia.SeliaPreShipmentListService;

@RestController
@RequestMapping("/api/selia/intelipost")
public class SeliaPreShipmentListController {

    private final SeliaPreShipmentListService seliaPreShipmentListService;

    public SeliaPreShipmentListController(SeliaPreShipmentListService seliaPreShipmentListService) {
        this.seliaPreShipmentListService = seliaPreShipmentListService;
    }

    @PostMapping("/pre-shipment-list")
    public ResponseEntity<SeliaPreShipmentListResponseDTO> receber(
            @RequestHeader(value = "logistic-provider-api-key", required = false) String logisticProviderApiKey,
            @RequestBody(required = false) SeliaPreShipmentListRequestDTO requisicao
    ) {
        try {
            return ResponseEntity.ok(seliaPreShipmentListService.receber(logisticProviderApiKey, requisicao));
        } catch (SeliaPlpProcessingException e) {
            Long lista = requisicao == null ? null : requisicao.intelipostPreShipmentList();
            return ResponseEntity.status(e.status()).body(seliaPreShipmentListService.respostaErro(lista, e.getMessage()));
        }
    }
}
