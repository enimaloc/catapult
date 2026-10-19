package fr.enimaloc.catapult.common.dto.admin;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * The Microsoft device code catapult-api's {@code POST /api/admin/minecraft-accounts/device-code}
 * starts: read from its snake_case answer, written camelCase to the admin page, which shows the user
 * code and the verification link and polls every {@code max(3, interval)} seconds.
 */
public record MinecraftDeviceCodeDto(@JsonAlias("device_code") String deviceCode,
                                     @JsonAlias("user_code") String userCode,
                                     @JsonAlias("verification_uri") String verificationUri,
                                     int interval) {}
