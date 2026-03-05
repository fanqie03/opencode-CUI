/**
 * PermissionMapper — Maps protocol permission responses to SDK values.
 *
 * The full-stack protocol uses `"allow"` / `"deny"` terminology,
 * while the OpenCode SDK v1 uses `"once"` / `"always"` / `"reject"`.
 *
 * This module provides the translation layer.
 *
 * @see .gsd/phases/1/RESEARCH.md §2 for detailed mapping rationale
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Permission response values used in our protocol (Layer 1 downstream). */
export type ProtocolPermissionResponse = 'allow' | 'always' | 'deny';

/** Permission response values accepted by the OpenCode SDK v1. */
export type SdkPermissionResponse = 'once' | 'always' | 'reject';

// ---------------------------------------------------------------------------
// Mapping
// ---------------------------------------------------------------------------

const RESPONSE_MAP: Record<ProtocolPermissionResponse, SdkPermissionResponse> = {
    allow: 'once',
    always: 'always',
    deny: 'reject',
};

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Map a protocol-level permission response to the SDK-level value.
 *
 * @param response  The protocol response string (e.g., `"allow"`, `"deny"`)
 * @returns         The corresponding SDK response string (e.g., `"once"`, `"reject"`)
 * @throws          If the response string is not a recognized protocol value
 *
 * @example
 * ```ts
 * mapPermissionResponse('allow')   // 'once'
 * mapPermissionResponse('always')  // 'always'
 * mapPermissionResponse('deny')    // 'reject'
 * ```
 */
export function mapPermissionResponse(response: string): SdkPermissionResponse {
    const mapped = RESPONSE_MAP[response as ProtocolPermissionResponse];
    if (!mapped) {
        throw new Error(
            `Unknown permission response: "${response}". Expected one of: ${Object.keys(RESPONSE_MAP).join(', ')}`,
        );
    }
    return mapped;
}
