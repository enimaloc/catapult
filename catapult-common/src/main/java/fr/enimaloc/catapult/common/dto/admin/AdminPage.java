package fr.enimaloc.catapult.common.dto.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One page of an admin listing, read off catapult-api's serialized Spring Data page, whichever
 * way Spring Data writes it: the page itself ({@code PageSerializationMode.DIRECT}: number,
 * totalPages and totalElements next to content) or its {@code PagedModel} ({@code VIA_DTO}: those
 * under a {@code page} object).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminPage<T>(List<T> content, int number, int totalPages, long totalElements) {

    public AdminPage {
        content = content == null ? List.of() : content;
    }

    @JsonCreator
    static <T> AdminPage<T> read(@JsonProperty("content") List<T> content,
                                 @JsonProperty("number") Integer number,
                                 @JsonProperty("totalPages") Integer totalPages,
                                 @JsonProperty("totalElements") Long totalElements,
                                 @JsonProperty("page") Metadata page) {
        if (page != null) {
            return new AdminPage<>(content, page.number(), page.totalPages(), page.totalElements());
        }
        return new AdminPage<>(content, number == null ? 0 : number, totalPages == null ? 0 : totalPages,
                totalElements == null ? 0 : totalElements);
    }

    /** {@code PagedModel}'s page metadata. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(int number, int totalPages, long totalElements) {}

    /** The first page (also when there is none). */
    public boolean first() {
        return number <= 0;
    }

    /** The last page (also when there is none). */
    public boolean last() {
        return number >= totalPages - 1;
    }
}
