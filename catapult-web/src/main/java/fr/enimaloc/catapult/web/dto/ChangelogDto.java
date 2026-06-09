package fr.enimaloc.catapult.web.dto;

import java.util.List;

public class ChangelogDto {

    public record ChangelogSection(String version, List<ChangelogEntry> entries) {}

    public record ChangelogEntry(String hash, String type, String scope, String subject, boolean breaking) {}
}
