/**
 * prompt `mode` validation (shared by REST and WS; F-1/F-4 fixes, 2026-10-12).
 *
 * Contract (PM ruling):
 *  - `mode` is either omitted entirely (= default `steer`), or the string
 *    `"steer"` / `"queue"`;
 *  - an explicit `null` is invalid => 400 `invalid_request`, exactly like the
 *    other bad values (previously only `null` was treated as "not supplied").
 *
 * Why its own module: F-1 happened because the WS branch parsed `mode` itself
 * instead of reusing the REST check, so an illegal mode was silently downgraded
 * to steer on WS.
 */
import { RcError } from './errors.mjs';

/** Allowed values (mirrors DSH `session.d.ts`: `'queue' | 'steer'`). */
export const PROMPT_MODES = ['steer', 'queue'];
/** Default: the phone's message has top priority, so it interrupts the running turn. */
export const DEFAULT_PROMPT_MODE = 'steer';

/**
 * Normalise `mode`. Throws `RcError('invalid_request')` for illegal values,
 * which the REST layer turns into 400 and the WS layer into an error frame.
 * @param {unknown} raw body.mode; `undefined` means "not supplied"
 * @returns {'steer'|'queue'}
 */
export function normalizeMode(raw) {
  if (raw === undefined) return DEFAULT_PROMPT_MODE;   // omitted => default
  if (raw === null) throw invalidMode();               // explicit null is illegal (F-4)
  if (typeof raw !== 'string') throw invalidMode();
  if (!PROMPT_MODES.includes(raw)) throw invalidMode();
  return raw;
}

function invalidMode() {
  // Reuse the shared RcError so the error carries BOTH `code` and `rcCode`, and
  // so `mapInnerError` returns it unchanged (it only recognises real RcErrors).
  // A bare Error with a hand-set rcCode was mapped to dsh_unavailable (503) --
  // that was the F-4 regression. See errors.mjs for the mapping table.
  return new RcError('invalid_request', 'mode must be "steer" or "queue"');
}

/** Non-throwing predicate (self-checks and unit tests). */
export function isValidMode(raw) {
  try { normalizeMode(raw); return true; } catch { return false; }
}
