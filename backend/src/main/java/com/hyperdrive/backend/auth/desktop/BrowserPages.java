package com.hyperdrive.backend.auth.desktop;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Minimal static pages and redirects for the system-browser leg of desktop sign-in. */
final class BrowserPages {

    private BrowserPages() {
    }

    static void securityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        // Keep handoff codes and transaction ids out of Referer headers.
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    }

    /** Messages are fixed strings from this codebase; nothing user-controlled is rendered. */
    static void message(HttpServletResponse response, int status, String title, String message) throws IOException {
        securityHeaders(response);
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>HyperDrive</title></head>
                <body style="font-family:system-ui,sans-serif;background:#0f1217;color:#e6e9ee;max-width:36rem;margin:15vh auto;padding:0 1rem">
                <h1 style="font-size:1.4rem">%s</h1><p>%s</p></body></html>
                """.formatted(title, message);
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }

    static void redirect(HttpServletResponse response, URI location) throws IOException {
        securityHeaders(response);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location.toASCIIString());
    }

    static void invalidLink(HttpServletResponse response) throws IOException {
        message(response, HttpServletResponse.SC_BAD_REQUEST, "This sign-in link can't be used",
                "It is invalid, has expired, or was already opened. Return to HyperDrive and choose "
                        + "“Continue with Google” again.");
    }
}
