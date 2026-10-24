package fr.enimaloc.catapult.common.dto.admin;

/** Switching a member's debug mode on; {@code revealSecrets} false masks the secrets in what is recorded. */
public record DebugEnableRequest(boolean revealSecrets) {
}
