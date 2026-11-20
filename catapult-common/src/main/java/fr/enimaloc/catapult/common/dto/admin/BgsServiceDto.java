package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** A BGS service of bgs.desc by its proto full name, with its wire hash ({@code 0x138D200C}). */
public record BgsServiceDto(String name, String hash, List<BgsMethodDto> methods) {}
