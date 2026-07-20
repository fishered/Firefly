const views = {
  overview: { title: '总览' },
  jobs: { title: '任务管理', action: 'new-job' },
  executors: { title: '执行器', action: 'new-executor' },
  executions: { title: '执行记录', action: 'refresh' },
  nodes: { title: '节点与集群', badge: 'Cluster Mode', action: 'refresh' },
  plugins: { title: '插件' },
  settings: { title: '配置' }
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
  overview: null,
  jobs: sampleJobs,
  executions: [],
  deadDispatches: [],
  executors: [],
  executorDefinitions: [],
  executorHeartbeatTimeoutSeconds: 30,
  executorServerTime: null,
  nodes: sampleNodes
};

document.querySelectorAll('.nav').forEach(button => {
  button.addEventListener('click', () => setView(button.dataset.view));
});

bootstrap();

async function bootstrap() {
  await loadUiConfig();
  await loadAllData();
  setView('overview');
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
  await refreshView(false);
}

async function refreshView(notify = true) {
  await loadAllData();
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
  actions.innerHTML = view === 'overview' ? `<span class="muted">●</span><span class="muted">?</span>` : '';
}

async function loadAllData() {
  await Promise.allSettled([
    loadOverview(), loadJobs(), loadExecutions(), loadDeadDispatches(), loadExecutors(), loadNodes()
  ]);
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

function renderView(view) {
  const root = document.getElementById('view-root');
  if (view === 'overview') root.innerHTML = overviewPage();
  if (view === 'jobs') root.innerHTML = jobsPage();
  if (view === 'executors') root.innerHTML = executorsPage();
  if (view === 'executions') root.innerHTML = executionsPage();
  if (view === 'nodes') root.innerHTML = nodesPage();
  if (view === 'plugins') root.innerHTML = placeholderPage('插件管理页面正在接入 Admin API');
  if (view === 'settings') root.innerHTML = placeholderPage('配置页面正在接入 Admin API');
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
          ${pluginRow('admin-web', true)}
          ${pluginRow('metrics-prometheus', true)}
          ${pluginRow('executor-gateway-netty', true)}
          ${pluginRow('jdbc-store', true)}
          ${pluginRow('alert-email', false)}
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
  const definitions = state.executorDefinitions.length
    ? state.executorDefinitions
    : [...new Set(executors.map(item => item.executorName))].map(name => ({
        name, description: '-', protocols: ['TCP'], enabled: true
      }));
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
          <thead><tr>${headers(['执行器名称', '说明', '协议', '状态', '已绑定实例', '操作'])}</tr></thead>
          <tbody>${definitions.map(definition => {
            const bound = executors.filter(item => item.executorName === definition.name);
            const onlineCount = bound.filter(item => item.status === 'ONLINE').length;
            const offlineCount = bound.length - onlineCount;
            return tableRow([
              code(definition.name),
              text(definition.description || '-'),
              text(Array.isArray(definition.protocols) ? definition.protocols.join(', ') : definition.protocols || '-'),
              statusText(definition.enabled === false ? 'DISABLED' : 'ENABLED', definition.enabled === false ? 'muted' : 'success'),
              `<button class="link-button" type="button" data-executor-instances="${escapeHtml(definition.name)}">${onlineCount} 在线${offlineCount ? ` / ${offlineCount} 离线` : ''}</button>`,
              `<div class="job-actions">
                <button class="link-button" type="button" data-executor-instances="${escapeHtml(definition.name)}">查看实例</button>
                ${definition.enabled === false ? '' : `<button class="link-button danger" type="button" data-isolate-executor="${escapeHtml(definition.name)}">隔离</button>`}
              </div>`
            ]);
          }).join('') || emptyRow(6, '暂无执行器定义')}</tbody>
        </table>
      </div>
    </section>
    <section class="table-card">
      <div class="table-title">执行器实例</div>
      <div class="table-scroll">
        <table>
          <thead><tr>${headers(['执行器','实例','服务','Gateway','地址','心跳年龄','状态','操作'])}</tr></thead>
          <tbody>${executors.map(item => tableRow([
            text(item.executorName),
            code(item.instanceId),
            text(item.serviceName),
            text(item.gatewayNodeId || '-'),
            text(`${item.host ?? '-'}:${item.port ?? '-'}`),
            text(`${Number(item.heartbeatAgeSeconds ?? 0)}s`),
            statusText(item.status ?? 'OFFLINE', item.status === 'ONLINE' ? 'success' : 'muted'),
            `<button class="link-button" type="button" data-executor-instance-detail="${escapeHtml(item.instanceId)}" data-executor-name="${escapeHtml(item.executorName)}">查看</button>`
          ])).join('') || emptyRow(8, '暂无执行器实例')}</tbody>
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
          ${dialogField('执行器', 'executorName', 'example-executor', true)}
          ${dialogField('处理器', 'handlerName', 'exampleHandler', true)}
          ${dialogSelect('分发模式', 'dispatchMode', ['UNICAST', 'BROADCAST', 'SHARDING'])}
          ${dialogSelect('路由策略', 'routingStrategy', ['ROUND_ROBIN', 'RANDOM', 'CONSISTENT_HASH'])}
          ${dialogSelect('完成策略', 'completionPolicy', ['ALL_SUCCESS', 'ANY_SUCCESS', 'QUORUM'])}
          ${dialogSelect('重试范围', 'retryScope', ['FAILED_TARGETS_ONLY', 'ALL_TARGETS'])}
          ${dialogField('分片数', 'shardCount', '1', true, 'number')}
          ${dialogField('路由键', 'routingKey', '', false)}
          ${dialogField('Cron', 'cron', '*/5 * * * * *', true)}
          ${dialogField('时区', 'zoneId', 'Asia/Shanghai', true)}
        </div>
        <div class="modal-footer">
          <button class="btn" type="button" data-close>取消</button>
          <button class="btn primary" type="submit">创建任务</button>
        </div>
      </form>
    </div>
  `;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  root.querySelector('#job-dialog').addEventListener('submit', createJob);
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
          ${dialogField('协议', 'protocols', 'TCP', true)}
        </div>
        <div class="modal-footer"><button class="btn" type="button" data-close>取消</button><button class="btn primary" type="submit">创建执行器</button></div>
      </form>
    </div>
  `;
  root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', closeDialog));
  root.querySelector('#executor-dialog').addEventListener('submit', createExecutor);
}

function dialogField(label, name, value, required, type = 'text') {
  return `<label class="field">${escapeHtml(label)}<input type="${escapeHtml(type)}" name="${escapeHtml(name)}" value="${escapeHtml(value)}" ${required ? 'required' : ''}></label>`;
}

function dialogSelect(label, name, options) {
  return `<label class="field">${escapeHtml(label)}<select name="${escapeHtml(name)}">${options.map(option => `<option value="${escapeHtml(option)}">${escapeHtml(option)}</option>`).join('')}</select></label>`;
}

function closeDialog() {
  document.getElementById('modal-root').innerHTML = '';
}

async function createJob(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector('button[type="submit"]');
  const body = Object.fromEntries(new FormData(form).entries());
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
  const response = await fetch(path, options);
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: 'invalid_json', message: text };
  }
  if (!response.ok) {
    const message = data?.message ?? data?.error ?? `HTTP ${response.status}`;
    throw new Error(message);
  }
  return data;
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
  element.textContent = message;
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
    text(job.businessHandlerName ?? job.handlerName ?? '-'),
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
          <thead><tr>${headers(['Job ID', 'Name', 'Group', 'Schedule', 'Time zone', 'Next fire', 'Executor', 'Dispatch', 'Handler', 'Status', 'Last result', 'Actions'])}</tr></thead>
          <tbody>${rows || emptyRow(12, 'No jobs found')}</tbody>
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
          ${dialogField('执行器', 'executorName', job.executorName ?? '', true)}
          ${dialogField('处理器', 'handlerName', job.businessHandlerName ?? job.handlerName ?? '', true)}
          ${dialogField('Cron 表达式', 'cron', job.schedule ?? '*/5 * * * * *', true)}
          ${dialogField('时区', 'zoneId', job.zoneId ?? 'UTC', true)}
          ${dialogSelect('分发模式', 'dispatchMode', ['UNICAST', 'BROADCAST', 'SHARDING'])}
          ${dialogSelect('路由策略', 'routingStrategy', ['ROUND_ROBIN', 'RANDOM', 'CONSISTENT_HASH'])}
          ${dialogSelect('完成策略', 'completionPolicy', ['ALL_SUCCESS', 'ANY_SUCCESS', 'QUORUM'])}
          ${dialogSelect('重试范围', 'retryScope', ['FAILED_TARGETS_ONLY', 'ALL_TARGETS'])}
          ${dialogField('分片数量', 'shardCount', job.shardCount ?? '1', true, 'number')}
          ${dialogField('路由键', 'routingKey', job.routingKey ?? '', false)}
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
  root.querySelector('#job-edit-dialog').addEventListener('submit', submitJobEdit);
}

async function submitJobEdit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const body = Object.fromEntries(new FormData(form).entries());
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
