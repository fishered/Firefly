import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';

test('supports sign-in, locale switching, executor lifecycle, and job execution controls', async ({ page }) => {
  const app = await startAdminUiFixture();
  try {
    await signIn(page, app.baseUrl);

    await page.locator('[data-locale="en-US"]').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en-US');
    await expect(page.locator('[data-view="jobs"]')).toContainText('Jobs');

    await page.locator('[data-view="executors"]').click();
    await page.locator('#page-actions button').click();
    await expect(page.locator('#executor-dialog')).toBeVisible();
    await page.locator('#executor-dialog [name="name"]').fill('billing-executor');
    await page.locator('#executor-dialog [name="description"]').fill('Billing service executor');
    await page.locator('#executor-dialog button[type="submit"]').click();
    await expect(page.locator('[data-delete-executor="billing-executor"]')).toBeVisible();
    page.once('dialog', dialog => dialog.accept());
    await page.locator('[data-delete-executor="billing-executor"]').click();
    await expect(page.locator('[data-delete-executor="billing-executor"]')).toHaveCount(0);

    await page.locator('[data-view="jobs"]').click();
    await page.locator('#page-actions button').click();
    await expect(page.locator('#job-dialog')).toBeVisible();
    await page.locator('#job-dialog [name="id"]').fill('billing-refresh');
    await page.locator('#job-dialog [name="name"]').fill('Billing refresh');
    await page.locator('#job-dialog [name="cron"]').fill('*/10 * * * * *');
    await page.locator('#job-dialog [name="zoneId"]').fill('UTC');
    await page.locator('#job-dialog button[type="submit"]').click();
    await expect(page.locator('[data-job-id="billing-refresh"][data-job-operation="trigger"]')).toBeVisible();

    page.once('dialog', dialog => dialog.accept());
    await page.locator('[data-job-id="billing-refresh"][data-job-operation="trigger"]').click();
    await page.locator('[data-job-id="billing-refresh"][data-job-operation="view"]').click();
    await page.locator('[data-execution-detail="exec-billing-refresh"]').click();
    await expect(page.locator('[data-cancel-execution="exec-billing-refresh"]')).toBeVisible();
    await page.locator('[data-cancel-execution="exec-billing-refresh"]').click();
    await page.locator('#cancel-dialog [name="reason"]').fill('e2e cancellation');
    await page.locator('#cancel-dialog button[type="submit"]').click();
    await expect(page.locator('[data-cancel-execution="exec-billing-refresh"]')).toHaveCount(0);
  } finally {
    await app.close();
  }
});

test('returns to sign-in when the UI session expires', async ({ page }) => {
  const app = await startAdminUiFixture({ sessionIdleTimeout: '1s' });
  try {
    await signIn(page, app.baseUrl);
    await expect(page.locator('body')).not.toHaveClass(/auth-required/);
    await page.waitForTimeout(1_250);
    await page.locator('[data-view="jobs"]').click();
    await expect(page.locator('#login-form')).toBeVisible();
  } finally {
    await app.close();
  }
});

async function signIn(page, baseUrl) {
  await page.goto(baseUrl);
  await expect(page.locator('#login-form')).toBeVisible();
  await page.locator('#login-form [name="username"]').fill('admin');
  await page.locator('#login-form [name="password"]').fill('admin');
  await page.locator('#login-form button[type="submit"]').click();
  await expect(page.locator('#login-form')).toHaveCount(0);
}

async function startAdminUiFixture(options = {}) {
  const mockApi = await startMockAdminApi();
  const uiPort = await freePort();
  const uiProcess = spawn(process.execPath, ['server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: {
      ...process.env,
      FIREFLY_ADMIN_UI_HOST: '127.0.0.1',
      FIREFLY_ADMIN_UI_PORT: String(uiPort),
      FIREFLY_ADMIN_API: mockApi.baseUrl,
      FIREFLY_ADMIN_SESSION_IDLE_TIMEOUT: options.sessionIdleTimeout ?? '10s'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  const output = [];
  uiProcess.stdout.on('data', chunk => output.push(chunk.toString()));
  uiProcess.stderr.on('data', chunk => output.push(chunk.toString()));
  await waitForHttp(`http://127.0.0.1:${uiPort}/ui/health`, uiProcess, output);
  return {
    baseUrl: `http://127.0.0.1:${uiPort}/`,
    api: mockApi,
    async close() {
      await closeChild(uiProcess);
      await mockApi.close();
    }
  };
}

async function startMockAdminApi() {
  const state = createMockState();
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? '/', 'http://127.0.0.1');
      const body = await readJson(request);
      await routeMockRequest(state, request.method ?? 'GET', url, body, response);
    } catch (error) {
      json(response, 500, { error: 'mock_api_error', message: String(error?.message ?? error) });
    }
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    state,
    close: () => new Promise(resolve => server.close(resolve))
  };
}

async function routeMockRequest(state, method, url, body, response) {
  if (method === 'GET' && url.pathname === '/api/auth/config') {
    json(response, 200, { enabled: true });
    return;
  }
  if (method === 'POST' && url.pathname === '/api/auth/login') {
    const valid = body.username === 'admin' && body.password === 'admin';
    json(response, valid ? 200 : 401, valid
      ? { accessToken: 'e2e-admin-token', expiresIn: 600, passwordChangeRequired: false }
      : { error: 'invalid_credentials' });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/overview') {
    json(response, 200, overview(state));
    return;
  }
  if (method === 'GET' && url.pathname === '/api/jobs') {
    json(response, 200, { jobs: state.jobs });
    return;
  }
  if (method === 'POST' && url.pathname === '/api/jobs') {
    state.jobs.push(normalizeJob(body));
    json(response, 201, { status: 'created' });
    return;
  }
  const triggerMatch = url.pathname.match(/^\/api\/jobs\/([^/]+)\/trigger$/);
  if (method === 'POST' && triggerMatch) {
    const jobId = decodeURIComponent(triggerMatch[1]);
    state.executions.unshift(runningExecution(jobId));
    json(response, 202, { status: 'triggered' });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/executors') {
    json(response, 200, {
      definitions: state.executorDefinitions,
      instances: state.executorInstances,
      heartbeatTimeoutSeconds: 30,
      serverTime: new Date().toISOString()
    });
    return;
  }
  if (method === 'POST' && url.pathname === '/api/executor-definitions') {
    const protocols = Array.isArray(body.protocols) ? body.protocols : [body.protocols ?? 'TCP'];
    state.executorDefinitions.push({
      name: body.name,
      description: body.description ?? '',
      protocols,
      enabled: true
    });
    json(response, 201, { status: 'created' });
    return;
  }
  const executorMatch = url.pathname.match(/^\/api\/executor-definitions\/([^/]+)$/);
  if (method === 'DELETE' && executorMatch) {
    const name = decodeURIComponent(executorMatch[1]);
    state.executorDefinitions = state.executorDefinitions.filter(item => item.name !== name);
    state.executorInstances = state.executorInstances.filter(item => item.executorName !== name);
    json(response, 200, { status: 'deleted' });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/executions') {
    json(response, 200, { executions: state.executions });
    return;
  }
  const executionDetailMatch = url.pathname.match(/^\/api\/executions\/([^/]+)$/);
  if (method === 'GET' && executionDetailMatch) {
    const executionId = decodeURIComponent(executionDetailMatch[1]);
    json(response, 200, state.executions.find(item => item.executionId === executionId) ?? { executionId });
    return;
  }
  const cancelMatch = url.pathname.match(/^\/api\/executions\/([^/]+)\/cancel$/);
  if (method === 'POST' && cancelMatch) {
    const executionId = decodeURIComponent(cancelMatch[1]);
    state.executions = state.executions.map(item => item.executionId === executionId
      ? { ...item, status: 'CANCELLED', endTime: new Date().toISOString(), lastError: body.reason ?? '' }
      : item);
    json(response, 200, { status: 'cancelled' });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/outbox/dead') {
    json(response, 200, { deadDispatches: [] });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/nodes') {
    json(response, 200, { nodes: state.nodes });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/plugins') {
    json(response, 200, { plugins: [] });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/users') {
    json(response, 200, { users: state.users });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/integration-key') {
    json(response, 200, { configured: true, version: 1, updatedAt: new Date().toISOString() });
    return;
  }
  if (method === 'GET' && url.pathname === '/api/schedules/timezones') {
    const query = url.searchParams.get('query') ?? '';
    const timezones = ['UTC', 'Asia/Shanghai', 'America/New_York'].filter(item => item.includes(query) || query === '');
    json(response, 200, { timezones });
    return;
  }
  if (method === 'POST' && url.pathname === '/api/schedules/preview') {
    json(response, 200, {
      cron: body.cron,
      nextFireTimes: [1, 2, 3, 4, 5].map(offset => ({
        instant: new Date(Date.now() + offset * 10_000).toISOString(),
        local: `+${offset * 10}s`
      }))
    });
    return;
  }
  json(response, 404, { error: 'not_found', path: url.pathname });
}

function createMockState() {
  const now = new Date();
  const startedAt = new Date(now.getTime() - 60_000).toISOString();
  return {
    startedAt,
    jobs: [normalizeJob({ id: 'sample-job', name: 'Sample job' })],
    executorDefinitions: [{
      name: 'order-executor',
      description: 'Order service executor',
      protocols: ['TCP'],
      enabled: true
    }],
    executorInstances: [{
      executorName: 'order-executor',
      instanceId: 'order-executor-1',
      serviceName: 'order-service',
      handlers: ['com.firefly.OrderJob#run'],
      gatewayNodeId: 'gateway-1',
      host: '127.0.0.1',
      port: 1883,
      lastHeartbeatAt: now.toISOString(),
      heartbeatAgeSeconds: 1,
      status: 'ONLINE'
    }],
    executions: [{
      executionId: 'exec-sample-job',
      jobId: 'sample-job',
      status: 'SUCCEEDED',
      scheduledFireTime: new Date(now.getTime() - 20_000).toISOString(),
      dispatchTime: new Date(now.getTime() - 19_500).toISOString(),
      startTime: new Date(now.getTime() - 19_000).toISOString(),
      endTime: new Date(now.getTime() - 18_000).toISOString()
    }],
    nodes: [{
      nodeId: 'scheduler-1',
      roles: ['SCHEDULER', 'GATEWAY'],
      status: 'ONLINE',
      mode: 'CLUSTER',
      shards: [0, 1],
      leaseUntil: new Date(now.getTime() + 30_000).toISOString(),
      fencingToken: '1'
    }],
    users: [{
      username: 'admin',
      roles: ['ADMIN'],
      enabled: true,
      version: 1,
      createdAt: startedAt,
      updatedAt: startedAt
    }]
  };
}

function normalizeJob(job) {
  return {
    id: job.id,
    name: job.name ?? job.id,
    groupName: job.groupName ?? 'default',
    executorName: job.executorName ?? 'order-executor',
    handlerName: job.handlerName ?? 'com.firefly.OrderJob#run',
    businessHandlerName: job.handlerName ?? 'com.firefly.OrderJob#run',
    schedule: job.cron ?? job.schedule ?? '*/5 * * * * *',
    zoneId: job.zoneId ?? 'UTC',
    dispatchMode: job.dispatchMode ?? 'UNICAST',
    routingStrategy: job.routingStrategy ?? 'ROUND_ROBIN',
    completionPolicy: job.completionPolicy ?? 'ALL_SUCCESS',
    retryScope: job.retryScope ?? 'FAILED_TARGETS_ONLY',
    shardCount: Number(job.shardCount ?? 1),
    routingKey: job.routingKey ?? '',
    enabled: true,
    nextFireTime: new Date(Date.now() + 30_000).toISOString(),
    lastResult: '-'
  };
}

function runningExecution(jobId) {
  return {
    executionId: `exec-${jobId}`,
    jobId,
    status: 'RUNNING',
    scheduledFireTime: new Date().toISOString(),
    dispatchTime: new Date().toISOString(),
    startTime: new Date().toISOString(),
    targets: [{
      targetId: `${jobId}-target-0`,
      status: 'RUNNING',
      attempts: 1
    }]
  };
}

function overview(state) {
  const jobsEnabled = state.jobs.filter(job => job.enabled !== false).length;
  return {
    status: 'UP',
    startedAt: state.startedAt,
    jobsTotal: state.jobs.length,
    jobsEnabled,
    jobsDisabled: state.jobs.length - jobsEnabled,
    nodesOnline: state.nodes.length,
    executorsOnline: state.executorInstances.filter(item => item.status === 'ONLINE').length
  };
}

async function readJson(request) {
  if (request.method === 'GET' || request.method === 'HEAD') return {};
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  return raw ? JSON.parse(raw) : {};
}

function json(response, status, body) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  response.end(JSON.stringify(body));
}

async function freePort() {
  const server = createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address();
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function waitForHttp(url, process, output) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < 10_000) {
    if (process.exitCode !== null) {
      throw new Error(`Admin UI exited before it became healthy:\n${output.join('')}`);
    }
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  }
  throw new Error(`Timed out waiting for Admin UI:\n${output.join('')}`);
}

async function closeChild(child) {
  if (child.exitCode !== null) return;
  child.kill();
  await Promise.race([
    once(child, 'exit'),
    new Promise(resolve => setTimeout(resolve, 2_000))
  ]);
  if (child.exitCode === null) child.kill('SIGKILL');
}
