package com.sporty.aviation.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Holds all valid API keys loaded from configuration.
 *
 * <p>Keys are read from {@code security.api-keys} in application.properties.
 * In production, override via environment variable so keys never live in source code:
 * <pre>
 *   SECURITY_API_KEYS=key-abc123,key-xyz789
 * </pre>
 *
 * <p>Uses a {@link Set} so key lookup is O(1) regardless of how many keys are configured.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security")
public class ApiKeyProperties {

    /**
     * Set of valid API keys allowed to call this service.
     * Comma-separated in properties: security.api-keys=key1,key2,key3
     */
    private Set<String> apiKeys = new HashSet<>();
}
