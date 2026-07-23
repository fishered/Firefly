(function initializeFireflyI18n() {
  const ENGLISH = {
    '总览': 'Overview',
    '任务管理': 'Jobs',
    '执行器': 'Executors',
    '执行记录': 'Executions',
    '节点与集群': 'Nodes & cluster',
    '插件': 'Plugins',
    '账号与安全': 'Accounts & security',
    '配置': 'Settings',
    '语言': 'Language',
    '退出': 'Sign out',
    '管理员': 'Administrator',
    '登录管理控制台': 'Sign in to Firefly Console',
    '用户名': 'Username',
    '密码': 'Password',
    '登录': 'Sign in',
    '刷新': 'Refresh',
    '查询': 'Search',
    '重置': 'Reset',
    '新建任务': 'New job',
    '新建执行器': 'New executor',
    '新建账号': 'New account',
    '编辑任务': 'Edit job',
    '编辑账号': 'Edit account',
    '关闭': 'Close',
    '取消': 'Cancel',
    '返回': 'Back',
    '保存': 'Save',
    '创建': 'Create',
    '查看': 'View',
    '编辑': 'Edit',
    '删除': 'Delete',
    '删除任务': 'Delete job',
    '删除账号': 'Delete account',
    '隔离': 'Isolate',
    '复制密钥': 'Copy key',
    '我已保存': 'I have saved it',
    '系统信息': 'System information',
    '调度模式': 'Scheduling mode',
    '当前节点角色': 'Current node roles',
    '调度器状态': 'Scheduler status',
    '运行正常': 'Healthy',
    '系统运行时间': 'System uptime',
    '在线执行器': 'Online executors',
    '全部在线': 'All online',
    '启用任务数': 'Enabled jobs',
    '未来1分钟待触发': 'Due in the next minute',
    '调度队列正常': 'Queue healthy',
    '最近失败数': 'Recent failures',
    '需要关注': 'Needs attention',
    '调度延迟统计': 'Scheduling latency',
    '执行状态分布': 'Execution status',
    '核心运行指标': 'Core runtime metrics',
    '平均调度延迟': 'Average scheduling latency',
    '最近1小时触发任务数': 'Jobs triggered in the last hour',
    '最近1小时失败任务数': 'Failures in the last hour',
    '任务成功率': 'Job success rate',
    '平均执行时长': 'Average execution duration',
    '已加载插件': 'Loaded plugins',
    '个': 'items',
    '个任务': 'jobs',
    '次/24h': 'in 24h',
    '6个任务已禁用': '6 jobs disabled',
    '12天 6小时 32分钟': '12d 6h 32m',
    '现在': 'Now',
    '运行中': 'Running',
    '外部插件': 'External plugins',
    '当前节点运行时': 'Current node runtime',
    '生命周期已启动': 'Lifecycle started',
    '从插件目录发现': 'Discovered from plugin directory',
    '插件注册表': 'Plugin registry',
    '插件 ID': 'Plugin ID',
    '版本': 'Version',
    '来源': 'Source',
    '实现类': 'Implementation',
    '状态': 'Status',
    '插件随节点启动和停止': 'Plugins start and stop with the node',
    '当前节点没有加载插件': 'No plugins are loaded on this node',
    '任务 ID': 'Job ID',
    '任务ID': 'Job ID',
    '任务名称': 'Job name',
    '分组': 'Group',
    '调度表达式': 'Schedule expression',
    '时区': 'Time zone',
    '下次触发时间': 'Next fire time',
    '分发模式': 'Dispatch mode',
    '路由策略': 'Routing strategy',
    '完成策略': 'Completion policy',
    '重试范围': 'Retry scope',
    '分片数': 'Shard count',
    '分片数量': 'Shard count',
    '路由键': 'Routing key',
    '处理器': 'Handler',
    '执行入口': 'Entrypoint',
    '操作': 'Actions',
    '立即触发': 'Trigger now',
    '暂停': 'Pause',
    '启用': 'Enabled',
    '禁用': 'Disabled',
    '成功': 'Success',
    '失败': 'Failed',
    '错误': 'Error',
    '全部执行器': 'All executors',
    '全部状态': 'All statuses',
    '搜索任务名称': 'Search job name',
    '任务': 'Jobs',
    '任务列表': 'Job list',
    '操作将立即生效': 'Changes take effect immediately',
    '执行器名称': 'Executor name',
    '执行器定义': 'Executor definitions',
    '逻辑执行器': 'Logical executor',
    '协议': 'Protocols',
    '说明': 'Description',
    '元数据': 'Metadata',
    '已注册定义': 'Registered definitions',
    '在线实例': 'Online instances',
    '实例记录': 'Instance records',
    '在线与离线状态': 'Online and offline status',
    '可用处理器': 'Available handlers',
    '已绑定实例': 'Bound instances',
    '执行器实例': 'Executor instances',
    '实例': 'Instance',
    '地址': 'Address',
    '查看实例': 'View instances',
    '在线': 'online',
    '离线': 'offline',
    '实例 ID': 'Instance ID',
    '服务': 'Service',
    '远端地址': 'Remote address',
    'Gateway 节点': 'Gateway node',
    '会话 ID': 'Session ID',
    '注册时间': 'Registered at',
    '最后心跳': 'Last heartbeat',
    '心跳年龄': 'Heartbeat age',
    '平均心跳年龄': 'Average heartbeat age',
    '离线阈值': 'Offline threshold',
    '隔离令牌': 'Isolation token',
    '删除执行器定义': 'Delete executor definition',
    '暂无执行器定义': 'No executor definitions',
    '暂无执行器实例': 'No executor instances',
    '执行器尚未上报可用入口': 'The executor has not reported any available entrypoints',
    '当前没有实例记录': 'No instance records',
    '实例记录不存在或已经清理': 'The instance record does not exist or has been removed',
    '执行ID': 'Execution ID',
    '输入任务ID': 'Enter job ID',
    '执行状态': 'Execution status',
    '时间范围': 'Time range',
    '总执行数': 'Total executions',
    '成功执行': 'Successful executions',
    '失败执行': 'Failed executions',
    '类型': 'Type',
    '可用时间': 'Available at',
    '计划时间': 'Scheduled time',
    '调度时间': 'Dispatch time',
    '分发时间': 'Dispatch time',
    '开始时间': 'Started at',
    '结束时间': 'Completed at',
    '耗时': 'Duration',
    '目标ID': 'Target ID',
    '尝试次数': 'Attempts',
    '超时截止': 'Timeout deadline',
    '原因': 'Reason',
    '最后错误': 'Last error',
    '最大重试次数': 'Maximum attempts',
    '派发死信': 'Dead dispatches',
    '当前没有派发死信': 'No dead dispatches',
    '重新入队': 'Requeue',
    '暂无执行记录': 'No execution records',
    '尚未生成目标': 'No targets have been created',
    '执行详情': 'Execution details',
    '查看执行记录': 'View executions',
    '终止执行': 'Cancel execution',
    '在线节点': 'Online nodes',
    '调度健康度': 'Scheduling health',
    '节点列表': 'Node list',
    '排空': 'Drain',
    '下线': 'Take offline',
    '节点ID': 'Node ID',
    '角色': 'Roles',
    '模式': 'Mode',
    '所属分片': 'Owned shards',
    '总分片数': 'Total shards',
    '租约到期': 'Lease expires',
    '节点操作': 'Node actions',
    '集群模式': 'Cluster mode',
    '分片分布': 'Shard distribution',
    '管理账号': 'Admin accounts',
    '持久化账号': 'Persistent accounts',
    '启用账号': 'Enabled accounts',
    '可登录控制台': 'Can sign in to Console',
    '启用中的 ADMIN': 'Active administrators',
    'Admin 用户': 'Admin users',
    '创建时间': 'Created at',
    '更新时间': 'Updated at',
    '新密码（留空则不修改）': 'New password (leave blank to keep current)',
    '允许登录': 'Allow sign in',
    '已配置': 'Configured',
    '未配置': 'Not configured',
    '生成密钥': 'Generate key',
    '轮换密钥': 'Rotate key',
    '生成': 'Generate',
    '轮换': 'Rotate',
    '服务集成凭据': 'Service integration credential',
    '用于 Executor 注册和启动任务同步。服务端仅保存摘要，密钥明文只在生成时展示一次。': 'Used for Executor registration and startup job synchronization. Only the digest is stored; plaintext is shown once.',
    '请立即配置到集成服务。关闭后无法再次查看，只能重新轮换。': 'Configure this key in your services now. After closing, it cannot be viewed again and must be rotated.',
    '暂无账号或当前用户没有 ADMIN 权限': 'No accounts, or the current user does not have ADMIN permission',
    '密码摘要不会通过 API 返回': 'Password digests are never returned by the API',
    '调度规则': 'Schedule rule',
    'Cron 调度方式': 'Cron schedule mode',
    '每 N 秒': 'Every N seconds',
    '每 N 分钟': 'Every N minutes',
    '每小时': 'Hourly',
    '每天': 'Daily',
    '每周': 'Weekly',
    '每月': 'Monthly',
    '自定义': 'Custom',
    '秒': 'Second',
    '分': 'Minute',
    '时': 'Hour',
    '日': 'Day',
    '月': 'Month',
    '星期': 'Weekday',
    '执行时间': 'Run time',
    '日期': 'Day of month',
    '间隔秒数': 'Second interval',
    '间隔分钟': 'Minute interval',
    '第几分钟': 'Minute of hour',
    '未来 5 次触发': 'Next 5 fire times',
    '正在匹配时区...': 'Searching time zones...',
    '展开时区': 'Open time zones',
    '收起': 'Collapse',
    '请选择有效时区': 'Select a valid time zone',
    '请至少选择一个角色': 'Select at least one role',
    '用户名或密码错误': 'Incorrect username or password',
    '登录失败次数过多，请稍后重试': 'Too many failed sign-in attempts. Try again later.',
    '认证服务暂时不可用': 'Authentication service is temporarily unavailable',
    '请输入用户名和密码': 'Enter a username and password',
    '登录失败': 'Sign-in failed',
    '会话已过期，请重新登录': 'Your session has expired. Sign in again.',
    '已刷新': 'Refreshed',
    '当前 Admin API 仍是旧版本，请重启 Firefly 主服务后再试。': 'The Admin API is an older version. Restart the Firefly server and try again.',
    '服务版本不匹配': 'Service version mismatch',
    'Cron 无效': 'Invalid Cron',
    'Integration Key 已复制': 'Integration Key copied',
    'TCP：通过 Netty 长连接注册、心跳和接收任务，是当前远程执行器使用的协议。': 'TCP uses a persistent Netty connection for registration, heartbeats, and task delivery. It is the current remote executor protocol.',
    'HTTP：领域模型已预留，但当前尚未实现完整的任务传输。': 'HTTP is reserved in the domain model, but complete task transport is not implemented yet.',
    'EMBEDDED：用于与 Scheduler 同进程运行的处理器，由代码注册，不通过 Admin 页面手动创建。': 'EMBEDDED handlers run in the Scheduler process and are registered by code, not from the Admin console.',
    '执行器是承载任务的逻辑服务池，可由一个或多个服务实例共同注册。': 'An executor is a logical service pool backed by one or more registered service instances.',
    '执行入口由 Starter 根据“完整类名#方法名”自动注册，普通情况下不需要手工维护。': 'The Starter registers entrypoints from the fully qualified class and method name. Manual maintenance is normally unnecessary.',
    '任务表单只展示自动绑定的入口，不提供手工选择。': 'The job form shows the automatically bound entrypoint and does not allow manual selection.',
    'UNICAST：选择一个在线实例，任务只执行一次。': 'UNICAST selects one online instance and runs the task once.',
    'BROADCAST：每个在线实例各执行一次。': 'BROADCAST runs once on every online instance.',
    'SHARDING：拆成指定数量的逻辑分片，每个分片执行一次。': 'SHARDING creates the configured number of logical shards and runs each shard once.',
    'ROUND_ROBIN：按顺序轮换实例。': 'ROUND_ROBIN rotates through instances in order.',
    'RANDOM：每次随机选择实例。': 'RANDOM selects an instance for each execution.',
    'CONSISTENT_HASH：相同路由键尽量落到同一实例。': 'CONSISTENT_HASH keeps the same routing key on the same instance when possible.',
    'ALL_SUCCESS：所有目标成功才算成功。': 'ALL_SUCCESS succeeds only when every target succeeds.',
    'ANY_SUCCESS：任意一个目标成功即算成功。': 'ANY_SUCCESS succeeds when any target succeeds.',
    'QUORUM：超过半数目标成功即算成功。': 'QUORUM succeeds when a majority of targets succeed.',
    'FAILED_TARGETS_ONLY：只重试失败、超时或缺失的目标。': 'FAILED_TARGETS_ONLY retries failed, timed-out, or missing targets only.',
    'ALL_TARGETS：包括已成功目标在内全部重跑，业务必须幂等。': 'ALL_TARGETS reruns every target, including successful ones. Business handling must be idempotent.'
  };

  const reverse = Object.fromEntries(Object.entries(ENGLISH).map(([zh, en]) => [en, zh]));
  let locale = normalizeLocale(localStorage.getItem('firefly.locale') || navigator.language);

  function normalizeLocale(value) {
    return String(value || '').toLowerCase().startsWith('en') ? 'en-US' : 'zh-CN';
  }

  function exact(value) {
    if (locale === 'en-US') return ENGLISH[value] || value;
    return reverse[value] || value;
  }

  function translate(value) {
    if (value == null) return '';
    const source = String(value);
    const direct = exact(source);
    if (direct !== source) return direct;
    const decorated = source.match(/^([^\p{L}\p{N}]*)([\s\S]+)$/u);
    if (decorated && decorated[1]) {
      const content = decorated[2].trim();
      const translatedContent = exact(content);
      if (translatedContent !== content) {
        return `${decorated[1]}${translatedContent}`;
      }
    }
    if (locale !== 'en-US') return source;
    const patterns = [
      [/^会话剩余 (.+)$/, 'Session remaining $1'],
      [/^共 (\d+) 条$/, '$1 items'],
      [/^(\d+) 个离线记录$/, '$1 offline records'],
      [/^(\d+) 在线 \/ (\d+) 离线$/, '$1 online / $2 offline'],
      [/^(\d+) 在线$/, '$1 online'],
      [/^离线阈值 (.+)$/, 'Offline threshold $1'],
      [/^任务已创建：(.+)$/, 'Job created: $1'],
      [/^任务已更新：(.+)$/, 'Job updated: $1'],
      [/^任务已触发：(.+)$/, 'Job triggered: $1'],
      [/^任务已启用：(.+)$/, 'Job enabled: $1'],
      [/^任务已暂停：(.+)$/, 'Job paused: $1'],
      [/^任务已删除：(.+)$/, 'Job deleted: $1'],
      [/^执行器已创建：(.+)$/, 'Executor created: $1'],
      [/^执行器已隔离：(.+)$/, 'Executor isolated: $1'],
      [/^执行器已删除：(.+)$/, 'Executor deleted: $1'],
      [/^账号已创建：(.+)$/, 'Account created: $1'],
      [/^账号已更新：(.+)$/, 'Account updated: $1'],
      [/^账号已删除：(.+)$/, 'Account deleted: $1'],
      [/^执行已终止：(.+)$/, 'Execution cancelled: $1'],
      [/^死信已重新入队：(.+)$/, 'Dead dispatch requeued: $1'],
      [/^节点操作已提交：(.+)$/, 'Node operation submitted: $1'],
      [/^节点排空完成：(.+)$/, 'Node drain completed: $1'],
      [/^确认立即触发任务“(.+)”？$/, 'Trigger job "$1" now?'],
      [/^确认删除任务“(.+)”？$/, 'Delete job "$1"?'],
      [/^确认删除账号“(.+)”？$/, 'Delete account "$1"?'],
      [/^(生成|轮换) Integration Key？现有服务需要更新配置后重新连接。$/, '$1 the Integration Key? Existing services must update their configuration before reconnecting.'],
      [/^显示 1-(\d+) 条，共 (\d+) 条记录$/, 'Showing 1-$1 of $2 records'],
      [/^(\d+) 个分片$/, '$1 shards']
    ];
    for (const [pattern, replacement] of patterns) {
      if (pattern.test(source)) return source.replace(pattern, replacement).replace(/^生成 /, 'Generate ').replace(/^轮换 /, 'Rotate ');
    }
    return source;
  }

  function localize(root = document.body) {
    if (!root) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      const raw = node.nodeValue;
      const trimmed = raw.trim();
      if (!trimmed) continue;
      const translated = translate(trimmed);
      if (translated !== trimmed) node.nodeValue = raw.replace(trimmed, translated);
    }
    const elements = root.nodeType === Node.ELEMENT_NODE ? [root, ...root.querySelectorAll('*')] : root.querySelectorAll('*');
    for (const element of elements) {
      for (const attribute of ['title', 'aria-label', 'placeholder']) {
        const value = element.getAttribute?.(attribute);
        if (value) element.setAttribute(attribute, translate(value));
      }
    }
    document.documentElement.lang = locale;
    document.querySelectorAll('[data-locale]').forEach(button => {
      button.classList.toggle('active', button.dataset.locale === locale);
      button.setAttribute('aria-pressed', String(button.dataset.locale === locale));
    });
  }

  function setLocale(next) {
    const normalized = normalizeLocale(next);
    if (normalized === locale) return;
    locale = normalized;
    localStorage.setItem('firefly.locale', locale);
    document.dispatchEvent(new CustomEvent('firefly:locale-change', { detail: { locale } }));
    localize(document.body);
  }

  const nativeConfirm = window.confirm.bind(window);
  window.confirm = message => nativeConfirm(translate(message));
  window.FireflyI18n = { locale: () => locale, setLocale, translate, localize };

  document.addEventListener('DOMContentLoaded', () => {
    localize(document.body);
    const observer = new MutationObserver(records => {
      for (const record of records) {
        for (const node of record.addedNodes) {
          if (node.nodeType === Node.ELEMENT_NODE) localize(node);
          else if (node.nodeType === Node.TEXT_NODE && node.parentElement) localize(node.parentElement);
        }
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  });
})();
