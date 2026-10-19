package fr.enimaloc.catapult.common.dto.admin;

/**
 * The Microsoft device code catapult-api's {@code POST /api/admin/minecraft-accounts/device-code}
 * starts (its snake_case {@code device_code}, {@code user_code}, {@code verification_uri},
 * {@code interval}, {@code expires_in}), as the admin page reads it: it shows the user code, the
 * verification link and the time left before the code expires ({@code expiresIn} seconds, 0 when
 * unknown), and polls every {@code max(3, interval)} seconds.
 */
public record MinecraftDeviceCodeDto(String deviceCode, String userCode, String verificationUri, int interval, int expiresIn) {}
