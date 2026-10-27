package fr.enimaloc.catapult.common.dto.admin;

/** One suffix of IANA's Well-Known URIs registry; reference without its [brackets]. */
public record IanaWellKnownDto(String suffix, String reference, String status, String changeController) {}
