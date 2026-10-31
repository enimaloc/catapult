package fr.enimaloc.catapult.common.dto;

import java.util.List;
import java.util.UUID;

public record UserInfoResponse(
        UUID id,
        String twitchId,
        String username,
        String profileImageUrl,
        String status,
        List<String> roles,
        String deletionRequestedAt
) {}
