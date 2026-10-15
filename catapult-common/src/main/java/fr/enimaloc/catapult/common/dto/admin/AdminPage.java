package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** One page of an admin listing, read off catapult-api's serialized Spring Data page. */
public record AdminPage<T>(List<T> content, int number, int totalPages, long totalElements) {

    public boolean first() {
        return number <= 0;
    }

    public boolean last() {
        return number >= totalPages - 1;
    }
}
