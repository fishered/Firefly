import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const host = process.env.FIREFLY_ADMIN_UI_HOST ?? '127.0.0.1';
const port = Number.parseInt(process.env.FIREFLY_ADMIN_UI_PORT ?? '9720', 10);
const apiBase = normalizeApiBase(process.env.FIREFLY_ADMIN_API ?? 'http://127.0.0.1:9710');
const apiTimeoutMs = Number.parseInt(process.env.FIREFLY_ADMIN_API_TIMEOUT_MS ?? '5000', 10);
const apiToken = process.env.FIREFLY_ADMIN_API_TOKEN ?? '';
const publicDir = fileURLToPath(new URL('./public/', import.meta.url));
const startedAt = new Date().toISOString();

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
    if (url.pathname === '/ui/config') {
      respondJson(res, 200, {
        service: 'firefly-admin-ui',
        host,
        port,
        apiBase,
        apiTimeoutMs,
        startedAt
      });
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      await proxyApi(req, res, url);
      return;
    }
    await serveStatic(url, res);
  } catch (error) {
    respondJson(res, 500, { error: 'admin_ui_error', message: String(error?.message ?? error) });
  }
}).listen(port, host, () => {
  console.log(`Firefly Admin UI: http://${host}:${port}`);
  console.log(`Proxying Admin API: ${apiBase}`);
});

async function proxyApi(req, res, url) {
  const upstreamUrl = new URL(`${url.pathname}${url.search}`, `${apiBase}/`);
  const body = await readBody(req);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), apiTimeoutMs);

  try {
    const response = await fetch(upstreamUrl, {
      method: req.method,
      headers: proxyHeaders(req),
      body: body.length === 0 || req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
      signal: controller.signal
    });
    const responseBody = Buffer.from(await response.arrayBuffer());
    res.writeHead(response.status, {
      'Content-Type': response.headers.get('content-type') ?? 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    res.end(responseBody);
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

async function serveStatic(url, res) {
  const requestedPath = url.pathname === '/' ? '/index.html' : url.pathname;
  const file = safePublicPath(requestedPath);
  try {
    const content = await readFile(file);
    res.writeHead(200, {
      'Content-Type': contentTypes.get(extname(file)) ?? 'application/octet-stream',
      'Cache-Control': extname(file) === '.html' ? 'no-store' : 'public, max-age=300'
    });
    res.end(content);
  } catch {
    const content = await readFile(resolve(publicDir, 'index.html'));
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(content);
  }
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

function proxyHeaders(req) {
  const headers = {};
  for (const name of ['accept', 'content-type']) {
    const value = req.headers[name];
    if (value) {
      headers[name] = value;
    }
  }
  if (apiToken) {
    headers['X-Firefly-Token'] = apiToken;
  }
  return headers;
}

function normalizeApiBase(value) {
  return value.replace(/\/+$/, '');
}

function readBody(req) {
  return new Promise((resolveBody, reject) => {
    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => resolveBody(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function respondJson(res, status, body) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  res.end(JSON.stringify(body));
}
