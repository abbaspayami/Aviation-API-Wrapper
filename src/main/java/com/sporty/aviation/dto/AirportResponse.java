package com.sporty.aviation.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/**
 * Clean, minimised airport response returned to API callers.
 *
 * <p>This is the <strong>only</strong> DTO that ever leaves the service boundary.
 * The raw upstream DTOs ({@link AviationWeatherAirportDto}, {@link AirportDBDto})
 * stay internal — they are implementation details that callers should never depend on.
 *
 * <p>Mapping from the internal representation is done via the single factory method
 * {@link #from(AviationWeatherAirportDto)}, keeping all field-name translations in one place.
 *
 * <p>Example JSON response:
 * <pre>{@code
 * {
 *   "icao":        "EGLL",
 *   "iata":        "LHR",
 *   "name":        "LONDON HEATHROW",
 *   "city":        "London",
 *   "country":     "GB",
 *   "latitude":    51.4775,
 *   "longitude":   -0.461389,
 *   "timezone":    null,
 *   "elevationFt": 83,
 *   "type":        "large_airport"
 * }
 * }</pre>
 */
@Schema(description = "Key airport information. Contains all fields a consumer needs — "
                    + "raw upstream codes are translated into clean, self-describing names.")
@Value
@Builder
@JsonDeserialize(builder = AirportResponse.AirportResponseBuilder.class)
public class AirportResponse {

    // ── Identifiers ──────────────────────────────────────────────────────────

    @Schema(
        description = "4-character ICAO airport identifier.",
        example     = "EGLL"
    )
    String icao;

    @Schema(
        description = "3-character IATA airport code used by airlines and passengers. "
                    + "May be null for military or private airfields.",
        example     = "LHR",
        nullable    = true
    )
    String iata;

    // ── Name & Location ───────────────────────────────────────────────────────

    @Schema(
        description = "Full official airport name.",
        example     = "LONDON HEATHROW"
    )
    String name;

    @Schema(
        description = "Nearest city or municipality. "
                    + "Populated by the fallback provider (AirportDB); "
                    + "may be null when served from the primary provider (Aviation Weather / NOAA).",
        example     = "London",
        nullable    = true
    )
    String city;

    @Schema(
        description = "ISO 3166-1 alpha-2 country code.",
        example     = "GB"
    )
    String country;

    // ── Coordinates ───────────────────────────────────────────────────────────

    @Schema(
        description = "Latitude in decimal degrees (WGS-84 datum). "
                    + "Positive = North, negative = South.",
        example     = "51.4775"
    )
    Double latitude;

    @Schema(
        description = "Longitude in decimal degrees (WGS-84 datum). "
                    + "Positive = East, negative = West.",
        example     = "-0.461389"
    )
    Double longitude;

    // ── Optional enrichment ───────────────────────────────────────────────────

    @Schema(
        description = "IANA timezone identifier (e.g. 'Europe/London'). "
                    + "Not provided by current upstream sources — always null. "
                    + "Reserved for future timezone enrichment.",
        example     = "Europe/London",
        nullable    = true
    )
    String timezone;

    @Schema(
        description = "Elevation above mean sea level, in feet.",
        example     = "83",
        nullable    = true
    )
    Integer elevationFt;

    @Schema(
        description = "Airport size or type. "
                    + "Fallback provider uses descriptive strings: "
                    + "'large_airport', 'medium_airport', 'small_airport', 'heliport', 'seaplane_base'. "
                    + "Primary provider may return a facility-type code (e.g. 'ARP').",
        example     = "large_airport",
        nullable    = true
    )
    String type;

    // ── Factory method ────────────────────────────────────────────────────────

    /**
     * Creates an {@code AirportResponse} from the internal raw DTO.
     *
     * <p>This is the <strong>single mapping point</strong> for the entire service.
     * Any upstream field renames or type changes are isolated here — no other
     * class needs to know the internal DTO structure.
     *
     * @param dto the raw airport data (from primary API or Provider 2 fallback)
     * @return a clean, documented response safe to send to callers
     */
    /**
     * Tells Jackson to use the Lombok-generated builder for deserialization from Redis JSON.
     * Without this, Jackson cannot construct the immutable {@code @Value} class.
     * {@code withPrefix = ""} matches Lombok's builder setter names (e.g. {@code .icao()} not {@code .withIcao()}).
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class AirportResponseBuilder {}

    public static AirportResponse from(AviationWeatherAirportDto dto) {
        return AirportResponse.builder()
                .icao(dto.getIcaoId())
                .iata(dto.getIataId())
                .name(dto.getName())
                .city(dto.getMunicipality())
                .country(dto.getCountry())
                .latitude(dto.getLat())
                .longitude(dto.getLon())
                .timezone(null)              // not available from current upstream sources
                .elevationFt(dto.getElev())
                .type(dto.getType())
                .build();
    }
}
