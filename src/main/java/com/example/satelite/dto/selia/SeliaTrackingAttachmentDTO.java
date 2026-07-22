package com.example.satelite.dto.selia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaTrackingAttachmentDTO(
        String url,
        String type,
        @JsonProperty("file_name")
        String fileName,
        @JsonProperty("mime_type")
        String mimeType
) {
}
