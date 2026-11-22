package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.auth.SecureTokens;
import com.hyperdrive.backend.auth.desktop.BrowserPages;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.connect.ConnectionOutcome.Failure;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * System-browser leg of a storage connection, for every provider. Separate from sign-in: its own
 * paths, client registrations and callbacks, no HTTP session, and it never creates or changes a
 * HyperDrive login. The browser only ever sees a one-time ticket, the provider's parameters and
 * fixed pages.
 */
@Controller
@ConditionalOnAuthEnabled
public class StorageBrowserController {

    public static final String BASE = "/api/v1/storage-connections";
    static final String BINDING_COOKIE = "HD_STORAGE_BINDING";

    private final StorageConnectionService transactions;
    private final Map<StorageProvider, ConnectionCompleter> completers;
    private final StorageProperties properties;
    private final boolean secureCookies;

    public StorageBrowserController(StorageConnectionService transactions, List<ConnectionCompleter> completers,
            StorageProperties properties, @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) {
        this.transactions = transactions;
        this.completers = completers.stream().collect(Collectors.toUnmodifiableMap(ConnectionCompleter::provider, c -> c));
        this.properties = properties;
        this.secureCookies = secureCookies;
    }

    public static String callbackPath(StorageProvider provider) {
        return BASE + "/" + provider.pathSegment() + "/callback";
    }

    public static String authorizePath(StorageProvider provider) {
        return BASE + "/" + provider.pathSegment() + "/authorize";
    }

    /** Opened once by the system browser; consumes the ticket and redirects to the provider's consent. */
    @GetMapping(BASE + "/{provider:google|dropbox}/authorize/{ticket}")
    public void authorize(@PathVariable String provider, @PathVariable String ticket, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        Optional<ConnectionCompleter> completer = completer(provider);
        if (completer.isEmpty() || !SecureTokens.STORAGE_TICKET.isWellFormed(ticket)) {
            invalidLink(response);
            return;
        }
        StorageProvider p = completer.get().provider();
        StorageOAuth.AuthorizationStart start = completer.get().oauth().start(request);
        String binding = SecureTokens.BROWSER_BINDING.generate();
        if (transactions.startBrowser(p, ticket, start.state(), start.nonceHash(), start.codeVerifier(), binding).isEmpty()) {
            invalidLink(response);
            return;
        }
        response.addHeader("Set-Cookie", bindingCookie(p, binding, properties.transactionTtl().toSeconds()).toString());
        BrowserPages.redirect(response, URI.create(start.authorizationUri()));
    }

    /** The provider's redirect back. Every value here is untrusted until matched to a transaction. */
    @GetMapping(BASE + "/{provider:google|dropbox}/callback")
    public void callback(@PathVariable String provider, @RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<ConnectionCompleter> completer = completer(provider);
        if (completer.isEmpty()) {
            invalidLink(response);
            return;
        }
        StorageProvider p = completer.get().provider();
        Optional<StorageConnectionService.Claimed> claimed = transactions.claimCallback(p, state);
        response.addHeader("Set-Cookie", bindingCookie(p, "", 0).toString());
        if (claimed.isEmpty()) {
            invalidLink(response);
            return;
        }
        ConnectionOutcome outcome;
        if (!bindingMatches(request, claimed.get().bindingHash())) {
            outcome = transactions.failWith(claimed.get(), Failure.BROWSER_MISMATCH);
        } else if (error != null) {
            outcome = transactions.failWith(claimed.get(), "access_denied".equals(error) ? Failure.ACCESS_DENIED
                    : Failure.AUTHORIZATION_FAILED);
        } else if (code == null || code.isEmpty() || code.length() > 2048) {
            outcome = transactions.failWith(claimed.get(), Failure.AUTHORIZATION_FAILED);
        } else {
            outcome = completer.get().complete(claimed.get(), code, state);
        }
        render(response, p, outcome);
    }

    private Optional<ConnectionCompleter> completer(String segment) {
        return StorageProvider.fromPathSegment(segment).map(completers::get);
    }

    private static String label(StorageProvider provider) {
        return provider == StorageProvider.DROPBOX ? "Dropbox" : "Google Drive";
    }

    private static void render(HttpServletResponse response, StorageProvider provider, ConnectionOutcome outcome)
            throws IOException {
        switch (outcome) {
            case ConnectionOutcome.Connected connected -> BrowserPages.message(response, HttpServletResponse.SC_OK,
                    label(provider) + (connected.reconnected() ? " reconnected" : " connected"),
                    "You can close this tab and return to HyperDrive. HyperDrive has read-only access to this account.");
            case ConnectionOutcome.Failed failed -> BrowserPages.message(response, HttpServletResponse.SC_BAD_REQUEST,
                    label(provider) + " wasn't connected", failureMessage(failed.failure()));
        }
    }

    private static String failureMessage(Failure failure) {
        return switch (failure) {
            case ACCESS_DENIED -> "You didn't allow access. Return to HyperDrive to try again.";
            case DRIVE_SCOPE_NOT_GRANTED -> "Drive access wasn't granted. When you try again, make sure the "
                    + "permission to see your Google Drive files is selected.";
            case SCOPE_NOT_GRANTED -> "The permissions HyperDrive needs weren't granted. Return to HyperDrive and try again.";
            case UNSUPPORTED_ACCOUNT -> "This account type isn't supported by HyperDrive yet. See the HyperDrive "
                    + "documentation for supported accounts.";
            case BROWSER_MISMATCH -> "This connection was finished in a different browser than the one that started it. "
                    + "Return to HyperDrive and try again.";
            default -> "Something went wrong while connecting. Return to HyperDrive and try again.";
        };
    }

    private static void invalidLink(HttpServletResponse response) throws IOException {
        BrowserPages.message(response, HttpServletResponse.SC_BAD_REQUEST, "This connection link can't be used",
                "It is invalid, has expired, was cancelled, or was already used. Return to HyperDrive and start "
                        + "the connection again.");
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

    /** Path-scoped per provider, so parallel Google and Dropbox attempts never overwrite each other. */
    private ResponseCookie bindingCookie(StorageProvider provider, String value, long maxAgeSeconds) {
        // SameSite=Lax: sent on the provider's top-level redirect back to the callback only.
        return ResponseCookie.from(BINDING_COOKIE, value)
                .httpOnly(true).secure(secureCookies).sameSite("Lax").path(BASE + "/" + provider.pathSegment())
                .maxAge(maxAgeSeconds).build();
    }
}
