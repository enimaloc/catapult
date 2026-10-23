package fr.enimaloc.catapult.service.degraded;

import fr.enimaloc.catapult.common.dto.DegradedEffectDto;

import java.util.List;

/**
 * What the site shows of catapult-api's degraded mode: the properties it changes, each against its normal value,
 * and where to read about it ({@code link}: the Instatus incident when there is one, else the status page; null
 * without a page). Serialized as is for the browser, which words the effects (degraded.js).
 */
public record DegradedView(boolean degraded, List<DegradedEffectDto> effects, String link, boolean incident) {
    public static final DegradedView NONE = new DegradedView(false, List.of(), null, false);
}
