package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record MemberTargeting(List<FlagView> flags, List<String> groupKeys) {}
