package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** GET /api/internal/admin/ccls: every Twitch CCL, and every IGDB descriptor they can be mapped to. */
public record AdminCclPage(List<AdminCclDto> ccls, List<AdminIgdbDescriptorDto> igdbDescriptors) {}
