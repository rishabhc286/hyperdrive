package com.hyperdrive.backend.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cloud-storage connection settings ({@code hyperdrive.storage.*}). Only used in auth mode.
 *
 * @param credentialKey          base64 of a 32-byte AES-256 key that encrypts provider tokens
 *                               ({@code HYPERDRIVE_CREDENTIAL_KEY}); required, never defaulted
 * @param credentialKeyVersion   version recorded with every value encrypted by {@code credentialKey}
 * @param previousCredentialKeys older keys still accepted for decryption, as
 *                               {@code version:base64,version:base64} (key rotation); may be empty
 * @param transactionTtl         how long a storage connection may take end to end
 * @param ticketTtl              how long the one-time browser-start ticket stays usable
 * @param driveApiBaseUrl        Google Drive API v3 base URL (tests point this at a fake)
 * @param connectTimeout         provider HTTP connect timeout
 * @param readTimeout            provider HTTP read timeout
 * @param defaultPageSize        listing page size when the client sends none
 * @param maxPageSize            upper bound for a listing page (larger requests are clamped)
 * @param maxRetries             retries for retryable provider failures (rate limits, 5xx, I/O)
 * @param retryBaseDelay         first backoff delay; doubles per retry, plus jitter
 * @param maxRetryDelay          longest single wait; a longer Retry-After is returned to the client
 */
@ConfigurationProperties("hyperdrive.storage")
public record StorageProperties(
        String credentialKey,
        @DefaultValue("1") int credentialKeyVersion,
        @DefaultValue("") String previousCredentialKeys,
        @DefaultValue("10m") Duration transactionTtl,
        @DefaultValue("2m") Duration ticketTtl,
        @DefaultValue("https://www.googleapis.com/drive/v3") URI driveApiBaseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("100") int defaultPageSize,
        @DefaultValue("200") int maxPageSize,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("250ms") Duration retryBaseDelay,
        @DefaultValue("4s") Duration maxRetryDelay) {
}
