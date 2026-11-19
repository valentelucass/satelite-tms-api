package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaTrackingEventDTO(
        @JsonProperty("event_date")
        String eventDate,
        @JsonProperty("original_code")
        String originalCode,
        @JsonProperty("original_message")
        String originalMessage,
        List<SeliaTrackingAttachmentDTO> attachments
) {
}
