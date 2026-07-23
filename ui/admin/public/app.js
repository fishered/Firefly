const views = {
  overview: { title: '总览' },
  jobs: { title: '任务管理', action: 'new-job' },
  executors: { title: '执行器', action: 'new-executor' },
  executions: { title: '执行记录', action: 'refresh' },
  nodes: { title: '节点与集群', badge: 'Cluster Mode', action: 'refresh' },
  plugins: { title: '插件' },
  settings: { title: '账号与安全', action: 'new-user' }
};

const sampleJobs = [
  { id: 'job-001', name: '每日订单对账', groupName: 'finance', schedule: '0 0 2 * * ?', zoneId: 'Asia/Shanghai', nextFireTime: '2026-07-09T02:00:00Z', executorName: 'order-executor', businessHandlerName: 'reconciliationHandler', enabled: true, lastResult: '成功' },
  { id: 'job-002', name: '每小时用户统计', groupName: 'user', schedule: '0 0 * * * ?', zoneId: 'Asia/Shanghai', nextFireTime: '2026-07-09T15:00:00Z', executorName: 'user-executor', businessHandlerName: 'userStatisticsHandler', enabled: true, lastResult: '失败' },
  { id: 'job-003', name: '每日推送报告', groupName: 'notification', schedule: '0 30 9 * * ?', zoneId: 'America/New_York', nextFireTime: '2026-07-09T09:30:00Z', executorName: 'notification-executor', businessHandlerName: 'dailyReportHandler', enabled: false, lastResult: '成功' },
  { id: 'job-004', name: '30秒心跳检测', groupName: 'monitor', schedule: 'fixed-rate:30000', zoneId: 'UTC', nextFireTime: '2026-07-09T07:30:00Z', executorName: 'monitor-executor', businessHandlerName: 'heartbeatHandler', enabled: false, status: '暂停', lastResult: '成功' }
];

const sampleNodes = [
  { nodeId: 'node-a', roles: ['SCHEDULER', 'API'], mode: 'Cluster', lastHeartbeatAt: '2024-05-20T14:32:18', shards: ['shard-0', 'shard-2', 'shard-4'], leaseUntil: '2024-05-20T14:33:18', fencingToken: '7a2f9d3e...', status: '在线' },
  { nodeId: 'node-b', roles: ['SCHEDULER', 'GATEWAY'], mode: 'Cluster', lastHeartbeatAt: '2024-05-20T14:32:17', shards: ['shard-1', 'shard-3', 'shard-5'], leaseUntil: '2024-05-20T14:33:17', fencingToken: '8b3e4f2a...', status: '在线' },
  { nodeId: 'node-c', roles: ['SCHEDULER', 'API'], mode: 'Cluster', lastHeartbeatAt: '2024-05-20T14:32:19', shards: ['shard-6', 'shard-7'], leaseUntil: '2024-05-20T14:33:19', fencingToken: '9c4d5g1b...', status: '在线' }
];

const sampleExecutions = [
  ['exec-001287', 'job-00042', '2024-05-20 10:30:00', '2024-05-20 10:30:00', '2024-05-20 10:30:01', '2024-05-20 10:30:05', '4s', 'order-executor@instance-1', 'SUCCEEDED'],
  ['exec-001286', 'job-00078', '2024-05-20 10:25:00', '2024-05-20 10:25:00', '2024-05-20 10:25:01', '2024-05-20 10:25:12', '11s', 'user-executor@instance-2', 'FAILED'],
  ['exec-001285', 'job-00023', '2024-05-20 10:20:00', '2024-05-20 10:20:00', '2024-05-20 10:20:01', '-', '运行中', 'notification-executor@instance-1', 'RUNNING'],
  ['exec-001284', 'job-00056', '2024-05-20 10:15:00', '2024-05-20 10:15:02', '-', '-', '-', '-', 'MISFIRED'],
  ['exec-001283', 'job-00091', '2024-05-20 10:10:00', '2024-05-20 10:10:00', '2024-05-20 10:10:01', '2024-05-20 10:15:02', '5m 1s', 'data-executor@instance-3', 'TIMEOUT']
];

const state = {
  currentView: 'overview',
  config: null,
  session: null,
  overview: null,
  jobs: sampleJobs,
  executions: [],
  deadDispatches: [],
  executors: [],
  executorDefinitions: [],
  executorHeartbeatTimeoutSeconds: 30,
  executorServerTime: null,
  nodes: sampleNodes,
  users: [],
  integrationKey: null,
  plugins: []
};

const viewLoadedAt = new Map();
const pendingRequests = new Map();
const VIEW_CACHE_MS = 5000;

const jobFieldHelp = {
  executorName: ['执行器是承载任务的逻辑服务池，可由一个或多个服务实例共同注册。'],
  handlerName: [
    '执行入口由 Starter 根据“完整类名#方法名”自动注册，普通情况下不需要手工维护。',
    '任务表单只展示自动绑定的入口，不提供手工选择。'
  ],
  dispatchMode: [
    'UNICAST：选择一个在线实例，任务只执行一次。',
    'BROADCAST：每个在线实例各执行一次。',
    'SHARDING：拆成指定数量的逻辑分片，每个分片执行一次。'
  ],
  routingStrategy: [
    'ROUND_ROBIN：按顺序轮换实例。',
    'RANDOM：每次随机选择实例。',
    'CONSISTENT_HASH：相同路由键尽量落到同一实例。'
  ],
  completionPolicy: [
    'ALL_SUCCESS：所有目标成功才算成功。',
    'ANY_SUCCESS：任意一个目标成功即算成功。',
    'QUORUM：超过半数目标成功即算成功。'
  ],
  retryScope: [
    'FAILED_TARGETS_ONLY：只重试失败、超时或缺失的目标。',
    'ALL_TARGETS：包括已成功目标在内全部重跑，业务必须幂等。'
  ],
  shardCount: ['仅 SHARDING 生效。例如分片数为 8，会产生 0～7 共 8 个子执行，并向处理器传入分片索引和总数。'],
  routingKey: ['一致性哈希使用它稳定选择实例；分片模式使用“路由键 + 分片索引”分配各分片。留空时每次执行使用 executionId。']
};

const executorFieldHelp = {
  protocols: [
    'TCP：通过 Netty 长连接注册、心跳和接收任务，是当前远程执行器使用的协议。',
    'HTTP：领域模型已预留，但当前尚未实现完整的任务传输。',
    'EMBEDDED：用于与 Scheduler 同进程运行的处理器，由代码注册，不通过 Admin 页面手动创建。'
  ]
};

document.querySelectorAll('.nav').forEach(button => {
  button.addEventListener('click', () => setView(button.dataset.view));
});
document.querySelectorAll('[data-logout]').forEach(button => button.addEventListener('click', logout));
document.querySelectorAll('[data-locale]').forEach(button => {
  button.addEventListener('click', () => window.FireflyI18n.setLocale(button.dataset.locale));
});
document.addEventListener('firefly:locale-change', () => {
  const view = state.currentView || 'overview';
  document.getElementById('page-title').textContent = views[view].title;
  renderActions(view);
  if (state.session) {
    renderView(view);
    updateSessionSummary();
  } else if (document.body.classList.contains('auth-required')) {
    showLogin();
  }
  window.FireflyI18n.localize(document.body);
});

let sessionTimer = null;

bootstrap();

async function bootstrap() {
  await loadUiConfig();
  if (!await restoreSession()) {
    showLogin();
    return;
  }
  await activateConsole();
}

async function restoreSession() {
  try {
    const response = await fetch('/ui/auth/session', { headers: { Accept: 'application/json' } });
    if (!response.ok) return false;
    const session = await response.json();
    if (!session.authenticated) return false;
    state.session = session;
    return true;
  } catch {
    return false;
  }
}

async function activateConsole() {
  document.body.classList.remove('auth-pending', 'auth-required');
  document.getElementById('modal-root').innerHTML = '';
  updateSessionSummary();
  clearInterval(sessionTimer);
  sessionTimer = setInterval(updateSessionSummary, 1000);
  await setView(state.currentView ?? 'overview');
}

function showLogin(message = '') {
  state.session = null;
  clearInterval(sessionTimer);
  document.body.classList.remove('auth-pending');
  document.body.classList.add('auth-required');
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <main class="login-screen">
      <section class="login-panel" aria-labelledby="login-title">
        <div class="login-brand"><img class="brand-logo" src="/firefly-mark.svg" alt=""><strong>Firefly</strong></div>
        <h1 id="login-title">登录管理控制台</h1>
        <form id="login-form">
          <label class="field">用户名<input name="username" value="admin" autocomplete="username" required autofocus></label>
          <label class="field">密码<input type="password" name="password" autocomplete="current-password" required></label>
          <div class="login-error" role="alert">${escapeHtml(message)}</div>
          <button class="btn primary login-submit" type="submit">登录</button>
        </form>
      </section>
    </main>`;
  root.querySelector('#login-form').addEventListener('submit', submitLogin);
}

async function submitLogin(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector('button[type="submit"]');
  const error = form.querySelector('.login-error');
  const credentials = Object.fromEntries(new FormData(form).entries());
  submit.disabled = true;
  error.textContent = '';
  try {
    const response = await fetch('/ui/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(credentials)
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(loginError(body.error));
    state.session = body;
    await activateConsole();
  } catch (failure) {
    error.textContent = failure.message;
  } finally {
    submit.disabled = false;
  }
}

async function logout() {
  const csrfToken = state.session?.csrfToken;
  try {
    await fetch('/ui/auth/logout', {
      method: 'POST',
      headers: csrfToken ? { 'X-Firefly-CSRF': csrfToken } : {}
    });
  } finally {
    showLogin();
  }
}

function loginError(code) {
  return {
    invalid_credentials: '用户名或密码错误',
    too_many_login_attempts: '登录失败次数过多，请稍后重试',
    authentication_service_unavailable: '认证服务暂时不可用',
    credentials_required: '请输入用户名和密码'
  }[code] ?? '登录失败';
}

function updateSessionSummary() {
  if (!state.session) return;
  const absolute = new Date(state.session.expiresAt).getTime();
  const idle = new Date(state.session.idleExpiresAt).getTime();
  const remaining = Math.min(absolute, idle) - Date.now();
  if (remaining <= 0) {
    showLogin('会话已过期，请重新登录');
    return;
  }
  document.getElementById('session-subject').textContent = state.session.subject;
  document.getElementById('session-expiry').textContent = `会话剩余 ${formatRemaining(remaining)}`;
}

function formatRemaining(milliseconds) {
  const totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

async function setView(view) {
  state.currentView = view;
  document.querySelectorAll('.nav').forEach(button => {
    button.classList.toggle('active', button.dataset.view === view);
  });
  document.getElementById('page-title').textContent = views[view].title;
  const badge = document.getElementById('page-badge');
  badge.textContent = views[view].badge ?? '';
  badge.classList.toggle('hidden', !views[view].badge);
  renderActions(view);
  renderView(view);
  await refreshView(false, false);
}

async function refreshView(notify = true, force = true) {
  await loadViewData(state.currentView, force);
  renderView(state.currentView);
  if (notify) toast('已刷新');
}

function renderActions(view) {
  const actions = document.getElementById('page-actions');
  if (views[view].action === 'new-job') {
    actions.innerHTML = `<button class="btn primary" type="button"><span>＋</span>新建任务</button>`;
    actions.querySelector('button').addEventListener('click', openJobDialog);
    return;
  }
  if (views[view].action === 'new-executor') {
    actions.innerHTML = `<button class="btn primary" type="button"><span>+</span>新建执行器</button>`;
    actions.querySelector('button').addEventListener('click', openExecutorDialog);
    return;
  }
  if (views[view].action === 'refresh') {
    actions.innerHTML = `<button id="refresh-page" class="btn" type="button"><span>⟳</span>刷新</button>`;
    document.getElementById('refresh-page').addEventListener('click', () => refreshView(true));
    return;
  }
  if (views[view].action === 'new-user') {
    actions.innerHTML = `<button class="btn primary" type="button"><span>+</span>新建账号</button>`;
    actions.querySelector('button').addEventListener('click', () => openUserDialog());
    return;
  }
  actions.innerHTML = '';
}

async function loadViewData(view, force) {
  const loadedAt = viewLoadedAt.get(view) ?? 0;
  if (!force && Date.now() - loadedAt < VIEW_CACHE_MS) return;
  const loaders = {
    overview: [loadOverview],
    jobs: [loadJobs, loadExecutors],
    executors: [loadExecutors],
    executions: [loadExecutions, loadDeadDispatches],
    nodes: [loadNodes],
    plugins: [loadPlugins],
    settings: [loadUsers, loadIntegrationKey]
  }[view] ?? [];
  await Promise.allSettled(loaders.map(loader => loader()));
  viewLoadedAt.set(view, Date.now());
}

async function loadUiConfig() {
  try {
    state.config = await request('/ui/config');
  } catch {
    state.config = null;
  }
}

async function loadOverview() {
  try {
    state.overview = await api('/api/overview');
  } catch {
    state.overview = null;
  }
}

async function loadJobs() {
  try {
    const data = await api('/api/jobs');
    const jobs = normalizeList(data, 'jobs');
    state.jobs = jobs.length ? jobs.map(normalizeJob) : sampleJobs;
  } catch {
    state.jobs = sampleJobs;
  }
}

async function loadExecutors() {
  try {
    const data = await api('/api/executors');
    state.executorDefinitions = normalizeList(data, 'definitions');
    state.executors = normalizeList(data, 'instances');
    if (!state.executors.length) state.executors = normalizeList(data, 'executors');
    state.executorHeartbeatTimeoutSeconds = Number(data.heartbeatTimeoutSeconds ?? 30);
    state.executorServerTime = data.serverTime ?? null;
  } catch {
    state.executors = [];
    state.executorDefinitions = [];
  }
}

async function loadExecutions() {
  try {
    const data = await api('/api/executions');
    const executions = normalizeList(data, 'executions');
    state.executions = state.executionFilterJobId
      ? executions.filter(item => item.jobId === state.executionFilterJobId)
      : executions;
  } catch {
    state.executions = sampleExecutionObjects();
  }
}

async function loadDeadDispatches() {
  try {
    const data = await api('/api/outbox/dead');
    state.deadDispatches = normalizeList(data, 'deadDispatches');
  } catch {
    state.deadDispatches = [];
  }
}

async function loadNodes() {
  try {
    const data = await api('/api/nodes');
    const nodes = normalizeList(data, 'nodes');
    state.nodes = nodes.length ? nodes.map(normalizeNode) : sampleNodes;
  } catch {
    state.nodes = sampleNodes;
  }
}

async function loadUsers() {
  try {
    const data = await api('/api/users');
    state.users = normalizeList(data, 'users');
  } catch {
    state.users = [];
  }
}

async function loadIntegrationKey() {
  try {
    state.integrationKey = await api('/api/integration-key');
  } catch {
    state.integrationKey = null;
  }
}

async function loadPlugins() {
  try {
    const data = await api('/api/plugins');
    state.plugins = normalizeList(data, 'plugins');
  } catch {
    state.plugins = [];
  }
}

function renderView(view) {
  const root = document.getElementById('view-root');
  if (view === 'overview') root.innerHTML = overviewPage();
  if (view === 'jobs') root.innerHTML = jobsPage();
  if (view === 'executors') root.innerHTML = executorsPage();
  if (view === 'executions') root.innerHTML = executionsPage();
  if (view === 'nodes') root.innerHTML = nodesPage();
  if (view === 'plugins') root.innerHTML = pluginsPage();
  if (view === 'settings') root.innerHTML = usersPage();
  bindViewActions(view);
}

function legacyBindViewActions(view) {
  if (view === 'executions') {
    document.querySelectorAll('[data-execution-detail]').forEach(button => {
      button.addEventListener('click', () => openExecutionDetail(button.dataset.executionDetail));
    });
    document.querySelectorAll('[data-outbox-requeue]').forEach(button => {
      button.addEventListener('click', () => requeueOutbox(button.dataset.outboxRequeue));
    });
  }
  if (view === 'executors') {
    document.querySelectorAll('[data-isolate-executor]').forEach(button => {
      button.addEventListener('click', () => isolateExecutor(button.dataset.isolateExecutor));
    });
    document.querySelectorAll('[data-executor-instances]').forEach(button => {
      button.addEventListener('click', () => openExecutorInstancesDialog(button.dataset.executorInstances));
    });
    bindExecutorInstanceDetailActions();
  }
  if (view === 'nodes') {
    document.querySelectorAll('[data-node-operation]').forEach(button => {
      button.addEventListener('click', () => updateNode(button.dataset.nodeId, button.dataset.nodeOperation));
    });
  }
}

function overviewPage() {
  const overview = state.overview ?? {};
  const jobsTotal = Number(overview.jobsTotal ?? 92);
  const jobsEnabled = Number(overview.jobsEnabled ?? (state.jobs.filter(job => job.enabled !== false).length || 86));
  const nodesOnline = Number(overview.nodesOnline ?? (state.nodes.length || 3));
  const executorsOnline = Number(overview.executorsOnline ?? (state.executors.length || 12));
  return `
    <section class="card system-card">
      <h2 class="section-title">系统信息</h2>
      <div class="divider"></div>
      <div class="system-grid">
        ${metaBlock('调度模式', tag('cluster'))}
        ${metaBlock('当前节点角色', `${tag('SCHEDULER', 'primary')} ${tag('API')}`)}
        ${metaBlock('调度器状态', `<span class="success"><span class="dot"></span>运行正常</span>`)}
        ${metaBlock('系统运行时间', '12天 6小时 32分钟')}
      </div>
    </section>

    <section class="stats-grid">
      ${statCard('在线执行器', executorsOnline, '/ 12', '全部在线', '▤', 'primary')}
      ${statCard('启用任务数', jobsEnabled, `/ ${jobsTotal}`, '6个任务已禁用', '●', 'yellow', 'warning')}
      ${statCard('未来1分钟待触发', 17, '个任务', '调度队列正常', '◔', 'gray')}
      ${statCard('最近失败数', 3, '次/24h', '需要关注', '▲', 'red', 'danger')}
    </section>

    <section class="grid-2">
      <article class="chart-card">
        <h2 class="chart-title">▰ 调度延迟统计</h2>
        <div class="divider"></div>
        <div class="line-chart">${lineChart()}</div>
      </article>
      <article class="chart-card">
        <h2 class="chart-title">◔ 执行状态分布</h2>
        <div class="divider"></div>
        <div class="donut-wrap">${statusDonut()}</div>
      </article>
    </section>

    <section class="grid-2">
      <article class="chart-card">
        <h2 class="chart-title">◉ 核心运行指标</h2>
        <div class="divider"></div>
        <div class="metrics-list">
          ${metricRow('平均调度延迟', '12ms')}
          ${metricRow('最近1小时触发任务数', '1247')}
          ${metricRow('最近1小时失败任务数', '0')}
          ${metricRow('任务成功率', '<span class="success">99.7%</span>')}
          ${metricRow('平均执行时长', '247ms')}
        </div>
      </article>
      <article class="chart-card">
        <h2 class="chart-title">✣ 已加载插件</h2>
        <div class="divider"></div>
        <div class="metrics-list">
          ${(state.plugins.length ? state.plugins : [
            { id: 'admin-http', status: 'ACTIVE' }, { id: 'metrics-prometheus', status: 'ACTIVE' }
          ]).map(plugin => pluginRow(plugin.id, plugin.status === 'ACTIVE')).join('')}
        </div>
      </article>
    </section>
  `;
}

function legacyJobsPage() {
  const rows = state.jobs.slice(0, 4).map(job => tableRow([
    code(job.id),
    text(job.name ?? job.id),
    text(job.groupName ?? job.groupId ?? '-'),
    code(job.schedule ?? '-'),
    text(job.zoneId ?? 'Asia/Shanghai'),
    text(formatDate(job.nextFireTime)),
    text(job.executorName ?? '-'),
    text(job.dispatchMode ?? 'UNICAST'),
    text(job.businessHandlerName ?? job.handlerName ?? '-'),
    statusText(job.enabled === false ? '禁用' : (job.status ?? '启用'), job.enabled === false ? 'muted' : 'success'),
    statusText(job.lastResult ?? '成功', job.lastResult === '失败' ? 'danger' : 'success'),
    `<span class="action-icons">◱ ⏸ ◆ ◔ ↶ 🗑</span>`
  ])).join('');
  return `
    <section class="toolbar">
      <label class="inline-field">任务名称 <input placeholder="搜索任务名称"></label>
      <label class="inline-field">执行器 <select><option>全部执行器</option></select></label>
      <label class="inline-field">状态 <select><option>全部状态</option></select></label>
      <button class="btn" type="button">🔍 查询</button>
      <button class="btn" type="button">⟳ 重置</button>
    </section>
    <section class="table-card">
      <div class="table-scroll">
        <table>
          <thead><tr>${headers(['Job ID','名称','分组','调度表达式','时区','下次触发时间','执行器','分发模式','处理器','状态','上次执行结果','操作'])}</tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      ${tableFooter('显示 1-4 共 24 条记录', '6')}
    </section>
  `;
}

function executorsPage() {
  const executors = state.executors;
  const online = executors.filter(item => item.status === 'ONLINE');
  const offline = executors.filter(item => item.status !== 'ONLINE');
  const averageHeartbeatAge = online.length
    ? Math.round(online.reduce((sum, item) => sum + Number(item.heartbeatAgeSeconds ?? 0), 0) / online.length)
    : 0;
  const definitions = state.executorDefinitions ?? [];
  return `
    <section class="summary-strip">
      ${statCard('逻辑执行器', definitions.length, '个', '已注册定义', '▤', 'primary')}
      ${statCard('在线实例', online.length, '个', `${offline.length} 个离线记录`, '●', 'yellow', offline.length ? 'warning' : 'success')}
      ${statCard('平均心跳年龄', averageHeartbeatAge, 's', `离线阈值 ${state.executorHeartbeatTimeoutSeconds}s`, '◔', 'gray')}
      ${statCard('实例记录', executors.length, '个', '在线与离线状态', '▦', 'red', 'success')}
    </section>
    <section class="table-card">
      <div class="table-title">执行器定义</div>
      <div class="table-scroll">
        <table>
          <thead><tr>${headers(['执行器名称', '说明', '协议', '可用处理器', '状态', '已绑定实例', '操作'])}</tr></thead>
          <tbody>${definitions.map(definition => {
            const bound = executors.filter(item => item.executorName === definition.name);
            const onlineCount = bound.filter(item => item.status === 'ONLINE').length;
            const offlineCount = bound.length - onlineCount;
            return tableRow([
              code(definition.name),
              text(definition.description || '-'),
              text(Array.isArray(definition.protocols) ? definition.protocols.join(', ') : definition.protocols || '-'),
              text(handlerNamesForExecutor(definition.name).join(', ') || '-'),
              statusText(definition.enabled === false ? 'DISABLED' : 'ENABLED', definition.enabled === false ? 'muted' : 'success'),
              `<button class="link-button" type="button" data-executor-instances="${escapeHtml(definition.name)}">${onlineCount} 在线${offlineCount ? ` / ${offlineCount} 离线` : ''}</button>`,
              `<div class="job-actions">
                <button class="link-button" type="button" data-executor-instances="${escapeHtml(definition.name)}">查看实例</button>
                ${definition.enabled === false ? '' : `<button class="link-button danger" type="button" data-isolate-executor="${escapeHtml(definition.name)}">隔离</button>`}
                <button class="icon-button danger" type="button" title="删除执行器定义" aria-label="删除执行器定义" data-delete-executor="${escapeHtml(definition.name)}">&#10005;</button>
              </div>`
            ]);
          }).join('') || emptyRow(7, '暂无执行器定义')}</tbody>
        </table>
      </div>
    </section>
    <section class="table-card">
      <div class="table-title">执行器实例</div>
      <div class="table-scroll">
        <table>
          <thead><tr>${headers(['执行器','实例','服务','处理器','Gateway','地址','心跳年龄','状态','操作'])}</tr></thead>
          <tbody>${executors.map(item => tableRow([
            text(item.executorName),
            code(item.instanceId),
            text(item.serviceName),
            text((item.handlers ?? []).join(', ') || '-'),
            text(item.gatewayNodeId || '-'),
            text(`${item.host ?? '-'}:${item.port ?? '-'}`),
            text(`${Number(item.heartbeatAgeSeconds ?? 0)}s`),
            statusText(item.status ?? 'OFFLINE', item.status === 'ONLINE' ? 'success' : 'muted'),
            `<button class="link-button" type="button" data-executor-instance-detail="${escapeHtml(item.instanceId)}" data-executor-name="${escapeHtml(item.executorName)}">查看</button>`
          ])).join('') || emptyRow(9, '暂无执行器实例')}</tbody>
        </table>
      </div>
    </section>
  `;
}

function openExecutorInstancesDialog(executorName) {
  const instances = state.executors.filter(item => item.executorName === executorName);
  const rows = instances.map(item => tableRow([
    code(item.instanceId),
    text(item.serviceName),
    text(item.gatewayNodeId || '-'),
    text(`${item.host ?? '-'}:${item.port ?? '-'}`),
    text(formatDate(item.lastHeartbeatAt)),
    text(`${Number(item.heartbeatAgeSeconds ?? 0)}s`),
    statusText(item.status ?? 'OFFLINE', item.status === 'ONLINE' ? 'success' : 'muted'),
    `<button class="link-button" type="button" data-executor-instance-detail="${escapeHtml(item.instanceId)}" data-executor-name="${escapeHtml(item.executorName)}">详情</button>`
  ])).join('');
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="executor-instances-title">
      <section class="modal executor-instances-modal">
        <div class="modal-header">
          <div><h2 id="executor-instances-title">已绑定实例</h2><div class="modal-subtitle code">${escapeHtml(executorName)}</div></div>
          <button class="btn icon" type="button" data-close aria-label="关闭">×</button>
        </div>
        <div class="modal-body execution-body">
          <div class="table-scroll target-table"><table>
            <thead><tr>${headers(['实例','服务','Gateway','地址','最后心跳','心跳年龄','状态','操作'])}</tr></thead>
            <tbody>${rows || emptyRow(8, '当前没有实例记录')}</tbody>
          </table></div>
        </div>
        <div class="modal-footer"><button class="btn" type="button" data-close>关闭</button></div>
      </section>
    </div>`;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  bindExecutorInstanceDetailActions(root);
}

function openExecutorInstanceDetail(executorName, instanceId) {
  const instance = state.executors.find(item =>
    item.executorName === executorName && item.instanceId === instanceId
  );
  if (!instance) {
    toast('实例记录不存在或已经清理');
    return;
  }
  const metadata = Object.entries(instance.metadata ?? {})
    .map(([key, value]) => `${key}=${value}`)
    .join(', ') || '-';
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="executor-instance-title">
      <section class="modal executor-instance-modal">
        <div class="modal-header">
          <div><h2 id="executor-instance-title">执行器实例详情</h2><div class="modal-subtitle code">${escapeHtml(instance.instanceId)}</div></div>
          <button class="btn icon" type="button" data-close aria-label="关闭">×</button>
        </div>
        <div class="modal-body execution-body">
          <div class="detail-grid executor-detail-grid">
            ${detailItem('执行器', instance.executorName)}
            ${detailItem('状态', statusText(instance.status ?? 'OFFLINE', instance.status === 'ONLINE' ? 'success' : 'muted'), true)}
            ${detailItem('服务', instance.serviceName)}
            ${detailItem('实例 ID', instance.instanceId)}
            ${detailItem('会话 ID', instance.sessionId)}
            ${detailItem('Gateway 节点', instance.gatewayNodeId)}
            ${detailItem('远端地址', `${instance.host ?? '-'}:${instance.port ?? '-'}`)}
            ${detailItem('协议', instance.protocol)}
            ${detailItem('注册时间', formatDate(instance.registeredAt))}
            ${detailItem('最后心跳', formatDate(instance.lastHeartbeatAt))}
            ${detailItem('心跳年龄', `${Number(instance.heartbeatAgeSeconds ?? 0)}s`)}
            ${detailItem('离线阈值', `${state.executorHeartbeatTimeoutSeconds}s`)}
            ${detailItem('元数据', metadata)}
          </div>
        </div>
        <div class="modal-footer"><button class="btn" type="button" data-close>关闭</button></div>
      </section>
    </div>`;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
}

function bindExecutorInstanceDetailActions(root = document) {
  root.querySelectorAll('[data-executor-instance-detail]').forEach(button => {
    button.addEventListener('click', () => openExecutorInstanceDetail(
      button.dataset.executorName, button.dataset.executorInstanceDetail
    ));
  });
}

function executionsPage() {
  const rows = state.executions.slice(0, 8).map(item => tableRow([
    code(item.executionId),
    code(item.jobId),
    text(formatDate(item.scheduledFireTime)),
    text(formatDate(item.dispatchTime)),
    text(formatDate(item.startTime)),
    text(formatDate(item.endTime)),
    text(item.duration ?? '-'),
    text(item.executorInstance ?? '-'),
    executionTag(item.status ?? 'SCHEDULED'),
    `<button class="link-button" type="button" data-execution-detail="${escapeHtml(item.executionId)}">查看</button>`
  ])).join('');
  const deadRows = state.deadDispatches.map(item => tableRow([
    code(item.outboxId),
    code(item.jobId),
    text(item.dispatchType),
    text(item.attempt),
    text(formatDate(item.availableAt)),
    text(item.lastError || '-'),
    `<button class="link-button danger" type="button" data-outbox-requeue="${escapeHtml(item.outboxId)}">重新入队</button>`
  ])).join('');
  return `
    <section class="toolbar">
      <label class="field">任务ID <input placeholder="输入任务ID"></label>
      <label class="field">执行器名称 <select><option>全部执行器</option></select></label>
      <label class="field">执行状态 <select><option>全部状态</option></select></label>
      <label class="field">时间范围 <span class="inline-field"><input type="date"><span>~</span><input type="date"></span></label>
      <button class="btn primary" type="button">🔍 查询</button>
      <button class="btn" type="button">◆ 重置</button>
    </section>
    <section class="stats-grid">
      ${statCard('总执行数', '12,847', '', '', '▤', 'primary')}
      ${statCard('成功执行', '12,362', '', '', '●', 'primary', 'success')}
      ${statCard('失败执行', 217, '', '', '×', 'red', 'danger')}
      ${statCard('运行中', 26, '', '', '◌', 'gray')}
    </section>
    <section class="table-card">
      <div class="table-scroll">
        <table>
          <thead><tr>${headers(['执行ID','任务ID','调度时间','分发时间','开始时间','结束时间','耗时','执行器实例','状态','操作'])}</tr></thead>
          <tbody>${rows || emptyRow(10, '暂无执行记录')}</tbody>
        </table>
      </div>
      ${tableFooter(`显示 1-${Math.min(state.executions.length, 8)} 条，共 ${state.executions.length} 条记录`, Math.max(1, Math.ceil(state.executions.length / 8)))}
    </section>
    <section class="table-card">
      <div class="table-title">派发死信 <span class="count-badge">${state.deadDispatches.length}</span></div>
      <div class="table-scroll">
        <table class="compact-table">
          <thead><tr>${headers(['Outbox ID','任务ID','类型','尝试次数','可用时间','最后错误','操作'])}</tr></thead>
          <tbody>${deadRows || emptyRow(7, '当前没有派发死信')}</tbody>
        </table>
      </div>
    </section>
  `;
}

async function openExecutionDetail(executionId) {
  try {
    const detail = await api(`/api/executions/${encodeURIComponent(executionId)}`);
    const execution = detail.execution ?? detail;
    const targets = normalizeList(detail, 'targets');
    const relatedAttempts = state.executions
      .filter(item => item.rootExecutionId && item.rootExecutionId === execution.rootExecutionId)
      .sort((left, right) => Number(left.runAttempt ?? 0) - Number(right.runAttempt ?? 0));
    const targetRows = targets.map(target => tableRow([
      code(target.targetExecutionId),
      text(target.instanceId || '-'),
      text(target.gatewayNodeId || '-'),
      text(target.shardIndex ?? '-'),
      executionTag(target.status),
      text(formatDate(target.acknowledgedAt)),
      text(formatDate(target.completedAt)),
      text(target.errorMessage || '-')
    ])).join('');
    const root = document.getElementById('modal-root');
    root.innerHTML = `
      <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="execution-dialog-title">
        <section class="modal execution-modal">
          <div class="modal-header">
            <div><h2 id="execution-dialog-title">执行详情</h2><div class="modal-subtitle code">${escapeHtml(execution.executionId)}</div></div>
            <button class="btn icon" type="button" data-close>×</button>
          </div>
          <div class="modal-body execution-body">
            <div class="detail-grid">
              ${detailItem('任务', execution.jobId)}
              ${detailItem('状态', executionTag(execution.status), true)}
              ${detailItem('分发模式', execution.dispatchMode)}
              ${detailItem('完成策略', execution.completionPolicy)}
              ${detailItem('计划时间', formatDate(execution.scheduledFireTime))}
              ${detailItem('超时截止', formatDate(execution.timeoutAt))}
            </div>
            <div class="attempt-track">
              ${(relatedAttempts.length ? relatedAttempts : [execution]).map(item => `
                <div class="attempt-node ${item.executionId === execution.executionId ? 'active' : ''}">
                  <span>Attempt ${Number(item.runAttempt ?? 0) + 1}</span>${executionTag(item.status)}
                </div>`).join('')}
            </div>
            <div class="detail-section-title">目标执行</div>
            <div class="table-scroll target-table"><table>
              <thead><tr>${headers(['目标ID','实例','Gateway','分片','状态','ACK','完成','错误'])}</tr></thead>
              <tbody>${targetRows || emptyRow(8, '尚未生成目标')}</tbody>
            </table></div>
          </div>
          <div class="modal-footer">
            <button class="btn" type="button" data-close>关闭</button>
            ${isActiveExecution(execution.status) ? `<button class="btn danger-button" type="button" data-cancel-execution="${escapeHtml(execution.executionId)}">终止执行</button>` : ''}
          </div>
        </section>
      </div>`;
    root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
    root.querySelector('[data-cancel-execution]')?.addEventListener('click', () => openCancelDialog(execution.executionId));
  } catch (error) {
    toast(error.message);
  }
}

function openCancelDialog(executionId) {
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="cancel-dialog-title">
      <form id="cancel-dialog" class="modal compact-modal">
        <div class="modal-header"><h2 id="cancel-dialog-title">终止执行</h2><button class="btn icon" type="button" data-close>×</button></div>
        <div class="modal-body">
          <div class="operation-warning">终止会停止后续重投，并向仍在线的 Executor 发送协作式取消请求。</div>
          ${dialogField('原因', 'reason', 'operator requested cancellation', true)}
        </div>
        <div class="modal-footer"><button class="btn" type="button" data-close>返回</button><button class="btn danger-button" type="submit">确认终止</button></div>
      </form>
    </div>`;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  root.querySelector('form').addEventListener('submit', event => cancelExecution(event, executionId));
}

async function cancelExecution(event, executionId) {
  event.preventDefault();
  const reason = new FormData(event.currentTarget).get('reason');
  try {
    await api(`/api/executions/${encodeURIComponent(executionId)}/cancel`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason })
    });
    closeDialog();
    await refreshView(false);
    toast(`执行已终止：${executionId}`);
  } catch (error) { toast(error.message); }
}

async function requeueOutbox(outboxId) {
  try {
    await api(`/api/outbox/${encodeURIComponent(outboxId)}/requeue`, { method: 'POST' });
    await refreshView(false);
    toast(`死信已重新入队：${outboxId}`);
  } catch (error) { toast(error.message); }
}

async function isolateExecutor(executorName) {
  try {
    await api(`/api/executor-definitions/${encodeURIComponent(executorName)}/isolate`, { method: 'POST' });
    await refreshView(false);
    toast(`执行器已隔离：${executorName}`);
  } catch (error) { toast(error.message); }
}

async function deleteExecutor(executorName) {
  const warning = `确认删除执行器定义“${executorName}”？\n\n仍有任务、任务组或在线实例时会拒绝删除。若服务端允许自动创建，业务服务重新注册后可能再次创建同名定义。`;
  if (!window.confirm(warning)) return;
  try {
    await api(`/api/executor-definitions/${encodeURIComponent(executorName)}`, { method: 'DELETE' });
    await refreshView(false);
    toast(`执行器已删除：${executorName}`);
  } catch (error) {
    toast(error.message);
  }
}

async function updateNode(nodeId, operation) {
  try {
    await api(`/api/nodes/${encodeURIComponent(nodeId)}/${operation}`, { method: 'POST' });
    await refreshView(false);
    toast(`节点操作已提交：${nodeId}`);
    if (operation === 'drain') void pollNodeDrain(nodeId);
  } catch (error) { toast(error.message); }
}

async function pollNodeDrain(nodeId) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    try {
      const status = await api(`/api/nodes/${encodeURIComponent(nodeId)}/drain-status`);
      await loadNodes();
      if (state.currentView === 'nodes') renderView('nodes');
      if (status.status === 'OFFLINE') {
        toast(`节点排空完成：${nodeId}`);
        return;
      }
    } catch (error) {
      return;
    }
  }
}

function isActiveExecution(status) {
  return ['DISPATCHING', 'DISPATCHED', 'RUNNING'].includes(status);
}

function detailItem(label, value, html = false) {
  return `<div class="detail-item"><span>${escapeHtml(label)}</span><strong>${html ? value : escapeHtml(value ?? '-')}</strong></div>`;
}

function emptyRow(columns, label) {
  return `<tr><td class="empty-cell" colspan="${columns}">${escapeHtml(label)}</td></tr>`;
}

function nodesPage() {
  return `
    <section class="summary-strip">
      ${statCard('集群模式', 'Cluster', '', '', '▤', 'primary')}
      ${statCard('在线节点', `${state.nodes.length} / 3`, '', '', '●', 'primary', 'success')}
      ${statCard('总分片数', 8, '', '', '▦', 'gray')}
      ${statCard('调度健康度', '100%', '', '', '❤', 'primary', 'success')}
    </section>
    <section class="table-card">
      <div class="table-title">节点列表</div>
      <div class="table-scroll">
        <table>
          <thead><tr>${headers(['节点ID','角色','模式','最后心跳','所属分片','租约到期','隔离令牌','状态','操作'])}</tr></thead>
          <tbody>${state.nodes.map(node => tableRow([
            text(node.nodeId),
            normalizeRoles(node.roles).split(', ').map(role => tag(role, role === 'SCHEDULER' ? 'primary' : '')).join(' '),
            text(node.mode ?? 'Cluster'),
            text(formatDate(node.lastHeartbeatAt)),
            (node.shards ?? ['shard-0']).map(shard => `<span class="shard-tag">${escapeHtml(shard)}</span>`).join(' '),
            text(node.leaseUntil ?? formatDate(new Date(Date.now() + 60000).toISOString())),
            text(node.fencingToken ?? '7a2f9d3e...'),
            statusText(node.status ?? '在线', node.status === 'DRAINING' ? 'warning' : 'success'),
            `<button class="link-button" type="button" data-node-operation="drain" data-node-id="${escapeHtml(node.nodeId)}">排空</button> <button class="link-button danger" type="button" data-node-operation="offline" data-node-id="${escapeHtml(node.nodeId)}">下线</button>`
          ])).join('')}</tbody>
        </table>
      </div>
    </section>
    <section class="chart-card">
      <h2 class="chart-title">分片分布</h2>
      <div class="node-donut-wrap">${nodeDonut()}</div>
    </section>
  `;
}

function placeholderPage(text) {
  return `<section class="table-card placeholder">${escapeHtml(text)}</section>`;
}

function metaBlock(label, value) {
  return `<div><div class="meta-label">${escapeHtml(label)}</div><div>${value}</div></div>`;
}

function statCard(label, value, suffix, note, icon, iconTone = 'primary', noteTone = 'success') {
  return `
    <article class="card">
      <div class="card-label">${escapeHtml(label)}</div>
      <div class="card-value">${escapeHtml(value)}${suffix ? `<small>${escapeHtml(suffix)}</small>` : ''}</div>
      ${note ? `<div class="card-note ${noteTone}"><span class="dot"></span>${escapeHtml(note)}</div>` : ''}
      <div class="card-icon ${iconTone}">${escapeHtml(icon)}</div>
    </article>
  `;
}

function metricRow(label, value) {
  return `<div class="metric-row"><span>${escapeHtml(label)}</span><strong>${value}</strong></div>`;
}

function pluginRow(name, enabled) {
  return `<div class="plugin-row"><span class="plugin-name"><span class="muted">♜</span>${escapeHtml(name)}</span><span class="${enabled ? 'success' : 'muted'}"><span class="dot"></span>${enabled ? 'enabled' : 'disabled'}</span></div>`;
}

function pluginsPage() {
  const active = state.plugins.filter(plugin => plugin.status === 'ACTIVE').length;
  const external = state.plugins.filter(plugin => plugin.source === 'EXTERNAL').length;
  const rows = state.plugins.map(plugin => tableRow([
    `<div class="plugin-identity"><span class="plugin-glyph">✣</span><div><strong>${escapeHtml(plugin.displayName || plugin.id)}</strong><small>${escapeHtml(plugin.description || plugin.id)}</small></div></div>`,
    code(plugin.id),
    code(plugin.version || 'development'),
    statusText(plugin.source || 'CLASSPATH', plugin.source === 'EXTERNAL' ? 'warning' : 'muted'),
    `<span class="implementation-name" title="${escapeHtml(plugin.implementationClass || '')}">${escapeHtml(plugin.implementationClass || '-')}</span>`,
    statusText(plugin.status || 'LOADED', plugin.status === 'ACTIVE' ? 'success' : 'muted')
  ])).join('');
  return `
    <section class="summary-strip plugin-summary">
      ${statCard('已加载插件', state.plugins.length, '个', '当前节点运行时', '✣', 'primary')}
      ${statCard('运行中', active, '个', '生命周期已启动', '●', 'yellow', active === state.plugins.length ? 'success' : 'warning')}
      ${statCard('外部插件', external, '个', '从插件目录发现', '↗', 'gray', 'muted')}
    </section>
    <section class="table-card plugin-registry">
      <div class="table-toolbar"><div><strong>插件注册表</strong><span class="table-count">共 ${state.plugins.length} 条</span></div><span class="muted">插件随节点启动和停止</span></div>
      <div class="table-scroll"><table>
        <thead><tr>${headers(['插件', '插件 ID', '版本', '来源', '实现类', '状态'])}</tr></thead>
        <tbody>${rows || emptyRow(6, '当前节点没有加载插件')}</tbody>
      </table></div>
    </section>`;
}

function headers(items) {
  return items.map(item => `<th>${escapeHtml(item)}</th>`).join('');
}

function tableRow(values) {
  return `<tr>${values.map(value => `<td>${value}</td>`).join('')}</tr>`;
}

function tableFooter(label, last) {
  return `
    <div class="table-footer">
      <span>${escapeHtml(label)}</span>
      <div class="pagination">
        <span class="page-btn">‹</span><span class="page-btn active">1</span><span class="page-btn">2</span><span class="page-btn">3</span><span>...</span><span class="page-btn">${escapeHtml(last)}</span><span class="page-btn">›</span>
      </div>
    </div>
  `;
}

function tag(label, tone = '') {
  return `<span class="tag ${tone}">${escapeHtml(label)}</span>`;
}

function code(value) {
  return `<span class="code">${escapeHtml(value)}</span>`;
}

function text(value) {
  return escapeHtml(value ?? '');
}

function statusText(label, tone) {
  return `<span class="${tone}"><span class="dot"></span>${escapeHtml(label)}</span>`;
}

function executionTag(status) {
  const tone = { SUCCEEDED: 'ok', SCHEDULED: 'run', DISPATCHING: 'run', DISPATCHED: 'run', DISABLED: 'mis', FAILED: 'bad', RUNNING: 'run', MISFIRED: 'mis', TIMEOUT: 'timeout', CANCELLED: 'cancelled' }[status] ?? 'ok';
  return `<span class="status-tag ${tone}">${escapeHtml(status)}</span>`;
}

function openJobDialog() {
  const executorName = defaultExecutorName();
  const handlers = handlerNamesForExecutor(executorName);
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="job-dialog-title">
      <form id="job-dialog" class="modal">
        <div class="modal-header">
          <h2 id="job-dialog-title">新建任务</h2>
          <button class="btn icon" type="button" data-close>×</button>
        </div>
        <div class="modal-body">
          ${dialogField('任务 ID', 'id', 'remote-example-job', true)}
          ${dialogField('任务名称', 'name', 'remote-example-job', false)}
          ${executorSelect(executorName)}
          ${handlerSelect(executorName, handlers.length === 1 ? handlers[0] : '')}
          ${dialogSelect('分发模式', 'dispatchMode', ['UNICAST', 'BROADCAST', 'SHARDING'], jobFieldHelp.dispatchMode)}
          ${dialogSelect('路由策略', 'routingStrategy', ['ROUND_ROBIN', 'RANDOM', 'CONSISTENT_HASH'], jobFieldHelp.routingStrategy)}
          ${dialogSelect('完成策略', 'completionPolicy', ['ALL_SUCCESS', 'ANY_SUCCESS', 'QUORUM'], jobFieldHelp.completionPolicy)}
          ${dialogSelect('重试范围', 'retryScope', ['FAILED_TARGETS_ONLY', 'ALL_TARGETS'], jobFieldHelp.retryScope)}
          ${dialogField('分片数', 'shardCount', '1', true, 'number', jobFieldHelp.shardCount, 'min="1" max="4096"')}
          ${dialogField('路由键', 'routingKey', '', false, 'text', jobFieldHelp.routingKey)}
          ${scheduleEditorFields('*/5 * * * * *', 'Asia/Shanghai')}
        </div>
        <div class="modal-footer">
          <button class="btn" type="button" data-close>取消</button>
          <button class="btn primary" type="submit">创建任务</button>
        </div>
      </form>
    </div>
  `;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  const form = root.querySelector('#job-dialog');
  bindJobDestinationFields(form);
  bindJobPolicyFields(form);
  bindScheduleEditor(form);
  form.addEventListener('submit', createJob);
}

function openExecutorDialog() {
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="executor-dialog-title">
      <form id="executor-dialog" class="modal">
        <div class="modal-header"><h2 id="executor-dialog-title">新建执行器</h2><button class="btn icon" type="button" data-close>×</button></div>
        <div class="modal-body">
          ${dialogField('执行器名称', 'name', 'order-executor', true)}
          ${dialogField('说明', 'description', '订单服务执行器', false)}
          ${executorProtocolSelect()}
        </div>
        <div class="modal-footer"><button class="btn" type="button" data-close>取消</button><button class="btn primary" type="submit">创建执行器</button></div>
      </form>
    </div>
  `;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  root.querySelector('#executor-dialog').addEventListener('submit', createExecutor);
}

function dialogField(label, name, value, required, type = 'text', help = null, attributes = '') {
  return `<label class="field" data-field="${escapeHtml(name)}">${fieldLabel(label, help)}<input type="${escapeHtml(type)}" name="${escapeHtml(name)}" value="${escapeHtml(value)}" ${required ? 'required' : ''} ${attributes}></label>`;
}

function dialogSelect(label, name, options, help = null) {
  return `<label class="field" data-field="${escapeHtml(name)}">${fieldLabel(label, help)}<select name="${escapeHtml(name)}">${options.map(option => `<option value="${escapeHtml(option)}">${escapeHtml(option)}</option>`).join('')}</select></label>`;
}

function executorProtocolSelect() {
  const options = [
    { value: 'TCP', label: 'TCP（Netty，当前支持）', disabled: false },
    { value: 'HTTP', label: 'HTTP（尚未实现）', disabled: true },
    { value: 'EMBEDDED', label: 'EMBEDDED（由进程内注册）', disabled: true }
  ];
  return `<label class="field" data-field="protocols">${fieldLabel('协议', executorFieldHelp.protocols)}<select name="protocols" required>${options.map(option => `<option value="${escapeHtml(option.value)}" ${option.disabled ? 'disabled' : ''}>${escapeHtml(option.label)}</option>`).join('')}</select></label>`;
}

function fieldLabel(label, help) {
  const tooltip = help?.length
    ? `<span class="help-tooltip" tabindex="0" aria-label="${escapeHtml(label)}说明">?<span class="help-tooltip-content" role="tooltip">${help.map(line => `<span>${escapeHtml(line)}</span>`).join('')}</span></span>`
    : '';
  return `<span class="field-label">${escapeHtml(label)}${tooltip}</span>`;
}

function defaultExecutorName() {
  return state.executorDefinitions.find(definition => definition.enabled !== false)?.name ?? '';
}

function handlerNamesForExecutor(executorName) {
  if (!executorName) return [];
  const instances = state.executors.filter(instance => instance.executorName === executorName);
  const online = instances.filter(instance => instance.status === 'ONLINE');
  const candidates = online.length ? online : instances;
  if (!candidates.length) return [];
  const sets = candidates.map(instance => new Set(instance.handlers ?? []));
  return [...sets[0]].filter(handler => sets.every(set => set.has(handler))).sort();
}

function executorSelect(value = '') {
  const definitions = state.executorDefinitions.filter(definition => definition.enabled !== false);
  const options = definitions.map(definition =>
    `<option value="${escapeHtml(definition.name)}" ${definition.name === value ? 'selected' : ''}>${escapeHtml(definition.name)}</option>`
  ).join('');
  return `<label class="field" data-field="executorName">${fieldLabel('执行器', jobFieldHelp.executorName)}<select name="executorName" required>${options || '<option value="">暂无可用执行器</option>'}</select></label>`;
}

function handlerSelect(executorName, value = '') {
  const handlers = handlerNamesForExecutor(executorName);
  const selected = value || handlers[0] || '';
  const display = selected || '执行器尚未上报可用入口';
  return `<label class="field" data-field="handlerName">${fieldLabel('执行入口', jobFieldHelp.handlerName)}<span class="readonly-field" data-handler-display>${escapeHtml(display)}</span><input type="hidden" name="handlerName" value="${escapeHtml(selected)}"></label>`;
}

function bindJobDestinationFields(form) {
  const executor = form.elements.executorName;
  const handler = form.elements.handlerName;
  const display = form.querySelector('[data-handler-display]');
  executor?.addEventListener('change', () => {
    const handlers = handlerNamesForExecutor(executor.value);
    handler.value = handlers[0] || '';
    display.textContent = handler.value || '执行器尚未上报可用入口';
  });
}

function bindJobPolicyFields(form) {
  const dispatch = form.elements.dispatchMode;
  const routing = form.elements.routingStrategy;
  const completion = form.elements.completionPolicy;
  const shardCount = form.elements.shardCount;
  const routingKey = form.elements.routingKey;
  const sync = () => {
    const sharding = dispatch.value === 'SHARDING';
    const targeted = dispatch.value !== 'BROADCAST';
    const keyed = sharding || routing.value === 'CONSISTENT_HASH';
    shardCount.disabled = !sharding;
    if (!sharding) shardCount.value = '1';
    routing.disabled = !targeted;
    completion.disabled = dispatch.value === 'UNICAST';
    if (completion.disabled) completion.value = 'ALL_SUCCESS';
    routingKey.disabled = !keyed;
    form.querySelector('[data-field="shardCount"]')?.classList.toggle('context-muted', !sharding);
    form.querySelector('[data-field="routingStrategy"]')?.classList.toggle('context-muted', !targeted);
    form.querySelector('[data-field="completionPolicy"]')?.classList.toggle('context-muted', dispatch.value === 'UNICAST');
    form.querySelector('[data-field="routingKey"]')?.classList.toggle('context-muted', !keyed);
  };
  dispatch.addEventListener('change', sync);
  routing.addEventListener('change', sync);
  sync();
}

function scheduleEditorFields(cron, zoneId) {
  return `
    <label class="field schedule-cron-field">Cron 表达式
      <span class="field-input-action">
        <input name="cron" value="${escapeHtml(cron)}" required autocomplete="off">
        <button class="btn field-action" type="button" data-cron-builder-toggle aria-expanded="false">选择</button>
      </span>
    </label>
    <label class="field timezone-field">时区
      <span class="combobox-input">
        <input name="zoneId" value="${escapeHtml(zoneId)}" required autocomplete="off" role="combobox" aria-autocomplete="list" aria-expanded="false">
        <button type="button" data-timezone-toggle title="展开时区" aria-label="展开时区" tabindex="-1">⌄</button>
      </span>
      <div class="timezone-options" role="listbox"></div>
    </label>
    <div class="cron-builder" hidden>
      <div class="cron-builder-header"><strong>调度规则</strong><button type="button" class="icon-button" data-cron-builder-close title="收起" aria-label="收起">×</button></div>
      <div class="cron-mode-tabs" role="tablist" aria-label="Cron 调度方式">
        ${[
          ['seconds', '每 N 秒'], ['minutes', '每 N 分钟'], ['hourly', '每小时'],
          ['daily', '每天'], ['weekly', '每周'], ['monthly', '每月'], ['custom', '自定义']
        ].map(([mode, label]) => `<button type="button" role="tab" data-cron-mode="${mode}">${label}</button>`).join('')}
      </div>
      <div class="cron-builder-controls"></div>
      <div class="cron-generated"><span>生成结果</span><code data-cron-generated></code></div>
    </div>
    <div class="schedule-preview" aria-live="polite"><span class="muted">正在解析计划...</span></div>`;
}

function bindScheduleEditor(form) {
  const cron = form.querySelector('[name="cron"]');
  const zone = form.querySelector('[name="zoneId"]');
  const options = form.querySelector('.timezone-options');
  const timezoneToggle = form.querySelector('[data-timezone-toggle]');
  const cronBuilder = form.querySelector('.cron-builder');
  const cronBuilderToggle = form.querySelector('[data-cron-builder-toggle]');
  let previewTimer;
  let zoneTimer;
  let zoneRequest = 0;
  let zoneValues = [];
  let activeZoneIndex = -1;

  const preview = () => {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(() => refreshSchedulePreview(form), 220);
  };
  const closeTimezoneOptions = () => {
    options.classList.remove('open');
    zone.setAttribute('aria-expanded', 'false');
    activeZoneIndex = -1;
  };
  const selectZone = value => {
    zone.value = value;
    zone.setCustomValidity('');
    delete form.dataset.timezoneValidated;
    closeTimezoneOptions();
    preview();
  };
  const renderZones = () => {
    options.innerHTML = zoneValues.map((value, index) => `<button type="button" role="option" aria-selected="${index === activeZoneIndex}" class="${index === activeZoneIndex ? 'active' : ''}" data-timezone-value="${escapeHtml(value)}">${escapeHtml(value)}</button>`).join('');
    options.classList.toggle('open', zoneValues.length > 0);
    zone.setAttribute('aria-expanded', options.classList.contains('open') ? 'true' : 'false');
    options.querySelectorAll('[data-timezone-value]').forEach(button => button.addEventListener('mousedown', event => {
      event.preventDefault();
      selectZone(button.dataset.timezoneValue);
    }));
    options.querySelector('.active')?.scrollIntoView({ block: 'nearest' });
  };
  const searchZones = (query = zone.value.trim()) => {
    clearTimeout(zoneTimer);
    zone.setCustomValidity('');
    const requestId = ++zoneRequest;
    zoneValues = [];
    activeZoneIndex = -1;
    options.innerHTML = '<div class="combobox-loading">正在匹配时区...</div>';
    options.classList.add('open');
    zone.setAttribute('aria-expanded', 'true');
    zoneTimer = setTimeout(async () => {
      try {
        const data = await api(`/api/schedules/timezones?query=${encodeURIComponent(query)}`);
        if (requestId !== zoneRequest) return;
        zoneValues = normalizeList(data, 'timezones').slice(0, 12);
        activeZoneIndex = -1;
        renderZones();
      } catch (error) {
        if (requestId !== zoneRequest) return;
        zoneValues = [];
        options.innerHTML = `<div class="combobox-error">${escapeHtml(scheduleApiError(error))}</div>`;
        options.classList.add('open');
        zone.setAttribute('aria-expanded', 'true');
      }
    }, 160);
  };

  cron.addEventListener('input', preview);
  cronBuilderToggle.addEventListener('click', () => {
    const opening = cronBuilder.hidden;
    cronBuilder.hidden = !opening;
    cronBuilderToggle.setAttribute('aria-expanded', String(opening));
    if (opening) renderCronBuilder(form, inferCronMode(cron.value), preview);
  });
  form.querySelector('[data-cron-builder-close]').addEventListener('click', () => {
    cronBuilder.hidden = true;
    cronBuilderToggle.setAttribute('aria-expanded', 'false');
  });
  form.querySelectorAll('[data-cron-mode]').forEach(button => button.addEventListener('click', () => {
    renderCronBuilder(form, button.dataset.cronMode, preview);
  }));
  zone.addEventListener('input', () => {
    delete form.dataset.timezoneValidated;
    searchZones(zone.value.trim());
    preview();
  });
  zone.addEventListener('focus', () => searchZones(zone.value.trim()));
  timezoneToggle.addEventListener('click', () => {
    if (options.classList.contains('open')) closeTimezoneOptions();
    else { zone.focus(); searchZones(''); }
  });
  zone.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      closeTimezoneOptions();
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Enter'].includes(event.key)) return;
    if (!options.classList.contains('open')) {
      if (event.key !== 'Enter') searchZones(zone.value.trim());
      return;
    }
    event.preventDefault();
    if (event.key === 'ArrowDown') activeZoneIndex = Math.min(zoneValues.length - 1, activeZoneIndex + 1);
    if (event.key === 'ArrowUp') activeZoneIndex = Math.max(0, activeZoneIndex < 0 ? 0 : activeZoneIndex - 1);
    if (event.key === 'Enter' && activeZoneIndex >= 0) {
      selectZone(zoneValues[activeZoneIndex]);
      return;
    }
    renderZones();
  });
  zone.addEventListener('blur', () => setTimeout(closeTimezoneOptions, 120));
  form.addEventListener('submit', async event => {
    if (form.dataset.timezoneValidated === zone.value) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    try {
      const data = await api(`/api/schedules/timezones?query=${encodeURIComponent(zone.value.trim())}`);
      if (!normalizeList(data, 'timezones').includes(zone.value.trim())) {
        zone.setCustomValidity('请选择有效时区');
        zone.reportValidity();
        return;
      }
      zone.setCustomValidity('');
      form.dataset.timezoneValidated = zone.value;
      form.requestSubmit();
    } catch (error) {
      toast(scheduleApiError(error));
    }
  }, true);
  refreshSchedulePreview(form);
}

function inferCronMode(expression) {
  const fields = expression.trim().split(/\s+/);
  if (fields.length !== 6) return 'custom';
  if (/^\*\/\d+$/.test(fields[0]) && fields.slice(1).every(field => field === '*')) return 'seconds';
  if (fields[0] === '0' && /^\*\/\d+$/.test(fields[1]) && fields.slice(2).every(field => field === '*')) return 'minutes';
  if (/^\d+$/.test(fields[0]) && /^\d+$/.test(fields[1]) && fields.slice(2).every(field => field === '*')) return 'hourly';
  if (fields.slice(0, 3).every(field => /^\d+$/.test(field)) && fields[3] === '*' && fields[4] === '*') {
    return fields[5] === '*' ? 'daily' : 'weekly';
  }
  if (fields.slice(0, 4).every(field => /^\d+$/.test(field)) && fields[4] === '*' && fields[5] === '*') return 'monthly';
  return 'custom';
}

function renderCronBuilder(form, mode, preview) {
  const cron = form.querySelector('[name="cron"]');
  const controls = form.querySelector('.cron-builder-controls');
  form.querySelectorAll('[data-cron-mode]').forEach(button => {
    const active = button.dataset.cronMode === mode;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
  });
  controls.innerHTML = cronBuilderControls(mode, cron.value);
  const apply = () => {
    cron.value = cronExpressionFromBuilder(mode, controls, cron.value);
    form.querySelector('[data-cron-generated]').textContent = cron.value;
    preview();
  };
  controls.querySelectorAll('input, select').forEach(control => control.addEventListener('input', apply));
  apply();
}

function cronBuilderControls(mode, expression) {
  const fields = expression.trim().split(/\s+/);
  const secondsStep = fields[0]?.match(/^\*\/(\d+)$/)?.[1] ?? '5';
  const minutesStep = fields[1]?.match(/^\*\/(\d+)$/)?.[1] ?? '5';
  const second = /^\d+$/.test(fields[0] ?? '') ? fields[0] : '0';
  const minute = /^\d+$/.test(fields[1] ?? '') ? fields[1] : '0';
  const hour = /^\d+$/.test(fields[2] ?? '') ? fields[2] : '0';
  const time = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`;
  if (mode === 'seconds') return cronNumberControl('间隔秒数', 'step', secondsStep, 1, 59);
  if (mode === 'minutes') return cronNumberControl('间隔分钟', 'step', minutesStep, 1, 59);
  if (mode === 'hourly') return `${cronNumberControl('第几分钟', 'minute', minute, 0, 59)}${cronNumberControl('秒', 'second', second, 0, 59)}`;
  if (mode === 'daily') return cronTimeControl(time);
  if (mode === 'weekly') return `${cronSelectControl('星期', 'weekday', ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'], fields[5] === '*' ? 'MON' : fields[5])}${cronTimeControl(time)}`;
  if (mode === 'monthly') return `${cronNumberControl('日期', 'day', /^\d+$/.test(fields[3] ?? '') ? fields[3] : '1', 1, 31)}${cronTimeControl(time)}`;
  const values = fields.length === 6 ? fields : ['0', '*', '*', '*', '*', '*'];
  return ['秒', '分', '时', '日', '月', '星期'].map((label, index) => `<label>${label}<input name="field${index}" value="${escapeHtml(values[index])}" required></label>`).join('');
}

function cronNumberControl(label, name, value, min, max) {
  return `<label>${label}<input type="number" name="${name}" value="${escapeHtml(value)}" min="${min}" max="${max}" required></label>`;
}

function cronTimeControl(value) {
  return `<label>执行时间<input type="time" name="time" value="${escapeHtml(value)}" step="1" required></label>`;
}

function cronSelectControl(label, name, values, selected) {
  return `<label>${label}<select name="${name}">${values.map(value => `<option value="${value}" ${value === selected ? 'selected' : ''}>${value}</option>`).join('')}</select></label>`;
}

function cronExpressionFromBuilder(mode, controls, fallback) {
  const value = name => controls.querySelector(`[name="${name}"]`)?.value ?? '';
  const time = () => {
    const [hour = '0', minute = '0', second = '0'] = value('time').split(':');
    return { hour: Number(hour), minute: Number(minute), second: Number(second) };
  };
  if (mode === 'seconds') return `*/${value('step') || 1} * * * * *`;
  if (mode === 'minutes') return `0 */${value('step') || 1} * * * *`;
  if (mode === 'hourly') return `${value('second') || 0} ${value('minute') || 0} * * * *`;
  if (mode === 'daily') { const at = time(); return `${at.second} ${at.minute} ${at.hour} * * *`; }
  if (mode === 'weekly') { const at = time(); return `${at.second} ${at.minute} ${at.hour} * * ${value('weekday')}`; }
  if (mode === 'monthly') { const at = time(); return `${at.second} ${at.minute} ${at.hour} ${value('day') || 1} * *`; }
  const fields = Array.from({ length: 6 }, (_, index) => value(`field${index}`));
  return fields.every(Boolean) ? fields.join(' ') : fallback;
}

async function refreshSchedulePreview(form) {
  const preview = form.querySelector('.schedule-preview');
  const cron = form.querySelector('[name="cron"]');
  const zone = form.querySelector('[name="zoneId"]');
  if (!cron.value.trim() || !zone.value.trim()) return;
  const requestId = Number(form.dataset.schedulePreviewRequest ?? 0) + 1;
  form.dataset.schedulePreviewRequest = String(requestId);
  try {
    const data = await api('/api/schedules/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cron: cron.value.trim(), zoneId: zone.value.trim(), count: 5 })
    });
    if (form.dataset.schedulePreviewRequest !== String(requestId)) return;
    cron.setCustomValidity('');
    preview.classList.remove('invalid');
    preview.dataset.cron = data.cron;
    preview.innerHTML = `<strong>未来 5 次触发</strong><ol>${normalizeList(data, 'nextFireTimes').map(item => `<li><time datetime="${escapeHtml(item.instant)}">${escapeHtml(item.local)}</time></li>`).join('')}</ol>`;
  } catch (error) {
    if (form.dataset.schedulePreviewRequest !== String(requestId)) return;
    const message = scheduleApiError(error);
    cron.setCustomValidity(message);
    preview.classList.add('invalid');
    delete preview.dataset.cron;
    preview.innerHTML = `<strong>${error.message === 'admin_ui_is_external' ? '服务版本不匹配' : 'Cron 无效'}</strong><span>${escapeHtml(message)}</span>`;
  }
}

function scheduleApiError(error) {
  return error.message === 'admin_ui_is_external'
    ? '当前 Admin API 仍是旧版本，请重启 Firefly 主服务后再试。'
    : error.message;
}

function closeDialog() {
  document.getElementById('modal-root').innerHTML = '';
}

async function createJob(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector('button[type="submit"]');
  const body = jobFormBody(form);
  body.name = body.name || body.id;
  submit.disabled = true;
  try {
    await api('/api/jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    closeDialog();
    await refreshView(false);
    toast(`任务已创建：${body.id}`);
  } catch (error) {
    toast(error.message);
  } finally {
    submit.disabled = false;
  }
}

function jobFormBody(form) {
  const body = Object.fromEntries(new FormData(form).entries());
  ['routingStrategy', 'completionPolicy', 'shardCount', 'routingKey'].forEach(name => {
    const field = form.elements[name];
    if (field) body[name] = field.value;
  });
  return body;
}

async function createExecutor(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    const body = Object.fromEntries(new FormData(form).entries());
    await api('/api/executor-definitions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    closeDialog();
    await refreshView(false);
    toast(`执行器已创建：${body.name}`);
  } catch (error) {
    toast(error.message);
  } finally {
    submit.disabled = false;
  }
}

function sampleExecutionObjects() {
  return sampleExecutions.map(item => ({
    executionId: item[0],
    jobId: item[1],
    scheduledFireTime: item[2],
    dispatchTime: item[3],
    startTime: item[4],
    endTime: item[5],
    duration: item[6],
    executorInstance: item[7],
    status: item[8]
  }));
}

function lineChart() {
  return `
    <svg viewBox="0 0 720 190" role="img" aria-label="调度延迟统计">
      <defs><linearGradient id="lineFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stop-color="#0f766e" stop-opacity=".20"/><stop offset="100%" stop-color="#0f766e" stop-opacity=".04"/></linearGradient></defs>
      ${[35,65,95,125,155].map(y => `<line x1="42" y1="${y}" x2="690" y2="${y}" stroke="#e2e8f0"/>`).join('')}
      <path d="M90 120 C150 105 205 90 245 55 C285 25 340 34 390 52 C445 72 485 90 540 98 C600 105 645 100 675 82 L675 155 L90 155 Z" fill="url(#lineFill)"/>
      <path d="M90 120 C150 105 205 90 245 55 C285 25 340 34 390 52 C445 72 485 90 540 98 C600 105 645 100 675 82" fill="none" stroke="#0f766e" stroke-width="2"/>
      <line x1="42" y1="155" x2="690" y2="155" stroke="#cbd5e1"/>
      ${['00:00','04:00','08:00','12:00','16:00','20:00','现在'].map((label, index) => `<text x="${90 + index * 97}" y="176" fill="#cbd5e1" font-size="13" text-anchor="middle">${label}</text>`).join('')}
      ${['0','3','6','9','12','15','---'].map((label, index) => `<text x="34" y="${158 - index * 25}" fill="#64748b" font-size="12" text-anchor="end">${label}</text>`).join('')}
    </svg>
  `;
}

function statusDonut() {
  return `
    <svg viewBox="0 0 560 190" role="img" aria-label="执行状态分布">
      <circle cx="190" cy="96" r="52" fill="none" stroke="#16a34a" stroke-width="24"/>
      <path d="M190 44 A52 52 0 0 1 194 44" fill="none" stroke="#2563eb" stroke-width="24"/>
      <path d="M194 44 A52 52 0 0 1 197 45" fill="none" stroke="#ef4444" stroke-width="24"/>
      ${legend(330, 52, '#16a34a', 'SUCCEEDED')}
      ${legend(330, 78, '#2563eb', 'RUNNING')}
      ${legend(330, 104, '#dc2626', 'FAILED')}
      ${legend(330, 130, '#d97706', 'TIMEOUT')}
      ${legend(330, 156, '#475569', 'MISFIRED')}
    </svg>
  `;
}

function nodeDonut() {
  return `
    <svg viewBox="0 0 700 250" role="img" aria-label="分片分布">
      <circle cx="350" cy="118" r="72" fill="none" stroke="#0f766e" stroke-width="34" stroke-dasharray="170 452" transform="rotate(-90 350 118)"/>
      <circle cx="350" cy="118" r="72" fill="none" stroke="#3b82f6" stroke-width="34" stroke-dasharray="135 452" stroke-dashoffset="-174" transform="rotate(-90 350 118)"/>
      <circle cx="350" cy="118" r="72" fill="none" stroke="#f59e0b" stroke-width="34" stroke-dasharray="113 452" stroke-dashoffset="-313" transform="rotate(-90 350 118)"/>
      <text x="470" y="80" fill="#334155">node-a</text><text x="470" y="96" fill="#334155">3 个分片</text>
      <text x="230" y="200" fill="#334155">node-b</text><text x="230" y="216" fill="#334155">3 个分片</text>
      <text x="240" y="58" fill="#334155">node-c</text><text x="240" y="74" fill="#334155">2 个分片</text>
      ${legend(276, 230, '#0f766e', 'node-a')}${legend(360, 230, '#3b82f6', 'node-b')}${legend(444, 230, '#f59e0b', 'node-c')}
    </svg>
  `;
}

function legend(x, y, color, label) {
  return `<rect x="${x}" y="${y - 10}" width="22" height="12" rx="3" fill="${color}"/><text x="${x + 30}" y="${y}" fill="#334155" font-size="13">${label}</text>`;
}

async function api(path, options) {
  return request(path, options);
}

async function request(path, options) {
  const requestOptions = { ...(options ?? {}) };
  const method = (requestOptions.method ?? 'GET').toUpperCase();
  const headers = new Headers(requestOptions.headers ?? {});
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && state.session?.csrfToken) {
    headers.set('X-Firefly-CSRF', state.session.csrfToken);
  }
  requestOptions.headers = headers;
  const requestKey = method === 'GET' ? `${method}:${path}` : '';
  if (requestKey && pendingRequests.has(requestKey)) return pendingRequests.get(requestKey);
  const operation = performRequest(path, requestOptions, method);
  if (requestKey) pendingRequests.set(requestKey, operation);
  try {
    return await operation;
  } finally {
    if (requestKey) pendingRequests.delete(requestKey);
  }
}

async function performRequest(path, requestOptions, method) {
  const response = await fetch(path, requestOptions);
  updateSessionFromResponse(response);
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: 'invalid_json', message: text };
  }
  if (!response.ok) {
    if (response.status === 401 && path.startsWith('/api/')) {
      showLogin('会话已过期，请重新登录');
    }
    const errorMessages = {
      executor_not_found: '执行器定义不存在或已被删除',
      executor_has_jobs: `该执行器仍被 ${Number(data?.jobCount ?? 0)} 个任务引用，请先迁移或删除任务`,
      executor_has_job_groups: `该执行器仍被 ${Number(data?.jobGroupCount ?? 0)} 个任务组引用，请先调整任务组`,
      executor_has_online_instances: `该执行器仍有 ${Number(data?.onlineInstances ?? 0)} 个在线实例，请先隔离或停止实例`
    };
    const message = data?.message ?? errorMessages[data?.error] ?? data?.error ?? `HTTP ${response.status}`;
    throw new Error(message);
  }
  return data;
}

function updateSessionFromResponse(response) {
  if (!state.session) return;
  const expiresAt = response.headers.get('X-Firefly-Session-Expires-At');
  const idleExpiresAt = response.headers.get('X-Firefly-Session-Idle-Expires-At');
  if (expiresAt) state.session.expiresAt = expiresAt;
  if (idleExpiresAt) state.session.idleExpiresAt = idleExpiresAt;
  updateSessionSummary();
}

function normalizeList(data, key) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.[key])) return data[key];
  return [];
}

function normalizeJob(job) {
  return {
    ...job,
    businessHandlerName: job.businessHandlerName ?? job.handlerName,
    groupName: job.groupName ?? job.groupId ?? 'default',
    lastResult: job.lastResult ?? '成功'
  };
}

function normalizeNode(node) {
  return {
    ...node,
    nodeId: node.nodeId ?? node.id,
    mode: node.mode ?? 'Cluster',
    shards: node.shards ?? ['shard-0'],
    leaseUntil: node.leaseUntil ?? '-',
    fencingToken: node.fencingToken ?? '7a2f9d3e...'
  };
}

function normalizeRoles(roles) {
  if (Array.isArray(roles)) return roles.join(', ');
  return String(roles ?? '').replace(/^\[|\]$/g, '') || '-';
}

function formatDate(value) {
  if (!value || value === '-') return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = number => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function toast(message) {
  const element = document.getElementById('toast');
  element.textContent = window.FireflyI18n.translate(message);
  element.classList.add('show');
  clearTimeout(element.hideTimer);
  element.hideTimer = setTimeout(() => element.classList.remove('show'), 2600);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[char]));
}

// Job operations are kept in one render path so every action has a real API
// contract and the table never falls back to placeholder glyphs.
function jobsPage() {
  const jobs = state.jobs;
  const executorOptions = [...new Set(jobs.map(job => job.executorName).filter(Boolean))];
  const rows = jobs.map(job => tableRow([
    code(job.id),
    text(job.name ?? job.id),
    text(job.groupName ?? job.groupId ?? 'default'),
    code(job.schedule ?? '-'),
    text(job.zoneId ?? 'UTC'),
    text(formatDate(job.nextFireTime)),
    text(job.executorName ?? '-'),
    text(job.dispatchMode ?? 'UNICAST'),
    statusText(job.enabled === false ? 'DISABLED' : 'ENABLED', job.enabled === false ? 'muted' : 'success'),
    statusText(job.lastResult ?? '-', job.lastResult === 'FAILED' ? 'danger' : 'success'),
    jobActions(job)
  ])).join('');
  return `
    <section class="toolbar jobs-toolbar">
      <label class="inline-field">任务名称 <input id="job-filter-text" placeholder="搜索任务名称"></label>
      <label class="inline-field">执行器 <select id="job-filter-executor"><option value="">全部执行器</option>${executorOptions.map(value => `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`).join('')}</select></label>
      <label class="inline-field">状态 <select id="job-filter-status"><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">暂停</option></select></label>
      <button class="btn" type="button" data-job-filter="apply">查询</button>
      <button class="btn" type="button" data-job-filter="reset">重置</button>
    </section>
    <section class="table-card">
      <div class="table-toolbar">
        <div><strong>任务列表</strong><span class="table-count">共 ${jobs.length} 条</span></div>
        <span class="muted">操作将立即生效</span>
      </div>
      <div class="table-scroll">
        <table class="jobs-table">
          <thead><tr>${headers(['Job ID', 'Name', 'Group', 'Schedule', 'Time zone', 'Next fire', 'Executor', 'Dispatch', 'Status', 'Last result', 'Actions'])}</tr></thead>
          <tbody>${rows || emptyRow(11, 'No jobs found')}</tbody>
        </table>
      </div>
    </section>
  `;
}

function jobActions(job) {
  const id = escapeHtml(job.id);
  const toggleLabel = job.enabled === false ? 'Enable job' : 'Pause job';
  const toggleIcon = job.enabled === false ? '&#9654;' : '&#10074;&#10074;';
  return `<div class="job-actions">
    <button class="icon-button primary" type="button" title="立即触发" aria-label="立即触发" data-job-operation="trigger" data-job-id="${id}">&#9654;</button>
    <button class="icon-button" type="button" title="${toggleLabel}" aria-label="${toggleLabel}" data-job-operation="toggle" data-job-id="${id}">${toggleIcon}</button>
    <button class="icon-button" type="button" title="编辑任务" aria-label="编辑任务" data-job-operation="edit" data-job-id="${id}">&#9998;</button>
    <button class="icon-button" type="button" title="查看执行记录" aria-label="查看执行记录" data-job-operation="view" data-job-id="${id}">&#128065;</button>
    <button class="icon-button danger" type="button" title="删除任务" aria-label="删除任务" data-job-operation="delete" data-job-id="${id}">&#10005;</button>
  </div>`;
}

function bindViewActions(view) {
  if (view === 'jobs') {
    document.querySelectorAll('[data-job-operation]').forEach(button => {
      button.addEventListener('click', () => operateJob(button.dataset.jobId, button.dataset.jobOperation));
    });
    document.querySelector('[data-job-filter="apply"]')?.addEventListener('click', filterJobs);
    document.querySelector('[data-job-filter="reset"]')?.addEventListener('click', () => {
      document.getElementById('job-filter-text').value = '';
      document.getElementById('job-filter-executor').value = '';
      document.getElementById('job-filter-status').value = '';
      renderView('jobs');
    });
  }
  if (view === 'executions') {
    document.querySelectorAll('[data-execution-detail]').forEach(button => {
      button.addEventListener('click', () => openExecutionDetail(button.dataset.executionDetail));
    });
    document.querySelectorAll('[data-outbox-requeue]').forEach(button => {
      button.addEventListener('click', () => requeueOutbox(button.dataset.outboxRequeue));
    });
  }
  if (view === 'executors') {
    document.querySelectorAll('[data-isolate-executor]').forEach(button => {
      button.addEventListener('click', () => isolateExecutor(button.dataset.isolateExecutor));
    });
    document.querySelectorAll('[data-delete-executor]').forEach(button => {
      button.addEventListener('click', () => deleteExecutor(button.dataset.deleteExecutor));
    });
    document.querySelectorAll('[data-executor-instances]').forEach(button => {
      button.addEventListener('click', () => openExecutorInstancesDialog(button.dataset.executorInstances));
    });
    bindExecutorInstanceDetailActions();
  }
  if (view === 'nodes') {
    document.querySelectorAll('[data-node-operation]').forEach(button => {
      button.addEventListener('click', () => updateNode(button.dataset.nodeId, button.dataset.nodeOperation));
    });
  }
  if (view === 'settings') {
    document.querySelector('[data-rotate-integration-key]')?.addEventListener('click', rotateIntegrationKey);
    document.querySelectorAll('[data-user-edit]').forEach(button => {
      button.addEventListener('click', () => openUserDialog(
        state.users.find(user => user.username === button.dataset.userEdit)
      ));
    });
    document.querySelectorAll('[data-user-delete]').forEach(button => {
      button.addEventListener('click', () => deleteUser(button.dataset.userDelete));
    });
  }
}

function usersPage() {
  const rows = state.users.map(user => {
    const current = user.username === state.session?.subject;
    return tableRow([
      code(user.username),
      text(normalizeRoles(user.roles)),
      statusText(user.enabled ? 'ENABLED' : 'DISABLED', user.enabled ? 'success' : 'muted'),
      text(String(user.version)),
      text(formatDate(user.createdAt)),
      text(formatDate(user.updatedAt)),
      `<div class="job-actions">
        <button class="icon-button" type="button" title="编辑账号" aria-label="编辑账号" data-user-edit="${escapeHtml(user.username)}">&#9998;</button>
        <button class="icon-button danger" type="button" title="删除账号" aria-label="删除账号" data-user-delete="${escapeHtml(user.username)}" ${current ? 'disabled' : ''}>&#10005;</button>
      </div>`
    ]);
  }).join('');
  return `
    <section class="table-card integration-key-panel">
      <div class="table-toolbar">
        <div><strong>Integration Key</strong><span class="table-count">服务集成凭据</span></div>
        <button class="btn primary" type="button" data-rotate-integration-key>${state.integrationKey?.configured ? '轮换密钥' : '生成密钥'}</button>
      </div>
      <div class="key-status-grid">
        <div><span class="muted">状态</span><strong>${state.integrationKey?.configured ? '已配置' : '未配置'}</strong></div>
        <div><span class="muted">版本</span><strong>${state.integrationKey?.version ?? '-'}</strong></div>
        <div><span class="muted">更新时间</span><strong>${formatDate(state.integrationKey?.updatedAt)}</strong></div>
      </div>
      <p class="muted key-note">用于 Executor 注册和启动任务同步。服务端仅保存摘要，密钥明文只在生成时展示一次。</p>
    </section>
    <section class="summary-strip">
      ${statCard('管理账号', state.users.length, '个', '持久化账号', '▤', 'primary')}
      ${statCard('启用账号', state.users.filter(user => user.enabled).length, '个', '可登录控制台', '●', 'yellow', 'success')}
      ${statCard('管理员', state.users.filter(user => user.enabled && user.roles?.includes('ADMIN')).length, '个', '启用中的 ADMIN', '◆', 'gray')}
    </section>
    <section class="table-card">
      <div class="table-toolbar"><div><strong>Admin 用户</strong><span class="table-count">共 ${state.users.length} 条</span></div><span class="muted">密码摘要不会通过 API 返回</span></div>
      <div class="table-scroll"><table>
        <thead><tr>${headers(['用户名', '角色', '状态', '版本', '创建时间', '更新时间', '操作'])}</tr></thead>
        <tbody>${rows || emptyRow(7, '暂无账号或当前用户没有 ADMIN 权限')}</tbody>
      </table></div>
    </section>`;
}

async function rotateIntegrationKey() {
  const action = state.integrationKey?.configured ? '轮换' : '生成';
  if (!window.confirm(`${action} Integration Key？现有服务需要更新配置后重新连接。`)) return;
  try {
    const result = await api('/api/integration-key', { method: 'POST' });
    state.integrationKey = { configured: true, version: result.version, updatedAt: result.updatedAt };
    renderView('settings');
    showIntegrationKey(result.integrationKey);
  } catch (error) {
    toast(error.message);
  }
}

function showIntegrationKey(value) {
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="integration-key-title">
      <section class="modal compact-modal">
        <div class="modal-header"><h2 id="integration-key-title">Integration Key</h2><button class="btn icon" type="button" data-close aria-label="关闭">×</button></div>
        <div class="modal-body">
          <p>请立即配置到集成服务。关闭后无法再次查看，只能重新轮换。</p>
          <div class="generated-key"><code>${escapeHtml(value)}</code><button class="icon-button" type="button" data-copy-key title="复制密钥" aria-label="复制密钥">&#10697;</button></div>
        </div>
        <div class="modal-footer"><button class="btn primary" type="button" data-close>我已保存</button></div>
      </section>
    </div>`;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  root.querySelector('[data-copy-key]').addEventListener('click', async () => {
    await navigator.clipboard.writeText(value);
    toast('Integration Key 已复制');
  });
}

function openUserDialog(user = null) {
  const editing = Boolean(user);
  const roles = new Set(user?.roles ?? ['READER']);
  const current = user?.username === state.session?.subject;
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="user-dialog-title">
      <section class="modal compact-modal">
        <div class="modal-header"><h2 id="user-dialog-title">${editing ? '编辑账号' : '新建账号'}</h2><button class="btn icon" type="button" data-close aria-label="关闭">×</button></div>
        <form id="user-form">
          <div class="modal-body">
            <label class="field">用户名<input name="username" value="${escapeHtml(user?.username ?? '')}" ${editing ? 'readonly' : 'required'} pattern="[A-Za-z0-9._@-]{1,128}" autocomplete="off"></label>
            <label class="field">${editing ? '新密码（留空则不修改）' : '密码'}<input type="password" name="password" ${editing ? '' : 'required'} minlength="8" maxlength="256" autocomplete="new-password"></label>
            <fieldset class="role-field"><legend>角色</legend>
              ${['READER', 'OPERATOR', 'ADMIN'].map(role => `<label><input type="checkbox" name="roles" value="${role}" ${roles.has(role) ? 'checked' : ''}> ${role}</label>`).join('')}
            </fieldset>
            <label class="toggle-field"><input type="checkbox" name="enabled" ${user?.enabled !== false ? 'checked' : ''} ${current ? 'disabled' : ''}><span>允许登录</span></label>
            ${editing ? `<input type="hidden" name="version" value="${Number(user.version)}">` : ''}
          </div>
          <div class="modal-footer"><button class="btn" type="button" data-close>取消</button><button class="btn primary" type="submit">保存</button></div>
        </form>
      </section>
    </div>`;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  root.querySelector('#user-form').addEventListener('submit', event => saveUser(event, user));
}

async function saveUser(event, existing) {
  event.preventDefault();
  const form = event.currentTarget;
  const roles = [...form.querySelectorAll('input[name="roles"]:checked')].map(input => input.value);
  if (!roles.length) {
    toast('请至少选择一个角色');
    return;
  }
  const body = {
    username: form.elements.username.value.trim(),
    password: form.elements.password.value,
    roles: roles.join(','),
    enabled: form.elements.enabled?.checked ?? existing?.enabled ?? true
  };
  if (existing) body.version = Number(form.elements.version.value);
  if (existing && !body.password) delete body.password;
  try {
    await api(existing ? `/api/users/${encodeURIComponent(existing.username)}` : '/api/users', {
      method: existing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    closeDialog();
    await refreshView(false);
    toast(existing ? `账号已更新：${existing.username}` : `账号已创建：${body.username}`);
  } catch (error) {
    toast(error.message);
  }
}

async function deleteUser(username) {
  const user = state.users.find(item => item.username === username);
  if (!user || !window.confirm(`确认删除账号“${username}”？`)) return;
  try {
    await api(`/api/users/${encodeURIComponent(username)}`, {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: Number(user.version) })
    });
    await refreshView(false);
    toast(`账号已删除：${username}`);
  } catch (error) {
    toast(error.message);
  }
}

function filterJobs() {
  const query = document.getElementById('job-filter-text').value.trim().toLowerCase();
  const executor = document.getElementById('job-filter-executor').value;
  const status = document.getElementById('job-filter-status').value;
  const filtered = state.jobs.filter(job => {
    const haystack = [job.id, job.name, job.executorName, job.businessHandlerName, job.handlerName].join(' ').toLowerCase();
    return (!query || haystack.includes(query))
      && (!executor || job.executorName === executor)
      && (!status || (status === 'enabled' ? job.enabled !== false : job.enabled === false));
  });
  const original = state.jobs;
  state.jobs = filtered;
  renderView('jobs');
  state.jobs = original;
}

async function operateJob(jobId, operation) {
  const job = state.jobs.find(item => item.id === jobId);
  if (!job) return;
  if (operation === 'view') {
    state.executionFilterJobId = jobId;
    await setView('executions');
    return;
  }
  if (operation === 'edit') {
    openJobEditDialog(job);
    return;
  }
  if (operation === 'delete' && !window.confirm(`确认删除任务“${jobId}”？`)) return;
  if (operation === 'trigger' && !window.confirm(`确认立即触发任务“${jobId}”？`)) return;
  try {
    if (operation === 'trigger') {
      await api(`/api/jobs/${encodeURIComponent(jobId)}/trigger`, { method: 'POST' });
      toast(`任务已触发：${jobId}`);
    } else if (operation === 'toggle') {
      const enabled = job.enabled === false;
      await api(`/api/jobs/${encodeURIComponent(jobId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled })
      });
      toast(enabled ? `任务已启用：${jobId}` : `任务已暂停：${jobId}`);
    } else if (operation === 'delete') {
      await api(`/api/jobs/${encodeURIComponent(jobId)}`, { method: 'DELETE' });
      toast(`任务已删除：${jobId}`);
    }
    await refreshView(false);
  } catch (error) {
    toast(error.message);
  }
}

function openJobEditDialog(job) {
  const root = document.getElementById('modal-root');
  root.innerHTML = `
    <div class="modal-mask" role="dialog" aria-modal="true" aria-labelledby="job-edit-title">
      <form id="job-edit-dialog" class="modal">
        <div class="modal-header"><h2 id="job-edit-title">编辑任务</h2><button class="btn icon" type="button" data-close aria-label="关闭">&#10005;</button></div>
        <div class="modal-body">
          ${dialogField('任务 ID', 'id', job.id, true)}
          ${dialogField('任务名称', 'name', job.name ?? job.id, true)}
          ${executorSelect(job.executorName ?? '')}
          ${handlerSelect(job.executorName ?? '', job.businessHandlerName ?? job.handlerName ?? '')}
          ${scheduleEditorFields(job.schedule ?? '*/5 * * * * *', job.zoneId ?? 'UTC')}
          ${dialogSelect('分发模式', 'dispatchMode', ['UNICAST', 'BROADCAST', 'SHARDING'], jobFieldHelp.dispatchMode)}
          ${dialogSelect('路由策略', 'routingStrategy', ['ROUND_ROBIN', 'RANDOM', 'CONSISTENT_HASH'], jobFieldHelp.routingStrategy)}
          ${dialogSelect('完成策略', 'completionPolicy', ['ALL_SUCCESS', 'ANY_SUCCESS', 'QUORUM'], jobFieldHelp.completionPolicy)}
          ${dialogSelect('重试范围', 'retryScope', ['FAILED_TARGETS_ONLY', 'ALL_TARGETS'], jobFieldHelp.retryScope)}
          ${dialogField('分片数量', 'shardCount', job.shardCount ?? '1', true, 'number', jobFieldHelp.shardCount, 'min="1" max="4096"')}
          ${dialogField('路由键', 'routingKey', job.routingKey ?? '', false, 'text', jobFieldHelp.routingKey)}
          ${dialogField('最大重试次数', 'retryMaxAttempts', job.retryMaxAttempts ?? '1', true, 'number')}
          ${dialogSelect('状态', 'enabled', ['true', 'false'])}
        </div>
        <div class="modal-footer"><button class="btn" type="button" data-close>取消</button><button class="btn primary" type="submit">保存修改</button></div>
      </form>
    </div>`;
  root.querySelector('[name="enabled"]').value = job.enabled === false ? 'false' : 'true';
  const selectedValues = {
    dispatchMode: job.dispatchMode ?? 'UNICAST',
    routingStrategy: job.routingStrategy ?? 'ROUND_ROBIN',
    completionPolicy: job.completionPolicy ?? 'ALL_SUCCESS',
    retryScope: job.retryScope ?? 'FAILED_TARGETS_ONLY'
  };
  Object.entries(selectedValues).forEach(([name, value]) => {
    const field = root.querySelector(`[name="${name}"]`);
    if (field) field.value = value;
  });
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  const form = root.querySelector('#job-edit-dialog');
  bindJobDestinationFields(form);
  bindJobPolicyFields(form);
  bindScheduleEditor(form);
  form.addEventListener('submit', submitJobEdit);
}

async function submitJobEdit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const body = jobFormBody(form);
  const jobId = body.id;
  delete body.id;
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await api(`/api/jobs/${encodeURIComponent(jobId)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    closeDialog();
    await refreshView(false);
    toast(`任务已更新：${jobId}`);
  } catch (error) {
    toast(error.message);
  } finally {
    submit.disabled = false;
  }
}
