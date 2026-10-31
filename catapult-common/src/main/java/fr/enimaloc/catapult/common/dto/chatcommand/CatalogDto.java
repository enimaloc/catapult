package fr.enimaloc.catapult.common.dto.chatcommand;

import java.util.List;

public record CatalogDto(List<String> contextPaths, List<ServiceFunctionDto> serviceFunctions,
                          List<String> settingKeys, List<TwOptionDto> knownTws) {}
