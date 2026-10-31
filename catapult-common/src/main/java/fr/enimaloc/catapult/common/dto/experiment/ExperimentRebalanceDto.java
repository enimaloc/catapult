package fr.enimaloc.catapult.common.dto.experiment;

import java.util.List;
import java.util.UUID;

/** How many members a weight change moves from one variant to another; empty when nobody moves. */
public record ExperimentRebalanceDto(List<Move> moves) {
    public record Move(UUID fromVariantId, UUID toVariantId, long count) {}
}
