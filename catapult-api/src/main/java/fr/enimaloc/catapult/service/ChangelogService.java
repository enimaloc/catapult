package fr.enimaloc.catapult.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChangelogService {

    private static final Pattern CONVENTIONAL = Pattern.compile(
        "^(feat|fix|docs|chore|refactor|test|perf|ci|build|style)(\\([^)]+\\))?(!)?:\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );

    public record ChangelogEntry(String hash, String type, String scope, String subject, boolean breaking) {}

    public record ChangelogSection(String version, List<ChangelogEntry> entries) {}

    @Getter
    private List<ChangelogSection> sections = List.of();

    @PostConstruct
    public void load() {
        try {
            List<String> lines;
            try {
                lines = runGitLog();
            } catch (Exception e) {
                log.debug("git not available, loading changelog from classpath: {}", e.getMessage());
                lines = readFromClasspath();
            }
            sections = parse(lines);
        } catch (Exception e) {
            log.warn("Could not load changelog: {}", e.getMessage());
        }
    }

    private List<String> runGitLog() throws Exception {
        Process process = new ProcessBuilder(
            "git", "log", "--format=%h|%s|%D", "--no-merges", "-n", "100"
        ).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines().toList();
        }
    }

    private List<String> readFromClasspath() throws Exception {
        InputStream is = getClass().getResourceAsStream("/changelog.log");
        if (is == null) return List.of();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().toList();
        }
    }

    private List<ChangelogSection> parse(List<String> lines) {
        List<ChangelogSection> result = new ArrayList<>();
        String currentVersion = "Dernières modifications";
        List<ChangelogEntry> currentEntries = new ArrayList<>();

        for (String line : lines) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) continue;

            String hash = parts[0].trim();
            String subject = parts[1].trim();
            String refs = parts.length == 3 ? parts[2].trim() : "";

            // Detect version tag in refs (e.g. "tag: v1.0.0")
            String tag = extractTag(refs);
            if (tag != null && !currentEntries.isEmpty()) {
                result.add(new ChangelogSection(currentVersion, List.copyOf(currentEntries)));
                currentEntries.clear();
                currentVersion = tag;
            }

            Matcher m = CONVENTIONAL.matcher(subject);
            if (m.matches()) {
                String type = m.group(1).toLowerCase();
                String scope = m.group(2) != null ? m.group(2).replaceAll("[()]", "") : null;
                boolean breaking = m.group(3) != null;
                String msg = m.group(4);
                currentEntries.add(new ChangelogEntry(hash, type, scope, msg, breaking));
            }
        }

        if (!currentEntries.isEmpty()) {
            result.add(new ChangelogSection(currentVersion, List.copyOf(currentEntries)));
        }
        return Collections.unmodifiableList(result);
    }

    private String extractTag(String refs) {
        if (refs.isBlank()) return null;
        for (String ref : refs.split(",")) {
            ref = ref.trim();
            if (ref.startsWith("tag: v")) return ref.substring(5);
        }
        return null;
    }
}
