package fr.enimaloc.catapult.common.dto.admin;

/**
 * An admin's free BGS call. Known method when {@code service} is set ({@code method} by name, {@code text} in
 * protobuf text format); otherwise raw: {@code serviceHash} in hex, {@code methodId}, {@code hex} body (may be empty).
 * {@code withCiid} null: the session's rule (friends and presence calls carry it).
 */
public record BgsCallRequest(String service, String method, String text, String serviceHash, Integer methodId,
                             String hex, Boolean withCiid) {}
