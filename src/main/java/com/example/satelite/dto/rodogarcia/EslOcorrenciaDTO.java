package com.example.satelite.dto.rodogarcia;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EslOcorrenciaDTO(
        Long id,
        @JsonProperty("order_number")
        String orderNumber,
        @JsonProperty("volume_number")
        String volumeNumber,
        @JsonProperty("occurrence_at")
        OffsetDateTime occurrenceAt,
        @JsonProperty("created_at")
        OffsetDateTime createdAt,
        EslInvoiceDTO invoice,
        EslFreightDTO freight,
        EslOccurrenceDefDTO occurrence) {

    public EslOcorrenciaDTO(
            Long id,
            OffsetDateTime occurrenceAt,
            OffsetDateTime createdAt,
            EslInvoiceDTO invoice,
            EslFreightDTO freight,
            EslOccurrenceDefDTO occurrence
    ) {
        this(id, null, null, occurrenceAt, createdAt, invoice, freight, occurrence);
    }

    public EslOcorrenciaDTO(
            Long id,
            String orderNumber,
            OffsetDateTime occurrenceAt,
            OffsetDateTime createdAt,
            EslInvoiceDTO invoice,
            EslFreightDTO freight,
            EslOccurrenceDefDTO occurrence
    ) {
        this(id, orderNumber, null, occurrenceAt, createdAt, invoice, freight, occurrence);
    }

    public EslOcorrenciaDTO(
            Long id,
            OffsetDateTime occurrenceAt,
            EslInvoiceDTO invoice,
            EslFreightDTO freight,
            EslOccurrenceDefDTO occurrence
    ) {
        this(id, null, null, occurrenceAt, null, invoice, freight, occurrence);
    }
}
