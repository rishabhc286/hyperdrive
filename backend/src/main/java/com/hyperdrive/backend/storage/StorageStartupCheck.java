package com.hyperdrive.backend.storage;

import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth;
import com.hyperdrive.backend.storage.connect.StorageBrowserController;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Fails startup when Google Drive connections are misconfigured: missing client, wrong callback,
 * wrong or broader-than-needed scopes, or unsafe limits. (A missing credential key already fails
 * when the {@code CredentialCipher} bean is created.)
 */
@Component
@ConditionalOnAuthEnabled
public class StorageStartupCheck implements InitializingBean {

    /** Exactly what storage consent asks for: identity of the Drive account, and Drive read-only. */
    static final Set<String> EXPECTED_SCOPES = Set.of("openid", "email", "profile", GoogleStorageOAuth.DRIVE_READONLY);

    private final AuthProperties auth;
    private final StorageProperties storage;
    private final ClientRegistrationRepository registrations;

    public StorageStartupCheck(AuthProperties auth, StorageProperties storage, ClientRegistrationRepository registrations) {
        this.auth = auth;
        this.storage = storage;
        this.registrations = registrations;
    }

    @Override
    public void afterPropertiesSet() {
        validate(auth, storage, registrations.findByRegistrationId(StorageProvider.GOOGLE_DRIVE.registrationId()));
    }

    static void validate(AuthProperties auth, StorageProperties storage, ClientRegistration drive) {
        if (drive == null || missing(drive.getClientId()) || missing(drive.getClientSecret())) {
            throw new IllegalStateException("Google Drive connections are not configured: set GOOGLE_CLIENT_ID and "
                    + "GOOGLE_CLIENT_SECRET (or GOOGLE_DRIVE_CLIENT_ID and GOOGLE_DRIVE_CLIENT_SECRET)");
        }
        String expectedRedirect = auth.publicBaseUrl().toString().replaceAll("/$", "")
                + StorageBrowserController.callbackPath(StorageProvider.GOOGLE_DRIVE);
        if (!expectedRedirect.equals(drive.getRedirectUri())) {
            throw new IllegalStateException("Google Drive redirect URI must be " + expectedRedirect);
        }
        if (!EXPECTED_SCOPES.equals(drive.getScopes())) {
            throw new IllegalStateException("Google Drive connections must request exactly " + EXPECTED_SCOPES);
        }
        if (storage.dropbox().partiallyConfigured()) {
            throw new IllegalStateException("Dropbox is partially configured: set both DROPBOX_APP_KEY and "
                    + "DROPBOX_APP_SECRET, or neither (Dropbox connections are then disabled)");
        }
        if (storage.maxPageSize() < 1 || storage.maxPageSize() > 1000 || storage.defaultPageSize() < 1
                || storage.defaultPageSize() > storage.maxPageSize()) {
            throw new IllegalStateException("hyperdrive.storage page sizes must satisfy 1 <= default <= max <= 1000");
        }
        if (storage.maxRetries() < 0 || storage.maxRetries() > 5) {
            throw new IllegalStateException("hyperdrive.storage.max-retries must be between 0 and 5");
        }
        if (storage.ticketTtl().isNegative() || storage.ticketTtl().isZero()
                || storage.transactionTtl().compareTo(storage.ticketTtl()) < 0) {
            throw new IllegalStateException("hyperdrive.storage ticket-ttl must be positive and <= transaction-ttl");
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }
}
