package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.XboxStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Decodes a dev/test "backdoor" UUID into a fake {@link DetectedGame}, bypassing widget-token
 * resolution entirely, for exercising {@code /api/v2/game/{uuid}} without a real linked account.
 *
 * <p>Bit layout of the UUID (both halves are 64-bit longs):
 * <ul>
 *   <li>MSB bits [63:15] must be zero (sentinel) — bits [14:0] are extra high-order payload
 *       (XBOX only, see below).
 *   <li>LSB bits [63:52] must be zero (sentinel).
 *   <li>LSB bits [51:48] are a 4-bit source-type nibble (1 = STEAM, 2 = XBOX, else MANUAL).
 *   <li>LSB bits [47:0] are the low 48 bits of the payload.
 * </ul>
 *
 * <p>Steam app ids (and MANUAL's placeholder) are plain decimal numbers and fit the 48-bit low
 * payload alone. Xbox Store product ids are alphanumeric (e.g. "9NBLGGH2JHXJ") and don't, so XBOX
 * also borrows the MSB's spare 15 bits as high-order payload, combining to a 63-bit value read as
 * base36 instead of decimal — a full 12-character product id (~62 bits) fits, since 36^12 < 2^63.
 */
@Component
@RequiredArgsConstructor
public class DevBackdoorResolver {

    private final SteamStoreService steamStoreService;
    private final XboxStoreService xboxStoreService;

    private static final long BACKDOOR_MASK_PREMIER_ENTIER = 0x000F000000000000L;
    private static final long BACKDOOR_MASK_SECOND_ENTIER = 0x0000FFFFFFFFFFFFL;
    private static final long BACKDOOR_MASK_VALIDATION = 0xFFF0000000000000L;
    private static final long BACKDOOR_MASK_MSB_VALIDATION = 0xFFFFFFFFFFFF8000L;
    private static final long BACKDOOR_MASK_MSB_PAYLOAD = 0x0000000000007FFFL;

    public Optional<DetectedGame> resolve(UUID uuid) {
        if ((uuid.getMostSignificantBits() & BACKDOOR_MASK_MSB_VALIDATION) != 0L
                || (uuid.getLeastSignificantBits() & BACKDOOR_MASK_VALIDATION) != 0L) {
            return Optional.empty();
        }
        GameBinding.SourceType type = switch ((int) ((uuid.getLeastSignificantBits() & BACKDOOR_MASK_PREMIER_ENTIER) >>> 48)) {
            case 1 -> GameBinding.SourceType.STEAM;
            case 2 -> GameBinding.SourceType.XBOX;
            default -> GameBinding.SourceType.MANUAL;
        };
        long lowPayload = uuid.getLeastSignificantBits() & BACKDOOR_MASK_SECOND_ENTIER;
        String sourceId;
        if (type == GameBinding.SourceType.XBOX) {
            long highPayload = uuid.getMostSignificantBits() & BACKDOOR_MASK_MSB_PAYLOAD;
            long payload = (highPayload << 48) | lowPayload;
            sourceId = Long.toString(payload, 36).toUpperCase(Locale.ROOT);
        } else {
            sourceId = String.valueOf(lowPayload);
        }
        String name = (switch (type) {
            case STEAM -> steamStoreService.fetchData(sourceId, Locale.ENGLISH)
                        .map(SteamStoreService.SteamStorePage::name);
            case XBOX -> xboxStoreService.fetchProduct(sourceId, Locale.ENGLISH)
                    .map(XboxStoreService.XboxProduct::title);
            default -> Optional.of(sourceId);
        }).orElse(sourceId);
        return Optional.of(new DetectedGame(sourceId, type, name));
    }
}
