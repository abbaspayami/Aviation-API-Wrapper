package com.sporty.aviation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Uniform error body returned on all non-2xx responses.")
@Data
@Builder
public class ErrorResponse {

    @Schema(description = "HTTP status code.", example = "404")
    private int status;

    @Schema(description = "Short HTTP status reason phrase.", example = "Not Found")
    private String error;

    @Schema(description = "Human-readable description of what went wrong.", example = "No airport found for ICAO code: ZZZZ")
    private String message;

    @Schema(description = "Request path that triggered the error.", example = "/api/v1/airports/ZZZZ")
    private String path;

    @Schema(description = "Server-side timestamp when the error occurred.", example = "2026-03-23T10:00:00")
    private LocalDateTime timestamp;
}
