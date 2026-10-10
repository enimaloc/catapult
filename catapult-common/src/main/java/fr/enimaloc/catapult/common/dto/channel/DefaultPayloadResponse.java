package fr.enimaloc.catapult.common.dto.channel;

import java.util.List;

public record DefaultPayloadResponse(String message, String style, String icon, String authorName,
                                      List<DefaultActionResponse> actions) {}
