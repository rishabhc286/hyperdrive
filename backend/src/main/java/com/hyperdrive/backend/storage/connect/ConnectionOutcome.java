package com.hyperdrive.backend.storage.connect;

/** Result of a storage-connection callback, shared by every provider. */
public sealed interface ConnectionOutcome {

    record Connected(boolean reconnected) implements ConnectionOutcome {
    }

    record Failed(Failure failure) implements ConnectionOutcome {
    }

    /** Machine-readable failure codes reported to the desktop through the transaction status. */
    enum Failure {
        ACCESS_DENIED("access_denied"),
        AUTHORIZATION_FAILED("authorization_failed"),
        TOKEN_EXCHANGE_FAILED("token_exchange_failed"),
        /** Google: the Drive permission was unticked. */
        DRIVE_SCOPE_NOT_GRANTED("drive_scope_not_granted"),
        /** Dropbox: the granted scopes do not include what HyperDrive requested. */
        SCOPE_NOT_GRANTED("scope_not_granted"),
        IDENTITY_INVALID("identity_invalid"),
        /** Dropbox: the account's root type or namespace is not one HyperDrive supports. */
        UNSUPPORTED_ACCOUNT("unsupported_account"),
        DRIVE_UNAVAILABLE("drive_unavailable"),
        PROVIDER_UNAVAILABLE("provider_unavailable"),
        BROWSER_MISMATCH("browser_mismatch"),
        INTERNAL("internal_error");

        private final String code;

        Failure(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
