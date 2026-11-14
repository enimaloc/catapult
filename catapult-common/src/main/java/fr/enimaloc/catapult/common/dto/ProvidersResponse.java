package fr.enimaloc.catapult.common.dto;

public record ProvidersResponse(boolean minecraft, boolean steam, boolean xbox, boolean battlenet, boolean ankama) {
    public boolean hasAny() { return minecraft || steam || xbox || battlenet || ankama; }
}
