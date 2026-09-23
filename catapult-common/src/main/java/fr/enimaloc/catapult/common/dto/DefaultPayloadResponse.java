package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record DefaultPayloadResponse(String message, String style, String icon, String authorName,
                                      List<DefaultActionResponse> actions) {}
