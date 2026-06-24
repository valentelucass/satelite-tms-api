package com.example.satelite.dto.rodogarcia;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EslOcorrenciaDTO(
        Long id,
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
            EslInvoiceDTO invoice,
            EslFreightDTO freight,
            EslOccurrenceDefDTO occurrence
    ) {
        this(id, occurrenceAt, null, invoice, freight, occurrence);
    }
}
