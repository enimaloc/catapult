package fr.enimaloc.catapult.service.feedback;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The release notes, as built into the jar in {@code CHANGELOG.md}'s format (see catapult-api's generateChangelog). */
@Slf4j
@Service
public class ChangelogService {

    private static final Pattern VERSION = Pattern.compile("^## \\[?(v[^]\\s]+)]?.*$");
    private static final Pattern KIND = Pattern.compile("^#{3,}\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^[-*]\\s+(.+)$");
    private static final Pattern SCOPE = Pattern.compile("^\\*\\*([^*]+):\\*\\*\\s+(.+)$");
    private static final Pattern HASH = Pattern.compile("^(.+)\\s+\\(([0-9a-f]{7,40})\\)$");

    public record ChangelogEntry(String hash, String type, String scope, String subject, boolean breaking) {}

    public record ChangelogSection(String version, List<ChangelogEntry> entries) {}

    @Getter
    private List<ChangelogSection> sections = List.of();

    @PostConstruct
    public void load() {
        try {
            sections = parse(readFromClasspath());
        } catch (Exception e) {
            log.warn("Could not load changelog: {}", e.getMessage());
        }
    }

    private List<String> readFromClasspath() throws Exception {
        InputStream is = getClass().getResourceAsStream("/changelog.md");
        if (is == null) return List.of();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    static List<ChangelogSection> parse(List<String> lines) {
        List<ChangelogSection> result = new ArrayList<>();
        String currentVersion = null;
        String currentType = "chore";
        List<ChangelogEntry> currentEntries = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();

            Matcher version = VERSION.matcher(line);
            if (version.matches()) {
                // Every version stays listed, even one published without notes
                if (currentVersion != null) {
                    result.add(new ChangelogSection(currentVersion, List.copyOf(currentEntries)));
                    currentEntries.clear();
                }
                currentVersion = version.group(1);
                currentType = "chore";
                continue;
            }

            Matcher kind = KIND.matcher(line);
            if (kind.matches()) {
                currentType = typeOf(kind.group(1));
                continue;
            }

            // Before the first version: the file's own introduction
            if (currentVersion == null || isNotAnEntry(line)) continue;
            currentEntries.add(entry(line, currentType));
        }

        if (currentVersion != null) {
            result.add(new ChangelogSection(currentVersion, List.copyOf(currentEntries)));
        }
        return Collections.unmodifiableList(result);
    }

    /** The notes' section, written by the release bot or by hand. */
    private static String typeOf(String heading) {
        return switch (heading.toLowerCase(Locale.ROOT)) {
            case "features" -> "feat";
            case "fixes", "corrections" -> "fix";
            default -> "chore";
        };
    }

    private static boolean isNotAnEntry(String line) {
        return line.isEmpty() || line.startsWith("<!--") || line.startsWith("**Full Changelog**")
                || line.equals("_Aucune note._");
    }

    private static ChangelogEntry entry(String line, String type) {
        Matcher bullet = BULLET.matcher(line);
        String subject = bullet.matches() ? bullet.group(1) : line;

        String hash = null;
        Matcher withHash = HASH.matcher(subject);
        if (withHash.matches()) {
            subject = withHash.group(1);
            hash = withHash.group(2);
        }

        String scope = null;
        Matcher withScope = SCOPE.matcher(subject);
        if (withScope.matches()) {
            scope = withScope.group(1);
            subject = withScope.group(2);
        }
        return new ChangelogEntry(hash, type, scope, subject, false);
    }
}
