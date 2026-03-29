package com.sporty.aviation.exception;

public class AviationApiException extends RuntimeException {

    public AviationApiException(String message) {
        super(message);
    }

    public AviationApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
