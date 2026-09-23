package fr.enimaloc.catapult.common.dto;

import java.util.UUID;

public record FeedbackRequest(UUID experimentId, int npsScore, String comment) {}
