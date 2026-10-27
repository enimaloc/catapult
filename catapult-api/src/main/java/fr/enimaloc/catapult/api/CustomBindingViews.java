package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * How the custom bindings read on the wire, shared by the token API ({@code /api/v2/custom-bindings}), the owner's
 * endpoints and the channel dashboard: the DTOs, and what an activation's outcome answers (204 once applied; 409
 * for a broken Twitch link or a paused bot, 502 when Twitch failed, each an {@link ApiProblem}).
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
        return ChannelEventPublisher.toDto(binding);
    }

    public static CustomBindingSaveResult saved(CustomBindingService.UpdateResult result) {
        return new CustomBindingSaveResult(toDto(result.binding()),
                result.reapplied() == null ? null : outcomeCode(result.reapplied()));
    }

    /** What triggering a binding answers: 204 once Twitch took it, else a problem with the outcome's code. */
    public static ResponseEntity<Void> activation(ActivationResult result) {
        return switch (result) {
            case APPLIED -> ResponseEntity.noContent().build();
            case TWITCH_UNAVAILABLE -> throw new ApiProblem(ErrorCode.TWITCH_UNAVAILABLE, "Reconnect your Twitch account");
            case BOT_DISABLED -> throw new ApiProblem(ErrorCode.BOT_DISABLED, "Catapult is paused on this channel");
            case FAILED -> throw new ApiProblem(ErrorCode.TWITCH_FAILED, "Twitch did not accept the category change");
        };
    }

    /** {@code result} as {@link CustomBindingSaveResult#reapplyOutcome()} reads it. */
    public static String outcomeCode(ActivationResult result) {
        return switch (result) {
            case APPLIED -> CustomBindingSaveResult.APPLIED;
            case TWITCH_UNAVAILABLE -> ErrorCode.TWITCH_UNAVAILABLE.code();
            case BOT_DISABLED -> ErrorCode.BOT_DISABLED.code();
            case FAILED -> ErrorCode.TWITCH_FAILED.code();
        };
    }
}
