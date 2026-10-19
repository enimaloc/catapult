package fr.enimaloc.catapult.common.dto.admin;

/**
 * The Microsoft device code catapult-api's {@code POST /api/admin/minecraft-accounts/device-code}
 * starts (its snake_case {@code device_code}, {@code user_code}, {@code verification_uri},
 * {@code interval}), as the admin page reads it: it shows the user code and the verification link
 * and polls every {@code max(3, interval)} seconds.
 */
public record MinecraftDeviceCodeDto(String deviceCode, String userCode, String verificationUri, int interval) {}
