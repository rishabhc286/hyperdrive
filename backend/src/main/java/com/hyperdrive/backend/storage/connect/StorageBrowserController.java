package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.auth.SecureTokens;
import com.hyperdrive.backend.auth.desktop.BrowserPages;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.connect.GoogleDriveConnectionCompleter.Failure;
import com.hyperdrive.backend.storage.connect.GoogleDriveConnectionCompleter.Outcome;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * System-browser leg of a Google Drive connection. Separate from sign-in: its own paths, its own
 * client registration and callback, no HTTP session, and it never creates or changes a HyperDrive
 * login. The browser only ever sees a one-time ticket, Google's parameters and fixed pages.
 */
@Controller
@ConditionalOnAuthEnabled
public class StorageBrowserController {

    public static final String CALLBACK_PATH = "/api/v1/storage-connections/google/callback";
    static final String BINDING_COOKIE = "HD_STORAGE_BINDING";
    private static final String COOKIE_PATH = "/api/v1/storage-connections/google";

    private final StorageConnectionService transactions;
    private final GoogleStorageOAuth oauth;
    private final GoogleDriveConnectionCompleter completer;
    private final StorageProperties properties;
    private final boolean secureCookies;

    public StorageBrowserController(StorageConnectionService transactions, GoogleStorageOAuth oauth,
            GoogleDriveConnectionCompleter completer, StorageProperties properties,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) {
        this.transactions = transactions;
        this.oauth = oauth;
        this.completer = completer;
        this.properties = properties;
        this.secureCookies = secureCookies;
    }

    /** Opened once by the system browser; consumes the ticket and redirects to Google's consent. */
    @GetMapping(StorageConnectionController.AUTHORIZE_PATH + "/{ticket}")
    public void authorize(@PathVariable String ticket, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!SecureTokens.STORAGE_TICKET.isWellFormed(ticket)) {
            invalidLink(response);
            return;
        }
        GoogleStorageOAuth.AuthorizationStart start = oauth.start(request);
        String binding = SecureTokens.BROWSER_BINDING.generate();
        Optional<?> started = transactions.startBrowser(StorageProvider.GOOGLE_DRIVE, ticket, start.state(),
                start.nonceHash(), start.codeVerifier(), binding);
        if (started.isEmpty()) {
            invalidLink(response);
            return;
        }
        response.addHeader("Set-Cookie", bindingCookie(binding, properties.transactionTtl().toSeconds()).toString());
        BrowserPages.redirect(response, URI.create(start.authorizationUri()));
    }

    /** Google's redirect back. Every value here is untrusted until matched to a transaction. */
    @GetMapping(CALLBACK_PATH)
    public void callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Optional<StorageConnectionService.Claimed> claimed =
                transactions.claimCallback(StorageProvider.GOOGLE_DRIVE, state);
        response.addHeader("Set-Cookie", bindingCookie("", 0).toString());
        if (claimed.isEmpty()) {
            invalidLink(response);
            return;
        }
        Outcome outcome;
        if (!bindingMatches(request, claimed.get().bindingHash())) {
            outcome = completer.fail(claimed.get(), Failure.BROWSER_MISMATCH);
        } else if (error != null) {
            outcome = completer.fail(claimed.get(), "access_denied".equals(error) ? Failure.ACCESS_DENIED
                    : Failure.AUTHORIZATION_FAILED);
        } else if (code == null || code.isEmpty() || code.length() > 2048) {
            outcome = completer.fail(claimed.get(), Failure.AUTHORIZATION_FAILED);
        } else {
            outcome = completer.complete(claimed.get(), code, state);
        }
        render(response, outcome);
    }

    private static void render(HttpServletResponse response, Outcome outcome) throws IOException {
        switch (outcome) {
            case Outcome.Connected connected -> BrowserPages.message(response, HttpServletResponse.SC_OK,
                    connected.reconnected() ? "Google Drive reconnected" : "Google Drive connected",
                    "You can close this tab and return to HyperDrive. HyperDrive has read-only access to this Drive.");
            case Outcome.Failed failed -> BrowserPages.message(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Google Drive wasn't connected", failureMessage(failed.failure()));
        }
    }

    private static String failureMessage(Failure failure) {
        return switch (failure) {
            case ACCESS_DENIED -> "You didn't allow access. Return to HyperDrive to try again.";
            case DRIVE_SCOPE_NOT_GRANTED -> "Drive access wasn't granted. When you try again, make sure the "
                    + "permission to see your Google Drive files is selected.";
            case BROWSER_MISMATCH -> "This connection was finished in a different browser than the one that started it. "
                    + "Return to HyperDrive and try again.";
            default -> "Something went wrong while connecting. Return to HyperDrive and try again.";
        };
    }

    private static void invalidLink(HttpServletResponse response) throws IOException {
        BrowserPages.message(response, HttpServletResponse.SC_BAD_REQUEST, "This connection link can't be used",
                "It is invalid, has expired, was cancelled, or was already used. Return to HyperDrive and choose "
                        + "“Connect Google Drive” again.");
    }

    private static boolean bindingMatches(HttpServletRequest request, byte[] expectedHash) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || expectedHash == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (BINDING_COOKIE.equals(cookie.getName()) && SecureTokens.BROWSER_BINDING.isWellFormed(cookie.getValue())) {
                return MessageDigest.isEqual(SecureTokens.hash(cookie.getValue()), expectedHash);
            }
        }
        return false;
    }

    private ResponseCookie bindingCookie(String value, long maxAgeSeconds) {
        // SameSite=Lax: sent on Google's top-level redirect back to the callback, not on cross-site subrequests.
        return ResponseCookie.from(BINDING_COOKIE, value)
                .httpOnly(true).secure(secureCookies).sameSite("Lax").path(COOKIE_PATH).maxAge(maxAgeSeconds)
                .build();
    }
}
