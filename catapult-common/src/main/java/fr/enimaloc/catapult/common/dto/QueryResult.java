package fr.enimaloc.catapult.common.dto;

public record QueryResult(String result, String error) {
    public boolean hasError() { return error != null; }
}
