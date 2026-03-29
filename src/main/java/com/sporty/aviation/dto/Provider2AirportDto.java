package com.sporty.aviation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Maps the raw JSON response from Provider 2 — AirportDB.io.
 *
 * <p>Example API call:
 * <pre>
 *   GET https://airportdb.io/api/v1/airport/EGLL?apiToken={token}
 * </pre>
 *
 * <p>AirportDB uses snake_case field names (e.g. {@code latitude_deg}),
 * which we map to Java camelCase via {@link JsonProperty}.
 * Unknown fields are silently ignored so future API additions don't break the app.
 *
 * <p>This DTO is an <strong>internal</strong> representation only — it is never
 * serialised to the caller. The public response is always {@link AirportResponse}.
 */
@Schema(
    description = "Raw airport data returned by Provider 2 (AirportDB.io). "
                + "Internal DTO only — never exposed to API callers directly."
)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Provider2AirportDto {

    @Schema(description = "ICAO airport identifier.", example = "EGLL")
    private String icao;

    @Schema(description = "IATA airport code used by airlines and passengers.", example = "LHR")
    private String iata;

    @Schema(description = "Full official airport name.", example = "Heathrow Airport")
    private String name;

    @Schema(
        description = "Airport size/type as classified by AirportDB. "
                    + "Possible values: 'large_airport', 'medium_airport', 'small_airport', "
                    + "'heliport', 'seaplane_base', 'balloonport'.",
        example     = "large_airport"
    )
    private String type;

    @Schema(description = "Latitude in decimal degrees as a string (WGS-84).", example = "51.4775")
    @JsonProperty("latitude_deg")
    private String latitudeDeg;

    @Schema(description = "Longitude in decimal degrees as a string (WGS-84).", example = "-0.461389")
    @JsonProperty("longitude_deg")
    private String longitudeDeg;

    @Schema(description = "Elevation above mean sea level in feet.", example = "83")
    @JsonProperty("elevation_ft")
    private Integer elevationFt;

    @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "GB")
    @JsonProperty("iso_country")
    private String isoCountry;

    @Schema(description = "ISO 3166-2 region code.", example = "GB-ENG")
    @JsonProperty("iso_region")
    private String isoRegion;

    @Schema(description = "Nearest city or municipality.", example = "London")
    private String municipality;
}
