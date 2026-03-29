package com.sporty.aviation.exception;

public class AirportNotFoundException extends RuntimeException {

    public AirportNotFoundException(String icaoCode) {
        super("No airport found for ICAO code: " + icaoCode);
    }
}
