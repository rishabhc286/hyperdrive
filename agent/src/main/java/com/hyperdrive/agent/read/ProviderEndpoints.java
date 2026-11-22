package com.hyperdrive.agent.read;

import java.net.URI;

/**
 * Fixed provider content endpoints. Production uses {@link #PRODUCTION} only; there is no
 * bootstrap field, API field, environment variable or system property that changes them. Tests
 * construct the agent in-process with local mock servers instead.
 */
public record ProviderEndpoints(URI googleDriveApi, URI dropboxContentApi) {

    public static final ProviderEndpoints PRODUCTION = new ProviderEndpoints(
            URI.create("https://www.googleapis.com/drive/v3"),
            URI.create("https://content.dropboxapi.com/2"));
}
