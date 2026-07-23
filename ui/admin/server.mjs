import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { extname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const host = process.env.FIREFLY_ADMIN_UI_HOST ?? '127.0.0.1';
const port = Number.parseInt(process.env.FIREFLY_ADMIN_UI_PORT ?? '9720', 10);
const apiBase = normalizeApiBase(process.env.FIREFLY_ADMIN_API ?? 'http://127.0.0.1:9710');
const apiTimeoutMs = Number.parseInt(process.env.FIREFLY_ADMIN_API_TIMEOUT_MS ?? '5000', 10);
const apiToken = process.env.FIREFLY_ADMIN_API_TOKEN ?? '';
const sessionIdleTimeoutMs = durationMs(process.env.FIREFLY_ADMIN_SESSION_IDLE_TIMEOUT ?? '30m');
const sessionCookieSecure = (process.env.FIREFLY_ADMIN_SESSION_COOKIE_SECURE ?? 'false').toLowerCase() === 'true';
const sessionCookieName = 'firefly_admin_session';
const sessions = new Map();
const loginAttempts = new Map();
const publicDir = fileURLToPath(new URL('./public/', import.meta.url));
const startedAt = new Date().toISOString();
const staticFiles = new Map();

setInterval(cleanupExpiredState, 60_000).unref();

const contentTypes = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml; charset=utf-8'],
  ['.ico', 'image/x-icon']
]);

createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? '/', `http://${host}:${port}`);
    if (url.pathname === '/ui/auth/login') {
      await login(req, res);
      return;
    }
    if (url.pathname === '/ui/auth/session') {
      sessionStatus(req, res);
      return;
    }
    if (url.pathname === '/ui/auth/logout') {
      logout(req, res);
      return;
    }
    if (url.pathname === '/ui/config') {
      respondJson(res, 200, {
        service: 'firefly-admin-ui',
        host,
        port,
        apiBase,
        apiTimeoutMs,
        sessionIdleTimeoutSeconds: Math.floor(sessionIdleTimeoutMs / 1000),
        startedAt
      });
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      await proxyApi(req, res, url);
      return;
    }
    await serveStatic(req, url, res);
  } catch (error) {
    respondJson(res, 500, { error: 'admin_ui_error', message: String(error?.message ?? error) });
  }
}).listen(port, host, () => {
  console.log(`Firefly Admin UI: http://${host}:${port}`);
  console.log(`Proxying Admin API: ${apiBase}`);
  console.log(`Admin session idle timeout: ${Math.floor(sessionIdleTimeoutMs / 1000)}s`);
});

async function login(req, res) {
  if (req.method !== 'POST') {
    respondJson(res, 405, { error: 'method_not_allowed' });
    return;
  }
  const remote = req.socket.remoteAddress ?? 'unknown';
  if (loginBlocked(remote)) {
    respondJson(res, 429, { error: 'too_many_login_attempts' }, { 'Retry-After': '60' });
    return;
  }
  let credentials;
  try {
    credentials = JSON.parse((await readBody(req, 16 * 1024)).toString('utf8'));
  } catch {
    recordLoginFailure(remote);
    respondJson(res, 400, { error: 'invalid_login_request' });
    return;
  }
  const username = typeof credentials.username === 'string' ? credentials.username.trim() : '';
  const password = typeof credentials.password === 'string' ? credentials.password : '';
  if (!username || !password) {
    recordLoginFailure(remote);
    respondJson(res, 400, { error: 'credentials_required' });
    return;
  }
  try {
    const token = await requestAdminAccessToken(username, password);
    const now = Date.now();
    const session = {
      id: randomBytes(32).toString('base64url'),
      csrfToken: randomBytes(24).toString('base64url'),
      subject: username,
      accessToken: token.value,
      createdAt: now,
      lastAccessAt: now,
      expiresAt: now + token.expiresInSeconds * 1000
    };
    sessions.set(session.id, session);
    loginAttempts.delete(remote);
    respondJson(res, 200, sessionView(session), {
      'Set-Cookie': sessionCookie(session.id, Math.floor(token.expiresInSeconds))
    });
  } catch (error) {
    recordLoginFailure(remote);
    const status = error.status === 401 ? 401 : 502;
    respondJson(res, status, {
      error: status === 401 ? 'invalid_credentials' : 'authentication_service_unavailable'
    });
  }
}

function sessionStatus(req, res) {
  if (req.method !== 'GET') {
    respondJson(res, 405, { error: 'method_not_allowed' });
    return;
  }
  const session = authenticatedSession(req, res, false, true);
  if (!session) return;
  touchSession(session);
  respondJson(res, 200, sessionView(session), sessionResponseHeaders(session));
}

function logout(req, res) {
  if (req.method !== 'POST') {
    respondJson(res, 405, { error: 'method_not_allowed' });
    return;
  }
  const session = authenticatedSession(req, res, false);
  if (!session) return;
  if (req.headers['x-firefly-csrf'] !== session.csrfToken) {
    respondJson(res, 403, { error: 'csrf_validation_failed' });
    return;
  }
  invalidateSession(session.id);
  respondJson(res, 200, { status: 'logged_out' }, { 'Set-Cookie': clearSessionCookie() });
}

async function requestAdminAccessToken(username, password) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), apiTimeoutMs);
  try {
    const response = await fetch(new URL('/api/auth/login', `${apiBase}/`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ username, password }),
      signal: controller.signal
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || !body.accessToken) {
      const error = new Error('token request failed');
      error.status = response.status;
      throw error;
    }
    return {
      value: body.accessToken,
      expiresInSeconds: Math.max(1, Number(body.expiresIn ?? 60))
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function proxyApi(req, res, url) {
  const session = authenticatedSession(req, res, true);
  if (!session) return;
  if (!safeMethod(req.method) && req.headers['x-firefly-csrf'] !== session.csrfToken) {
    respondJson(res, 403, { error: 'csrf_validation_failed' });
    return;
  }
  const upstreamUrl = new URL(`${url.pathname}${url.search}`, `${apiBase}/`);
  const body = safeMethod(req.method) ? Buffer.alloc(0) : await readBody(req);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), apiTimeoutMs);

  try {
    const response = await fetch(upstreamUrl, {
      method: req.method,
      headers: proxyHeaders(req, session),
      body: body.length === 0 || req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
      signal: controller.signal
    });
    if (response.status === 401) invalidateSession(session.id);
    res.writeHead(response.status, securityHeaders({
      'Content-Type': response.headers.get('content-type') ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
      ...(response.headers.get('content-length') ? { 'Content-Length': response.headers.get('content-length') } : {}),
      ...sessionResponseHeaders(session),
      ...(response.status === 401 ? { 'Set-Cookie': clearSessionCookie() } : {})
    }));
    if (response.body) Readable.fromWeb(response.body).pipe(res);
    else res.end();
  } catch (error) {
    const code = error?.name === 'AbortError' ? 'admin_api_timeout' : 'admin_api_unreachable';
    respondJson(res, 502, {
      error: code,
      apiBase,
      message: String(error?.message ?? error)
    });
  } finally {
    clearTimeout(timeout);
  }
}

async function serveStatic(req, url, res) {
  const requestedPath = url.pathname === '/' ? '/index.html' : url.pathname;
  const file = safePublicPath(requestedPath);
  try {
    const resource = await staticResource(file);
    if (req.headers['if-none-match'] === resource.etag) {
      res.writeHead(304, securityHeaders({ ETag: resource.etag }));
      res.end();
      return;
    }
    res.writeHead(200, securityHeaders({
      'Content-Type': contentTypes.get(extname(file)) ?? 'application/octet-stream',
      'Cache-Control': extname(file) === '.html' ? 'no-store' : 'public, max-age=3600',
      ETag: resource.etag
    }));
    res.end(resource.content);
  } catch {
    const resource = await staticResource(resolve(publicDir, 'index.html'));
    res.writeHead(200, securityHeaders({
      'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store'
    }));
    res.end(resource.content);
  }
}

async function staticResource(file) {
  let resource = staticFiles.get(file);
  if (resource) return resource;
  const content = await readFile(file);
  resource = {
    content,
    etag: `"${createHash('sha256').update(content).digest('base64url')}"`
  };
  staticFiles.set(file, resource);
  return resource;
}

function safePublicPath(pathname) {
  const decoded = decodeURIComponent(pathname).replace(/^\/+/, '');
  const file = resolve(publicDir, decoded);
  const fromPublic = relative(publicDir, file);
  if (fromPublic.startsWith('..') || fromPublic.includes(`..${sep}`) || resolve(file) === resolve(publicDir)) {
    return resolve(publicDir, 'index.html');
  }
  return file;
}

function proxyHeaders(req, session) {
  const headers = {};
  for (const name of ['accept', 'content-type']) {
    const value = req.headers[name];
    if (value) {
      headers[name] = value;
    }
  }
  if (apiToken) {
    headers['X-Firefly-Token'] = apiToken;
  } else {
    headers.Authorization = `Bearer ${session.accessToken}`;
  }
  return headers;
}

function authenticatedSession(req, res, touch, allowAnonymous = false) {
  const id = cookies(req.headers.cookie ?? '')[sessionCookieName];
  const session = id ? sessions.get(id) : null;
  const now = Date.now();
  if (!session || session.expiresAt <= now || session.lastAccessAt + sessionIdleTimeoutMs <= now) {
    if (session) invalidateSession(session.id);
    respondJson(res, allowAnonymous ? 200 : 401,
      allowAnonymous ? { authenticated: false } : { error: 'ui_session_expired' },
      { 'Set-Cookie': clearSessionCookie() });
    return null;
  }
  if (touch) touchSession(session);
  return session;
}

function touchSession(session) {
  session.lastAccessAt = Date.now();
}

function sessionView(session) {
  return {
    authenticated: true,
    subject: session.subject,
    csrfToken: session.csrfToken,
    createdAt: new Date(session.createdAt).toISOString(),
    expiresAt: new Date(session.expiresAt).toISOString(),
    idleExpiresAt: new Date(Math.min(session.expiresAt, session.lastAccessAt + sessionIdleTimeoutMs)).toISOString(),
    idleTimeoutSeconds: Math.floor(sessionIdleTimeoutMs / 1000)
  };
}

function sessionResponseHeaders(session) {
  const view = sessionView(session);
  return {
    'X-Firefly-Session-Expires-At': view.expiresAt,
    'X-Firefly-Session-Idle-Expires-At': view.idleExpiresAt
  };
}

function invalidateSession(id) {
  sessions.delete(id);
}

function sessionCookie(id, maxAgeSeconds) {
  return `${sessionCookieName}=${id}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAgeSeconds}${sessionCookieSecure ? '; Secure' : ''}`;
}

function clearSessionCookie() {
  return `${sessionCookieName}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${sessionCookieSecure ? '; Secure' : ''}`;
}

function cookies(header) {
  return Object.fromEntries(header.split(';').map(value => value.trim()).filter(Boolean).map(value => {
    const separator = value.indexOf('=');
    return separator < 0 ? [value, ''] : [value.substring(0, separator), value.substring(separator + 1)];
  }));
}

function safeMethod(method = 'GET') {
  return method === 'GET' || method === 'HEAD' || method === 'OPTIONS';
}

function loginBlocked(remote) {
  const attempt = loginAttempts.get(remote);
  if (!attempt || attempt.resetAt <= Date.now()) return false;
  return attempt.count >= 10;
}

function recordLoginFailure(remote) {
  const now = Date.now();
  const current = loginAttempts.get(remote);
  loginAttempts.set(remote, !current || current.resetAt <= now
    ? { count: 1, resetAt: now + 5 * 60_000 }
    : { ...current, count: current.count + 1 });
}

function cleanupExpiredState() {
  const now = Date.now();
  for (const [id, session] of sessions) {
    if (session.expiresAt <= now || session.lastAccessAt + sessionIdleTimeoutMs <= now) sessions.delete(id);
  }
  for (const [remote, attempt] of loginAttempts) {
    if (attempt.resetAt <= now) loginAttempts.delete(remote);
  }
}

function normalizeApiBase(value) {
  return value.replace(/\/+$/, '');
}

function durationMs(value) {
  const match = /^(\d+)(ms|s|m|h)$/.exec(String(value).trim().toLowerCase());
  if (!match) throw new Error(`invalid duration: ${value}`);
  const multiplier = { ms: 1, s: 1000, m: 60_000, h: 3_600_000 }[match[2]];
  const duration = Number(match[1]) * multiplier;
  if (!Number.isSafeInteger(duration) || duration < 1000) throw new Error('session timeout must be at least 1s');
  return duration;
}

function readBody(req, maxBytes = 1024 * 1024) {
  return new Promise((resolveBody, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > maxBytes) {
        reject(new Error('request_body_too_large'));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolveBody(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function securityHeaders(headers = {}) {
  return {
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    ...headers
  };
}

function respondJson(res, status, body, headers = {}) {
  res.writeHead(status, securityHeaders({
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...headers
  }));
  res.end(JSON.stringify(body));
}
