package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminCclDto;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminIgdbDescriptorDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The admin page's CCL, caches and data tabs of a mock session, changed by their actions as
 * catapult-api would: a few Twitch CCLs mapped to IGDB descriptors, an IGDB cache big enough to
 * page through, a read-only cache, and two data repositories — one of them the descriptors, which
 * a CCL mapping keeps from being deleted.
 */
public class MockAdminData {
    static final int PAGE_SIZE = 25;
    static final String COMMANDS = "chat-command";
    static final String DESCRIPTORS = "igdb-rating-descriptor";
    private static final String COMMAND_CACHE = "chat-command-user-cache";
    private static final Set<String> READ_ONLY_CACHES = Set.of("tw-known-paths");

    private record Ccl(String id, String name, String description, Set<Long> mapped) {}

    private final Map<String, Ccl> ccls = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> caches = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> repos = new LinkedHashMap<>();

    MockAdminData(String selfId) {
        repos.put(COMMANDS, new ArrayList<>());
        repos.put(DESCRIPTORS, new ArrayList<>());
        descriptor(1, "Violence");
        descriptor(2, "Blood and Gore");
        descriptor(3, "Strong Language");
        descriptor(4, "Use of Drugs");
        descriptor(5, "Sexual Themes");
        descriptor(6, "Simulated Gambling");

        ccl("ViolentGraphic", "Violent and Graphic Depictions", "Realistic or graphic violence and gore.", 1L, 2L);
        ccl("ProfanityVulgarity", "Significant Profanity or Vulgarity", "Frequent and severe profanity.", 3L);
        ccl("DrugsIntoxication", "Drugs, Intoxication, or Excessive Tobacco Use", "Use of illegal drugs or excessive intoxication.", 4L);
        ccl("SexualThemes", "Sexual Themes", "Content focused on sexual attributes or acts.");
        ccl("Gambling", "Gambling", "Participating in online or in-person gambling.", 6L);

        Map<String, String> games = new LinkedHashMap<>();
        for (int i = 1; i <= 30; i++) games.put(String.valueOf(1000 + i), "Mock game #" + i);
        caches.put("igdb-game-cache", games);
        caches.put("igdb-name-index", linked("mock game #1", "1001", "mock game #2", "1002"));
        caches.put(COMMAND_CACHE, linked(selfId, "3 commands"));
        caches.put("tw-known-paths", linked("game.name", "string", "game.ccls", "list"));

        command("!discord", "Join us on discord.gg/mock", selfId);
        command("!lurk", "{user} is lurking", selfId);
        command("!so", "Go follow {target}!", selfId);
    }

    // --- CCL ---

    public AdminCclPage ccl() {
        List<AdminIgdbDescriptorDto> descriptors = descriptors();
        return new AdminCclPage(ccls.values().stream()
                .map(c -> new AdminCclDto(c.id(), c.name(), c.description(),
                        descriptors.stream().filter(d -> c.mapped().contains(d.id())).toList()))
                .toList(), descriptors);
    }

    /** 204, or 404 for an unknown CCL. */
    public int saveCclMappings(String cclId, Set<Long> ids) {
        Ccl saved = ccls.computeIfPresent(cclId, (id, c) -> new Ccl(id, c.name(), c.description(), Set.copyOf(ids)));
        return saved == null ? 404 : 204;
    }

    // --- caches ---

    public List<CacheSummaryDto> caches() {
        return caches.entrySet().stream()
                .map(e -> new CacheSummaryDto(e.getKey(), e.getValue().size(), !READ_ONLY_CACHES.contains(e.getKey())))
                .toList();
    }

    /** Null for an unknown cache, as the real service reads catapult-api's 404. */
    public AdminPage<CacheEntryDto> cacheEntries(String name, String q, int page) {
        Map<String, String> entries = caches.get(name);
        if (entries == null) return null;
        return page(entries.entrySet().stream()
                .map(e -> new CacheEntryDto(e.getKey(), e.getValue()))
                .filter(e -> matches(q, Stream.of(e.key(), e.value())))
                .toList(), page);
    }

    public CacheEntryDetailDto cacheEntry(String name, String key) {
        Map<String, String> entries = caches.get(name);
        if (entries == null || !entries.containsKey(key)) return new CacheEntryDetailDto(key, null, null);
        Object detail = COMMAND_CACHE.equals(name)
                ? List.copyOf(repos.get(COMMANDS))
                : linked("key", key, "value", entries.get(key));
        return new CacheEntryDetailDto(key, detail, name.startsWith("igdb-") ? 3600L : null);
    }

    public int deleteCacheEntry(String name, String key) {
        Map<String, String> entries = caches.get(name);
        if (entries == null) return 404;
        if (READ_ONLY_CACHES.contains(name)) return 400;
        return entries.remove(key) != null ? 204 : 404;
    }

    // --- data ---

    public List<RepoSummaryDto> repos() {
        return repos.entrySet().stream().map(e -> new RepoSummaryDto(e.getKey(), e.getValue().size())).toList();
    }

    public AdminPage<Map<String, Object>> rows(String repo, String q, int page) {
        List<Map<String, Object>> rows = repos.get(repo);
        if (rows == null) return null;
        return page(rows.stream()
                .filter(r -> matches(q, r.values().stream().map(String::valueOf)))
                .map(r -> (Map<String, Object>) new LinkedHashMap<>(r))
                .toList(), page);
    }

    /** The row with its collections, as GET /api/admin/data/{repo}/{id} gives it; null when there is none. */
    public Map<String, Object> row(String repo, String id) {
        Map<String, Object> row = find(repo, id);
        if (row == null) return null;
        Map<String, Object> detail = new LinkedHashMap<>(row);
        if (COMMANDS.equals(repo)) {
            detail.put("aliases", List.of(linked("id", id + "~alias", "label", row.get("name") + "-alias")));
        }
        return detail;
    }

    public int create(String repo, Map<String, String> fields) {
        List<Map<String, Object>> rows = repos.get(repo);
        if (rows == null) return 404;
        String id = fields.getOrDefault("id", String.valueOf(rows.stream()
                .mapToLong(r -> Long.parseLong(String.valueOf(r.get("id")))).max().orElse(0) + 1));
        if (find(repo, id) != null) return 400;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        columns(rows).forEach(column -> row.put(column, fields.get(column)));
        rows.add(row);
        return 201;
    }

    public int update(String repo, String id, Map<String, String> fields) {
        Map<String, Object> row = find(repo, id);
        if (row == null) return 404;
        columns(repos.get(repo)).stream().filter(fields::containsKey).forEach(column -> row.put(column, fields.get(column)));
        return 200;
    }

    public int delete(String repo, String id) {
        Map<String, Object> row = find(repo, id);
        if (row == null) return 404;
        if (DESCRIPTORS.equals(repo) && ccls.values().stream().anyMatch(c -> c.mapped().contains(Long.valueOf(id)))) {
            return 409;
        }
        repos.get(repo).remove(row);
        return 204;
    }

    // --- helpers ---

    private List<AdminIgdbDescriptorDto> descriptors() {
        return repos.get(DESCRIPTORS).stream().map(r -> new AdminIgdbDescriptorDto(
                Long.valueOf(String.valueOf(r.get("id"))), (String) r.get("description"),
                Integer.valueOf(String.valueOf(r.get("organizationId"))), (String) r.get("displayName"))).toList();
    }

    private Map<String, Object> find(String repo, String id) {
        List<Map<String, Object>> rows = repos.get(repo);
        if (rows == null) return null;
        return rows.stream().filter(r -> String.valueOf(r.get("id")).equals(id)).findFirst().orElse(null);
    }

    /** The writable columns: every key of the repository's rows but the id and the "_label" companions. */
    private static Set<String> columns(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        rows.forEach(r -> r.keySet().stream().filter(k -> !k.equals("id") && !k.endsWith("_label")).forEach(columns::add));
        return columns;
    }

    private void descriptor(long id, String description) {
        repos.get(DESCRIPTORS).add(linked("id", String.valueOf(id), "description", description,
                "organizationId", "1", "displayName", "ESRB — " + description));
    }

    private void ccl(String id, String name, String description, Long... mapped) {
        ccls.put(id, new Ccl(id, name, description, Set.of(mapped)));
    }

    private void command(String name, String template, String userId) {
        List<Map<String, Object>> rows = repos.get(COMMANDS);
        rows.add(linked("id", String.valueOf(rows.size() + 1), "name", name, "template", template,
                "enabled", "true", "user", userId, "user_label", "mock streamer"));
    }

    private static boolean matches(String q, Stream<String> values) {
        if (q == null || q.isBlank()) return true;
        String needle = q.toLowerCase(Locale.ROOT);
        return values.anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(needle));
    }

    static <T> AdminPage<T> page(List<T> all, int page) {
        int totalPages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int from = Math.min(Math.max(page, 0) * PAGE_SIZE, all.size());
        return new AdminPage<>(List.copyOf(all.subList(from, Math.min(from + PAGE_SIZE, all.size()))), page, totalPages, all.size());
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> linked(Object... keysAndValues) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], (V) keysAndValues[i + 1]);
        return map;
    }
}
