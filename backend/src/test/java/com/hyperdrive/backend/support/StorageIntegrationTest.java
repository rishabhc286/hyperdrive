package com.hyperdrive.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Integration-test base for storage connections: signed-in, onboarded users and drivers. */
public abstract class StorageIntegrationTest extends AuthIntegrationTest {

    @Autowired
    protected CredentialCipher cipher;

    protected DesktopLoginDriver login;
    protected StorageConnectionDriver storage;

    @BeforeEach
    void setUpDrivers() {
        login = new DesktopLoginDriver(mvc, google);
        storage = new StorageConnectionDriver(mvc, google);
    }

    /** Signs in with a Google sign-in subject and completes onboarding; returns the access token. */
    protected String onboardedUser(String signInSubject, String username) throws Exception {
        String token = login.signIn(signInSubject, signInSubject + "@login.example").accessToken();
        MvcResult result = mvc.perform(post("/api/v1/me/onboarding").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return token;
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return DesktopLoginDriver.JSON.readTree(result.getResponse().getContentAsString());
    }

    protected String storedRefreshToken(UUID accountId) {
        return jdbc.sql("""
                SELECT encrypted_refresh_token, encryption_key_version FROM provider_credentials
                WHERE connected_account_id = :id
                """)
                .param("id", accountId)
                .query((rs, n) -> {
                    byte[] value = rs.getBytes(1);
                    return value == null ? null
                            : cipher.decrypt(value, rs.getInt(2), "provider_credentials:" + accountId + ":refresh");
                })
                .single();
    }

    protected String accountStatus(UUID accountId) {
        return jdbc.sql("SELECT status FROM connected_accounts WHERE id = :id").param("id", accountId)
                .query(String.class).single();
    }
}
