package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record AppConfigResponse(String name, int deletionDelayDays, List<String> gettersName) {}
