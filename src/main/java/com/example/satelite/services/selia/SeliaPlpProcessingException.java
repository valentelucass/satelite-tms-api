package com.example.satelite.services.selia;

import org.springframework.http.HttpStatus;

public class SeliaPlpProcessingException extends RuntimeException {

    private final HttpStatus status;

    public SeliaPlpProcessingException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
