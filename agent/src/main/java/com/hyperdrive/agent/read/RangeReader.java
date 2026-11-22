package com.hyperdrive.agent.read;

import com.hyperdrive.agent.read.CredentialRegistry.Credential;

/** Reads {@code [start, endInclusive]} of a provider file (the real adapter, or a deterministic fake in tests). */
public interface RangeReader {

    ProviderReader.Result read(Credential credential, String fileId, long start, long endInclusive, long expectedSize,
            String expectedRevision, CancelToken cancel) throws ReadException;
}
