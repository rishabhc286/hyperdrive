package com.hyperdrive.backend.user;

import java.util.UUID;

/** Safe user shape returned to the desktop. Contains no tokens or provider data. */
public record UserAccount(UUID id, String username, String displayName, boolean onboardingComplete) {
}
