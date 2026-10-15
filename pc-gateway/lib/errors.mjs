// pc-gateway/lib/errors.mjs — 契约错误映射（规范 §3.1 + v1.1 补充错误码表）
export const ERR_HTTP = {
  unauthorized: 401,
  invalid_request: 400,
  not_found: 404,
  session_busy: 409,
  dsh_unavailable: 503,
  internal: 500,
};

/** 网关内部异常 → 契约错误体 */
export class RcError extends Error {
  constructor(code, message) {
    super(message || code);
    this.code = code;
    this.rcCode = code;
    this.http = ERR_HTTP[code] ?? 500;
  }
}

export const badRequest = m => new RcError('invalid_request', m);
export const notFound = m => new RcError('not_found', m ?? 'session not found');
export const unauthorized = m => new RcError('unauthorized', m ?? 'missing or invalid token');
export const sessionBusy = m => new RcError('session_busy', m ?? 'session is held by another DSH instance');
export const dshUnavailable = m => new RcError('dsh_unavailable', m ?? 'inner DSH runtime is unavailable');
export const internal = m => new RcError('internal', m ?? 'internal gateway error');

/**
 * 把内层 SessionController 的 RemoteError 映射为契约错误。
 * 实测内层错误码见 pc-gateway/README.md「内层 API 实测形状」。
 */
export function mapInnerError(err) {
  if (err instanceof RcError) return err;
  const code = err?.code ?? '';
  const msg = err?.message ?? String(err);
  if (code === 'gateway/bad-request') return badRequest(msg);
  if (code === 'session/not-found' || code === 'subagent/not-found') return notFound(msg);
  if (code === 'session/writer-held' || code === 'session/agent-busy') return sessionBusy(msg);
  if (code === 'session/title-invalid' || code === 'session/attachment-invalid') return badRequest(msg);
  if (code === 'gateway/cancelled') return internal(msg);
  if (err?.name === 'AbortError') return internal(msg);
  // 无 RemoteError code：内层不可用（尚未就绪 / 崩溃）
  if (!code) return dshUnavailable(msg);
  return internal(`${code}: ${msg}`);
}
