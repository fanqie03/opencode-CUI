package com.opencode.cui.skill.model;

/**
 * Result of resolving an assistant account to its corresponding AK and owner.
 *
 * @param ak            the application key bound to this assistant
 * @param ownerWelinkId the WeLink ID of the assistant's owner (used as userId
 *                      in Gateway interactions)
 */
public record AssistantResolveResult(String ak, String ownerWelinkId) {
}
