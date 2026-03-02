/**
 * AK/SK Authentication
 *
 * Generates HMAC-SHA256 signatures for authenticating the PCAgent
 * with the AI-Gateway. The signature covers the access key, a Unix
 * epoch-second timestamp, and a one-time nonce to prevent replay attacks.
 */

import { createHmac, randomUUID } from 'node:crypto';

/** Parameters produced by {@link AkSkAuth.sign} and sent as query params on the WebSocket URL. */
export interface AuthParams {
  /** Access Key */
  ak: string;
  /** Unix epoch seconds as a string */
  timestamp: string;
  /** One-time random UUID */
  nonce: string;
  /** HMAC-SHA256 Base64 signature */
  signature: string;
}

/**
 * AK/SK authentication helper.
 *
 * Usage:
 * ```ts
 * const params = AkSkAuth.sign(ak, sk);
 * // params.ak, params.timestamp, params.nonce, params.signature
 * ```
 */
export class AkSkAuth {
  /**
   * Produce authentication parameters.
   *
   * Signature algorithm:
   * ```
   * message  = "{ak}\n{timestamp}\n{nonce}"
   * signature = HMAC-SHA256(sk, message)   // Base64-encoded
   * ```
   *
   * @param ak - Access Key
   * @param sk - Secret Key
   * @returns Authentication parameters ready for query-string use.
   */
  static sign(ak: string, sk: string): AuthParams {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const nonce = randomUUID();
    const message = `${ak}\n${timestamp}\n${nonce}`;
    const signature = createHmac('sha256', sk).update(message).digest('base64');

    return { ak, timestamp, nonce, signature };
  }
}
