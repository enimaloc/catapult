package fr.enimaloc.catapult.common.dto.admin;

/** A method of a BGS service, from bgs.desc; {@code noResponse}: nobody answers it. */
public record BgsMethodDto(String name, int id, String inputType, String outputType, boolean noResponse) {}
