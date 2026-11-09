package fr.enimaloc.catapult.common.dto.experiment;

import java.util.UUID;

public record FeedbackRequest(UUID experimentId, int npsScore, String comment) {}
