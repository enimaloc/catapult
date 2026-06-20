package fr.enimaloc.catapult.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Enriched IGDB game details cache (stale-while-revalidate).
 * Mirrors the {@code igdb_game_details} table defined in V39.
 */
@Entity
@Table(name = "igdb_game_details")
@Getter
@Setter
@NoArgsConstructor
public class IgdbGameDetails {

    @Id
    @Column(name = "igdb_id", length = 64)
    private String igdbId;

    @Column(length = 255)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "first_release_date")
    private Instant firstReleaseDate;

    @Convert(converter = WebsitesMapConverter.class)
    @Column(name = "websites_json", nullable = false, columnDefinition = "JSONB")
    private Map<String, String> websites = new HashMap<>();

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /**
     * Serializes a {@code Map<String, String>} to a JSON string for storage in a JSONB column.
     */
    @Converter
    public static class WebsitesMapConverter implements AttributeConverter<Map<String, String>, String> {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            try {
                return MAPPER.writeValueAsString(attribute == null ? Map.of() : attribute);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize websites map", e);
            }
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return new HashMap<>();
            }
            try {
                Map<String, String> parsed = MAPPER.readValue(dbData, TYPE);
                return parsed == null ? new HashMap<>() : parsed;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize websites map", e);
            }
        }
    }
}
