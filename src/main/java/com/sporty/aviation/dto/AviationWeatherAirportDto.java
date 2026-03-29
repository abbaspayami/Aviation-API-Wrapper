package com.sporty.aviation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Raw airport data returned by the Aviation Weather API (NOAA).
 *
 * <p>This is the response DTO returned by {@code GET /api/v1/airports/{icaoCode}}.
 * Field names and raw codes match the upstream API contract exactly.
 * No transformation is applied — what the upstream returns is what the caller receives.
 *
 * <p>Source: {@code https://aviationweather.gov/api/data/airport?ids={icao}&format=json}
 */
@Schema(
    description = "Airport data as returned by the Aviation Weather API. "
                + "Raw codes are preserved (e.g. owner='P' means Public, tower='T' means has tower)."
)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AviationWeatherAirportDto {

    @Schema(description = "ICAO airport identifier — 4 alphanumeric characters.", example = "EGLL")
    private String icaoId;

    @Schema(description = "IATA airport code used by airlines and passengers.", example = "LHR")
    private String iataId;

    @Schema(description = "FAA identifier (primarily for US airports).", example = "JFK")
    private String faaId;

    @Schema(description = "Full official airport name.", example = "LONDON HEATHROW")
    private String name;

    @Schema(description = "US state or region code — present only for US airports.", example = "NY")
    private String state;

    @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "GB")
    private String country;

    @Schema(description = "Data source organisation.", example = "FAA")
    private String source;

    @Schema(description = "Facility type code. 'ARP' = Airport Reference Point.", example = "ARP")
    private String type;

    @Schema(description = "Latitude in decimal degrees (WGS-84).", example = "51.4775")
    private Double lat;

    @Schema(description = "Longitude in decimal degrees (WGS-84).", example = "-0.461389")
    private Double lon;

    @Schema(description = "Elevation above mean sea level, in feet.", example = "83")
    private Integer elev;

    @Schema(description = "Magnetic declination. 'W' = west of true north.", example = "1W")
    private String magdec;

    @Schema(description = "Ownership type. 'P' = Public, 'PR' = Private.", example = "P")
    private String owner;

    @Schema(description = "Number of runways.", example = "2")
    private String rwyNum;

    @Schema(description = "Runway length category code.", example = "12000")
    private String rwyLength;

    @Schema(description = "Primary runway surface code. 'C' = Concrete, 'A' = Asphalt.", example = "A")
    private String rwyType;

    @Schema(description = "Available airport services indicator.", example = "A")
    private String services;

    @Schema(description = "Control tower indicator. 'T' = has active control tower.", example = "T")
    private String tower;

    @Schema(description = "Rotating beacon indicator. 'B' = has beacon.", example = "B")
    private String beacon;

    @Schema(description = "Annual aircraft operations count.", example = "480000")
    private String operations;

    @Schema(description = "Annual passenger count.", example = "80900000")
    private String passengers;

    @Schema(
        description = "Semicolon-separated radio frequencies. "
                    + "Each entry is 'name,frequency' (e.g. 'D-ATIS,115.4').",
        example     = "D-ATIS,115.4;LCL/P,119.1"
    )
    private String freqs;

    @Schema(description = "Data priority classification.", example = "1")
    private String priority;

    /**
     * Nearest city or municipality.
     *
     * <p>Not present in the primary Aviation Weather API response — populated only
     * when data comes from Provider 2 (AirportDB) via
     * {@link com.sporty.aviation.service.Provider2AirportService}.
     * Will be {@code null} for primary-sourced results.
     */
    @Schema(description = "Nearest city or municipality (populated by fallback provider only).", example = "London")
    private String municipality;

    @Schema(description = "Individual runway details. One entry per runway.")
    private List<RunwayDto> runways;

    // -------------------------------------------------------------------------

    @Schema(description = "Individual runway data as returned by the Aviation Weather API.")
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RunwayDto {

        @Schema(description = "Runway designation.", example = "09L/27R")
        private String id;

        @Schema(
            description = "Runway dimensions — 'lengthxwidth' in feet.",
            example     = "12802x164"
        )
        private String dimension;

        @Schema(
            description = "Surface material code. 'C' = Concrete, 'A' = Asphalt, "
                        + "'T' = Turf, 'G' = Gravel.",
            example     = "A"
        )
        private String surface;

        @Schema(description = "Magnetic heading alignment in degrees.", example = "90")
        private Integer alignment;
    }
}
