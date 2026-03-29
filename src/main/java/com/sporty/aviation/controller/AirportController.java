package com.sporty.aviation.controller;

import com.sporty.aviation.dto.AirportResponse;
import com.sporty.aviation.dto.ErrorResponse;
import com.sporty.aviation.service.AirportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
    name        = "Airports",
    description = "Look up airport details by ICAO code. "
                + "Returns clean, documented airport information. "
                + "Data comes from Aviation Weather (NOAA) or, if unavailable, from AirportDB (Provider 2)."
)
@RestController
@RequestMapping("/api/v1/airports")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "ApiKeyAuth")
public class AirportController {

    private final AirportService airportService;

    @Operation(
        summary     = "Get airport by ICAO code",
        description = """
            Returns key details for a single airport identified by its 4-character ICAO code.

            **ICAO code rules:**
            - Exactly 4 alphanumeric characters (letters A-Z, digits 0-9)
            - Case-insensitive (`EGLL` and `egll` are treated the same)

            **Examples:** `EGLL` (London Heathrow), `KJFK` (JFK New York), `OMDB` (Dubai)

            **Caching:** results are cached in Redis for 10 minutes.
            A cache hit returns data in < 1 ms with no call to the upstream API.

            **Resilience:** if the primary API is down the service automatically
            falls back to Provider 2 (AirportDB). The fallback result is also cached.
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description  = "Airport found — returns clean, documented airport information.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = AirportResponse.class),
                examples  = @ExampleObject(
                    name  = "London Heathrow",
                    value = """
                        {
                          "icao":        "EGLL",
                          "iata":        "LHR",
                          "name":        "LONDON HEATHROW",
                          "city":        "London",
                          "country":     "GB",
                          "latitude":    51.4775,
                          "longitude":   -0.461389,
                          "timezone":    null,
                          "elevationFt": 83,
                          "type":        "large_airport"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description  = "Invalid ICAO code — must be exactly 4 alphanumeric characters.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ErrorResponse.class),
                examples  = @ExampleObject(value = """
                    {
                      "status":    400,
                      "error":     "Bad Request",
                      "message":   "ICAO code must be exactly 4 characters",
                      "path":      "/api/v1/airports/EG",
                      "timestamp": "2026-03-23T10:00:00"
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description  = "Missing or invalid `X-API-Key` header.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ErrorResponse.class),
                examples  = @ExampleObject(value = """
                    {
                      "status":    401,
                      "error":     "Unauthorized",
                      "message":   "Missing or invalid API key. Include a valid 'X-API-Key' header.",
                      "path":      "/api/v1/airports/EGLL",
                      "timestamp": "2026-03-23T10:00:00"
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description  = "No airport exists for the given ICAO code.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ErrorResponse.class),
                examples  = @ExampleObject(value = """
                    {
                      "status":    404,
                      "error":     "Not Found",
                      "message":   "No airport found for ICAO code: ZZZZ",
                      "path":      "/api/v1/airports/ZZZZ",
                      "timestamp": "2026-03-23T10:00:00"
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "429",
            description  = "Rate limit exceeded — 60 requests per minute per API key. "
                         + "Check the `Retry-After` response header for the exact wait time.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ErrorResponse.class),
                examples  = @ExampleObject(value = """
                    {
                      "status":    429,
                      "error":     "Too Many Requests",
                      "message":   "Rate limit exceeded (60 req/min). Retry after 45 seconds.",
                      "path":      "/api/v1/airports/EGLL",
                      "timestamp": "2026-03-23T10:00:00"
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "502",
            description  = "Both the primary API (Aviation Weather) and fallback provider "
                         + "(AirportDB) failed to respond successfully.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ErrorResponse.class),
                examples  = @ExampleObject(value = """
                    {
                      "status":    502,
                      "error":     "Bad Gateway",
                      "message":   "Aviation Weather API is temporarily unavailable. Please try again later.",
                      "path":      "/api/v1/airports/EGLL",
                      "timestamp": "2026-03-23T10:00:00"
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "503",
            description  = "Circuit breaker is OPEN — the upstream API has been failing repeatedly. "
                         + "Requests are rejected instantly to prevent cascade failures. "
                         + "Retry after ~30 seconds.",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ErrorResponse.class),
                examples  = @ExampleObject(value = """
                    {
                      "status":    503,
                      "error":     "Service Unavailable",
                      "message":   "Aviation API is temporarily unavailable. Please try again in a few moments.",
                      "path":      "/api/v1/airports/EGLL",
                      "timestamp": "2026-03-23T10:00:00"
                    }
                    """)
            )
        )
    })
    @GetMapping("/{icaoCode}")
    public ResponseEntity<AirportResponse> getAirportByIcao(
            @Parameter(
                description = "4-character ICAO airport identifier (letters and digits only).",
                example     = "EGLL",
                required    = true
            )
            @PathVariable
            @Size(min = 4, max = 4, message = "ICAO code must be exactly 4 characters")
            @Pattern(regexp = "^[A-Za-z0-9]{4}$",
                     message = "ICAO code must contain only alphanumeric characters")
            String icaoCode) {

        return ResponseEntity.ok(airportService.getAirportByIcao(icaoCode));
    }
}
