package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingErrorDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * How the custom bindings read on the wire, shared by the token API ({@code /api/v2/custom-bindings}), the owner's
 * endpoints and the channel dashboard: the DTOs, and what an activation's outcome answers (204 once applied; 409
 * for a broken Twitch link or a paused bot, 502 when Twitch failed, with a {@link CustomBindingErrorDto}).
 */
@Component
@RequiredArgsConstructor
public class CustomBindingViews {

    private final CustomBindingService service;

    /** {@code user}'s bindings and the active one; 403 while the experiment is closed to them. */
    public CustomBindingsStateDto state(UserAccount user) {
        List<CustomBindingDto> bindings = service.list(user).stream().map(CustomBindingViews::toDto).toList();
        Optional<CustomBindingActivation> active = service.active(user);
        return new CustomBindingsStateDto(true, bindings,
                active.map(activation -> activation.getCustomBinding().getId()).orElse(null),
                active.map(CustomBindingActivation::getActivatedAt).orElse(null));
    }

    /** {@link #state}, or null while the experiment is closed to {@code user}. */
    public CustomBindingsStateDto stateIfEnabled(UserAccount user) {
        return service.enabled(user) ? state(user) : null;
    }

    public static CustomBindingDto toDto(CustomBinding binding) {
        return new CustomBindingDto(binding.getId(), binding.getName(), binding.getTwitchGameId(),
                binding.getTwitchGameName(), binding.getCcls().stream().sorted().toList(),
                binding.getTws().stream().sorted().toList(), binding.isUninterruptible());
    }

    public static CustomBindingSaveResult saved(CustomBindingService.UpdateResult result) {
        return new CustomBindingSaveResult(toDto(result.binding()),
                result.reapplied() == null ? null : outcomeCode(result.reapplied()));
    }

    /** What triggering a binding answers: 204 once Twitch took it, else the error and its code. */
    public static ResponseEntity<?> activation(ActivationResult result) {
        return switch (result) {
            case APPLIED -> ResponseEntity.noContent().build();
            case TWITCH_UNAVAILABLE -> ResponseEntity.status(HttpStatus.CONFLICT).body(new CustomBindingErrorDto(
                    CustomBindingErrorDto.TWITCH_UNAVAILABLE, "Reconnecte ton compte Twitch"));
            case BOT_DISABLED -> ResponseEntity.status(HttpStatus.CONFLICT).body(new CustomBindingErrorDto(
                    CustomBindingErrorDto.BOT_DISABLED, "Catapult est en pause sur cette chaîne"));
            case FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new CustomBindingErrorDto(
                    CustomBindingErrorDto.TWITCH_FAILED, "Twitch n'a pas accepté le changement de catégorie"));
        };
    }

    /** {@code result} as {@link CustomBindingSaveResult#reapplyOutcome()} reads it. */
    public static String outcomeCode(ActivationResult result) {
        return switch (result) {
            case APPLIED -> CustomBindingSaveResult.APPLIED;
            case TWITCH_UNAVAILABLE -> CustomBindingErrorDto.TWITCH_UNAVAILABLE;
            case BOT_DISABLED -> CustomBindingErrorDto.BOT_DISABLED;
            case FAILED -> CustomBindingErrorDto.TWITCH_FAILED;
        };
    }
}
