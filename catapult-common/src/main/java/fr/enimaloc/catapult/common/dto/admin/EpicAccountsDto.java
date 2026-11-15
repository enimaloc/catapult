package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** The Epic bot pool and {@code codeUrl}, the page that gives the authorization code of the bot logged in there. */
public record EpicAccountsDto(List<EpicAccountDto> accounts, String codeUrl) {}
