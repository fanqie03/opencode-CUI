# PRD: Replace JsonNode with typed response, use @JsonProperty for IM model classes

## Goal

1. Replace `JsonNode` usage in `ImMultiDeviceSyncService.push()` with a typed Java record response class
2. Refactor all three IM model classes (`AppNotifyRequest`, `AppNotifyData`, `ImAppNotifyResponse`) to use `@JsonProperty` instead of `@JsonNaming(SnakeCaseStrategy.class)`

## Confirmed Facts (from codebase inspection)

- **Affected files**: `ImMultiDeviceSyncService.java`, `AppNotifyRequest.java`, `AppNotifyData.java`, `ImMultiDeviceSyncServiceTest.java`
- **Current behavior**: `restTemplate.postForEntity(url, entity, JsonNode.class)` → manual `respBody.path("error")` traversal
- **Response structure** (from parsing logic lines 102-118):
  ```json
  { "error": { "error_code": "...", "error_msg": "..." } }
  ```
- **Impact scope**: `AppNotifyRequest` and `AppNotifyData` are only used by `ImMultiDeviceSyncService` and its test — no other consumers
- **User preference**: Use `@JsonProperty` on each field for explicit wire-format control, not `@JsonNaming` class-level strategy

## Requirements

1. Create `ImAppNotifyResponse` record with `@JsonProperty` annotations (nested `ErrorInfo` for the `error` field)
2. Refactor `AppNotifyRequest` — replace `@JsonNaming(SnakeCaseStrategy.class)` with `@JsonProperty` on each field
3. Refactor `AppNotifyData` — replace `@JsonNaming(SnakeCaseStrategy.class)` with `@JsonProperty` on each field
4. Replace `ResponseEntity<JsonNode>` with `ResponseEntity<ImAppNotifyResponse>` in `ImMultiDeviceSyncService.push()`
5. Replace `JsonNode` path traversal with typed field access (`response.getBody().error().errorCode()` etc.)
6. Update tests to use typed response objects

## Acceptance Criteria

- [ ] No `JsonNode` import remains in `ImMultiDeviceSyncService.java`
- [ ] No `@JsonNaming` remains in `AppNotifyRequest.java`, `AppNotifyData.java`
- [ ] All model fields have explicit `@JsonProperty` annotations
- [ ] Response error checking uses typed field access
- [ ] All existing tests pass
- [ ] `ImMultiDeviceSyncServiceTest` uses typed response objects

## Out of Scope

- Modifying other services that use `JsonNode`
- Changing the logging format
- Adding validation annotations
