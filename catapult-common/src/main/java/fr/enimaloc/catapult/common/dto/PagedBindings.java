package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record PagedBindings(int number, int totalPages, long totalElements, List<BindingDto> content) {
    public boolean first() { return number == 0; }
    public boolean last() { return number >= totalPages - 1; }
}
