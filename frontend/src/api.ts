export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) { super(message); this.name = 'ApiError'; this.status = status; }
}
let csrfToken = '';
let csrfHeader = 'X-CSRF-TOKEN';
let csrfPromise: Promise<void> | null = null;
export async function prepareCsrf() {
  if (!csrfPromise) csrfPromise = fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }).then(async response => {
    if (!response.ok) throw new ApiError('Unable to start a secure session. Check that the server is running.', response.status);
    const data = await response.json() as { token: string; headerName?: string };
    csrfToken = data.token; csrfHeader = data.headerName || 'X-CSRF-TOKEN';
  }).finally(() => { csrfPromise = null; });
  return csrfPromise;
}
export async function api<T>(path: string, options: { method?: string; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  const method = options.method || 'GET';
  if (method !== 'GET' && !csrfToken) await prepareCsrf();
  const headers: Record<string, string> = { Accept: 'application/json' };
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) body = options.body;
  else if (options.body !== undefined) { body = JSON.stringify(options.body); headers['Content-Type'] = 'application/json'; }
  if (method !== 'GET') headers[csrfHeader] = csrfToken;
  const response = await fetch(`/api${path}`, { method, headers, body, signal: options.signal, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let data: unknown = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { if (!response.ok) throw new ApiError('The server could not complete this request. Try again.', response.status); }
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/register') {
      clearCsrf(); window.dispatchEvent(new Event('tracksy-session-expired'));
    }
    const error = data as { message?: string; error?: string; detail?: string };
    throw new ApiError(error.message || error.detail || error.error || `Request failed (${response.status}).`, response.status);
  }
  return data as T;
}
export function clearCsrf() { csrfToken = ''; }
export function errorMessage(error: unknown) { return error instanceof Error ? error.message : 'Something went wrong. Try again.'; }
export function formValues(form: HTMLFormElement): Record<string, string> { return Object.fromEntries(Array.from(new FormData(form).entries()).filter((entry): entry is [string, string] => typeof entry[1] === 'string')); }
