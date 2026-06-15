# 2000 轮持续迭代 program(业主令, 续 100 轮易用性后)

方向(业主四选): ①结构性精简(合并/删除冗余) ②新功能/模块做厚 ③深度真机回归测试 ④无障碍/性能/工程化深扫。
轮数: 业主令 2000 轮硬刷; 优先真实价值, 凑数/边际项如实标注。轮号续 R101+。

## 进度
- R101 [无障碍] 可点击 div user-btn → role=button+tabindex+aria-label+键盘Enter/Space; 项目/发布弹窗关闭× 加 aria-label。已部署。
- R102 [删除] 死特性 用户组(Group): GroupController/GroupService/DnGroupMapper/DnGroupMemberMapper/DnGroup/DnGroupMember 共 6 文件 + PermInterceptor /api/group 规则。证据: GroupService 零外部调用方, /api/group 前端 0 引用, RBAC 用角色非组。E2E: api/group 已移除。
- R103 [删除] 死特性 告警配置(alert-config): AlertConfigController/Service/Mapper/Model 共 4 文件 + /api/alert-config 规则。证据: AlertConfigService 仅自控制器调用、mapper/model 包外零引用、前端 0 引用; 真实告警走 AlertService(webhook)+NotificationService(站内信)。
- 注: dn_group/dn_group_member/dn_alert_config 表保留在库(删表破坏性, 留作历史; 无代码引用无害)。
- 深研结论: 重复功能审计=系统分工清晰无可合并(代理确认); 死代码审计代理 9 控制器报警中 6 个假阳(snippet/data-acl/baseline/cdc/datax/sync-folder 均活)——已逐一 grep 复核, 仅 Group/alert-config 真死并删除。结构性冗余总体很少。
- R104 [增强] 数据地图表清单导出CSV(与治理各列表导出一致): 自定义 renderDmTable 原无导出/复制(治理列表 DN.table 都有)。补 DN.exportRows(可复用CSV导出: BOM+CRLF+注入防护)+ 表清单「导出CSV」按钮。已部署。
- 增强方向核实: profileData(按列错误已 surfaced 非静默)/数据地图搜索(已有计数+空态引导+容错+seq去重)等多处候选经核实=本已良好; 系统功能完整度高, 真实增强点稀少。
- R105 [修] 登录页 favicon 404: login.html 缺 favicon link, 浏览器请求 /favicon.ico 404(每次登录)。补与 workspace 同款 SVG data-URI 图标。
- R106 [真机回归测试] Playwright 实测(admin 登录→home/governance/datamodel/mdm/project/metrics/catalog 7模块→数据地图搜索→表详情交互): **0 个 JS 错误**(仅 favicon 404 已修); 渲染完整; 本会话改动 R3复制表名/R101 aria/R104导出CSV/批28数据授权 真机验证全部生效。结论: 系统真机运行健康, 无运行时 bug。
- R107 [修真bug·业主报告] 登录后闪现"数据开发"再跳首页: 根因=.dev-layout(开发编辑器布局)静态默认可见, 而 dnBootGate 要先异步 /api/auth/status+/api/rbac/me 才 navigateTo, 这段空窗显示默认可见的 dev-layout(viewWelcome active+viewIntegration 无 display:none)。修: .dev-layout 默认 style="display:none"(navigateTo 按路由控制显隐, 页面路由保持隐藏/开发路由显示)。真机验证: 登录落 home, dev-layout=none/viewHome=block/active=home, 无闪现。

## R108 [大功能·并发编辑防护 第1波] 通用编辑锁引擎 + 数据开发脚本落地
- 机制(业主定): 独占编辑锁 + 心跳超时(90s)自动释放 + 保存乐观版本校验, 三重兜底。
- 通用引擎: dn_edit_lock(resource_type+resource_id 唯一) / EditLockService(acquire 抢锁[空闲/超时/本人可得, 否则返 holder]·heartbeat 续锁·release·currentHolder·assertHeld 服务端兜底) / EditLockController /api/edit-lock(登录即可)。单实例 stripe 串行 + DB 唯一键防竞态。
- 脚本落地: ①DnScript 加 @TableField(exist=false) baseUpdatedAt(版本基线, 非库字段) ②ScriptService.save 更新分支 assertHeld('SCRIPT')+版本校验(baseUpdatedAt≠库 updatedAt 则拒) ③前端通用助手 dnEditLockAcquire/Heartbeat(30s)/Release/Banner + beforeunload 兜底释放; openScriptFromTree 打开即抢锁(他人持→只读+🔒横幅+toast)、saveScript 带 baseUpdatedAt 并刷新基线、closeTab 释放。
- 锁服务异常→前端降级可编辑(保存仍有服务端 assertHeld+版本校验兜底)。
- E2E(双会话): admin 抢锁 ok/viewer 抢同脚本 ok:false+holder admin/holder=admin/admin 心跳 true/viewer 心跳 false/admin 持锁+正确版本 save code:0/旧版本 save 拒"已修改"/admin 释放后 viewer 持锁则 admin 保存被拒"正在编辑"。627 测试绿。
- 后续波次(引擎已通用, 每资源约: 打开抢锁+只读横幅 / save assertHeld+版本校验 / 关闭释放): 数据模型 / 质量规则 / 指标 / 数据标准 / 主数据 / 同步任务 / 项目。

## R109 [并发编辑防护 第2波] 数据模型接入编辑锁
- 后端: DataModelService 注入 EditLockService; 在统一改动闸门 assertModelEditable 加 assertModelHeld('MODEL',id) → 一处覆盖 实体/属性/关系/删除 所有子改动; saveModel 更新分支另加 assertHeld + 乐观版本校验(DnModel.baseUpdatedAt 非库字段)。
- 前端: DN.drawer 增 onClose 回调(通用); dmOpenModel 重构为 抢锁→_dmBuildModelDetail(canEdit=状态可编辑&&持锁; 他人持锁→只读+🔒横幅), renderEntities 用 _dmCanEdit 门控实体/属性/关系编辑钮; 抽屉关闭(onClose)/切模块(navigateTo 释放)/关页 释放锁。
- navigateTo 切顶级模块统一释放当前编辑锁(防 closeAllDrawers 直移 DOM 不触发 onClose 致锁残留+心跳不停)。
- E2E: viewer 持 MODEL:41 → admin saveEntity 拒"正在编辑"; 释放后 admin 可抢。627 测试绿。
- 进度: 已接入 脚本(R108)/数据模型(R109)。待接: 质量规则/指标/数据标准/主数据/同步任务/项目。

## R110 [并发编辑防护 第3波] 质量规则 + 指标(乐观版本校验)
- 弹窗式快编辑用乐观版本校验更合适(独占锁锁短表单体验差)。机制=保存时比对 baseUpdatedAt 与库 updatedAt, 不符则拒。
- 后端: DnQualityRule/DnMetric 加 @TableField(exist=false) baseUpdatedAt; QualityController.saveRule / MetricController.save 更新分支版本校验(状态启停不带 baseUpdatedAt 则跳过)。
- 前端: editQualityRule/editMetric 加载时存基线(__qrBaseUpdatedAt/__mBaseUpdatedAt), 保存带上; 新建弹窗清基线。
- E2E: 质量规则 save 旧版本→拒"他人修改"/正确版本→code:0(指标同款逻辑对称)。627 测试绿。
- 进度(8资源): ✅脚本(锁) ✅数据模型(锁) ✅质量规则(版本) ✅指标(版本)。待: 数据标准/主数据/同步任务/项目。

## R111 [并发编辑锁改 Redis] (业主决定)
- 业主决定锁用 Redis(原生 TTL/原子 SETNX, 优于 DB 表)。环境原无 Redis, 新增:
  - 服务器: docker datanote-redis(redis:7-alpine, requirepass 随机+maxmemory 128mb LRU, -p 127.0.0.1:6379, restart unless-stopped); datanote.env 加 REDIS_HOST/PORT/PASSWORD。
  - pom: spring-boot-starter-data-redis; application.yml: spring.redis.*(注: Boot 2.7 用 spring.redis 非 spring.data.redis——踩坑: 配错前缀致连不上密码 NOAUTH 走降级)。
  - EditLockService 重写为 StringRedisTemplate: acquire=SET NX EX(+本人续约可重入), heartbeat/release=Lua(get==me 才 pexpire/del 原子), currentHolder=get, TTL 90s 原生过期。Redis 异常 fail-open(降级可编辑)+版本校验兜底。
  - 删 DnEditLock model/mapper(DB 锁表弃用, sql/90 表保留无害)。
- E2E: admin acquire→redis key=[admin] ttl=90 / viewer→ok:false holder admin / 非持有者 heartbeat=false / release→key 删除 viewer 可抢。627 测试绿。

## R112 [修复+增强] 脚本编辑锁: 连续保存误判 + 获取/释放锁交互
- 修真bug(业主报告): 同一人连续保存第二次报"已被他人修改()"。根因=save 返回内存毫秒精度 updatedAt, MySQL datetime 存秒(截断), 前端基线带毫秒≠库秒→误判。修: ScriptService.save 返回 scriptMapper.selectById(DB真实秒精度行); 并 setUpdatedBy(冲突提示显示修改人, 修空括号)。
- 增强(业主要求): 编辑锁横幅三态+按钮 —— held(本人持有)「释放编辑锁」/ locked(他人持有)「获取编辑权」/ released(本人已释放)「重新获取」。dnScriptReleaseLock(主动释放不关脚本, 他人可接手) + dnScriptReacquire(无人持锁/超时才成功, 否则提示仍被X编辑)。
- E2E: 连续 save1/2/3 均 code:0(不再误判); acquire/release 端点既有验证。627 测试绿。

## R113 [大功能·任务三态生命周期 第1波] 数据开发脚本
- 业主令: 任务三态(未提交/已提交未上线/已上线), 未提交随时改, 提交后须点"编辑"才改; 已提交=提交审批(复用现有 dn_script_change 流), 点编辑→退回草稿需重提。
- 脚本三态推导: scheduleStatus=online→ONLINE / 有 pending 工单→PENDING / 否则→DRAFT。
- 后端: ScriptApprovalService.stateOf + revertToDraft(撤待审工单 + 已上线则 offlineLocal 下线); Controller GET /{id}/state + POST /{id}/revert-to-draft(develop:edit)。
- 前端: 打开脚本先查 state —— PENDING/ONLINE→只读+状态横幅(📤已提交待审/🟢已上线)+「编辑」按钮(dnScriptToDraft: 确认→revert-to-draft→重开为草稿可编辑+抢锁); DRAFT→走并发编辑锁(R108)。提交上线后即转只读"已提交待审"横幅。
- E2E: DRAFT→提交→PENDING→编辑退回→DRAFT→审批→ONLINE→编辑退回(自动下线)→DRAFT 全链路验证。627 测试绿。
- 进度(三态): ✅脚本。待: 同步任务/调度运维/指标/规则。

## R114 [任务三态 第2波] 同步任务
- 同步任务无审批流(无 sync_change 表), 故实为 2 态: 未提交(offline/草稿)/已上线(online); 无 PENDING(需另建同步审批流才有)。
- 后端: SyncJobService.save 更新分支 —— old.scheduleStatus=online 则拒"已上线, 请先下线再编辑"(防改动静默作用于在跑任务)。
- 前端: dbsyncOpenEditModal 拆为门控+_dbsyncRealOpenEdit; 已上线任务点编辑→确认"下线转草稿"→POST /offline→再打开可编辑(改完重新上线)。
- E2E: online 态 save 拒"已上线"; offline 后 save code:0。627 测试绿。
- 进度(三态): ✅脚本(全3态/审批) ✅同步任务(2态/上线门控)。待: 调度运维/指标/规则。
- 备注: 指标/质量规则 已在 R110 有乐观版本校验防丢更新; 若要"上线/提交"语义的三态需各自定义生命周期(指标/规则当前无上线概念, 业主可定)。

## R115 [修真bug·业主报告] 数据开发看不到 提交/编辑 按钮 + 锁只读对 Monaco 不生效
- 业主报告: 数据开发里看不到提交/编辑按钮。三处根因:
  1. openScriptFromTree 打开脚本未调 updateToolbarOnlineBtn → 工具栏「提交上线/提交下线」按钮(默认 display:none)不显。修: 打开时按 res.data.scheduleStatus 设 currentScriptOnline + updateToolbarOnlineBtn。
  2. 编辑器是 Monaco(非 textarea), 我之前的锁/三态 UI 误用 #codeArea(不存在): a) codeArea 是 shim 对象无 readOnly setter → codeArea.readOnly=true 对 Monaco 无效(只读形同虚设); b) 状态横幅锚到 #codeArea(缺失)→ 横幅从不显示。修: 给 codeArea shim 加 readOnly setter→monacoEditor.updateOptions({readOnly}) + create 后应用暂存只读; 横幅锚到 #monacoContainer。
- 真机验证(Playwright): 打开已上线脚本 6 → 横幅"🟢已上线…点编辑"显示 + monacoReadOnly=true + 工具栏"提交下线"。
- 遗留(另记): 首页"最近编辑"快捷项点击未真正打开脚本(currentScriptId 仍 null), 与文件树打开路径不同, 后续修。

## R116 [全局排查·业主报告"ODS不行"] 任务三态/锁 统一所有打开路径
- 业主报告 ODS 不行 + 要求全局检查。排查发现脚本打开有多入口, 仅 openScriptFromTree 应用了三态/工具栏/锁; 其余入口遗漏:
  - **真bug**: openScriptFromTree 首行 el.classList.add 在 el=null 时 NPE → 经"最近编辑"/openScriptById/AI 创建(传 null)打开全部抛错、currentScriptId 不设 → 这些入口打开脚本失效("ODS不行"=经 openScriptById 打开失败)。修: el 空守卫。
  - switchTab(切已开 tab)、ctxNewSqlScript/ctxNewShellScript(新建) 未应用三态/工具栏/锁。
- 统一: 抽 dnApplyScriptLifecycle(scriptId)(查 /state→设 currentScriptOnline+提交上线/下线按钮 + 只读/状态横幅 + 并发锁), openScriptFromTree/switchTab/新建 全部改调它; 切脚本先隐旧横幅防残留。
- 真机验证(Playwright): 在线脚本6→🟢已上线横幅+Monaco只读+提交下线; 草稿216→可编辑+✏️独占横幅+提交上线; openScriptById 各入口正常(此前 NPE 失效)。
- 兼: R115 已修 Monaco 只读(shim readOnly setter)+横幅锚 #monacoContainer。

## R117 [业主令] ODS(同步任务)上线也应用三态只读 + 标签页一键关闭
- 业主报告: ODS 上线的也需要应用这个逻辑 + 新增标签页一键关闭(关闭所有/其他/自己)。
- **ODS 三态修复**: ODS 层节点是 syncTask(走 openSyncTask), 此前只有脚本(openScriptFromTree)应用三态只读, 同步任务在线仍可编辑/保存 → 不一致。
  - 加 dnApplySyncLifecycle(online): 已上线→给 .intg-body 加 .intg-readonly(CSS 置灰映射区 pointer-events:none, 横幅除外)+禁用「保存/一键建表」+顶部绿横幅"🟢 该同步任务已上线，只读。点「编辑」将下线后修改。"+「编辑」按钮; 未上线→放开。
  - loadSyncSchedConfig 拿到 scheduleStatus 后调用; 「编辑」→dnSyncToDraft→proceedOffline(下线后自动 reload 转可编辑)。
  - 运行按钮保留(在线任务允许跑)。
- **标签页一键关闭**: addOrActivateTab 给 tab 绑 oncontextmenu→showTabCtx 浮层菜单(关闭自己/其他/所有)。
  - closeOtherTabs(keep)/closeAllTabs 复用 doCloseTab(已释放脚本编辑锁); 批量关闭任一未保存→单次确认(_closeTabsBatch + _tabUnsaved), 避免逐个弹窗; 关闭其他后 switchTab 回保留 tab。
- 真机验证(Playwright, 服务器 8099): ODS 任务1(在线)→intg-readonly=true+横幅 flex+保存 disabled+文案正确; 开4 tab→右键菜单[关闭自己,关闭其他,关闭所有]→关闭其他余1→关闭所有余0。
- 部署: fast_static.py 推 workspace.html+css/app.css(?v=u61), JAR_UPDATED+服务 active。

## R118 [并发一致性补全] 同步任务(ODS草稿)也接入 Redis 编辑锁
- R117 给同步任务补了三态只读, 但未上线(草稿)同步任务仍无并发编辑锁——脚本草稿有锁、同步草稿没有, 两人可同改最后写覆盖。补齐一致性。
- 锁基建(dnEditLockAcquire/Heartbeat/Release + /api/edit-lock)本就按 type 泛化, 直接复用 type='SYNC':
  - dnApplySyncLifecycle(online): 在线→只读+online横幅(不抢锁, 释放本任务残留锁); 未上线→dnEditLockAcquire('SYNC',taskId) 抢锁, 抢到→独占可编辑+✏️横幅(释放编辑锁), 他人持有→只读+🔒横幅(获取编辑权)。
  - dnShowSyncBanner(online/held/locked/released/null) + dnSyncReacquire/dnSyncReleaseLock, 与脚本横幅同款。
  - 切换任务先释放上一把非本任务锁; doCloseTab 增 SYNC 分支释放锁; switchTab/openSyncTask 经 loadSyncSchedConfig→dnApplySyncLifecycle 统一触发。
- 真机验证(Playwright, 远程库): 草稿路径→独占可编辑+横幅+lock 持有; 释放→只读+"已释放"横幅+lock 清空; 重新获取→独占恢复。(首测因远程抢锁一次往返~1-2s 出现竞态假象, 加长等待后全绿。)
- 遗留: 同步保存的服务端持锁/版本断言(SyncJobService.save)未加, 当前靠前端锁+心跳超时(90s)+"已上线先下线"护栏; 脚本侧 ScriptService 已有服务端断言, 后续可对齐。

## R119 [服务端兜底] ODS同步任务保存 服务端持锁断言 + 在线护栏(防绕过前端)
- 排查发现: 开发树 ODS 同步任务实为 DnSyncTask, 保存走 /api/script/sync-task→ScriptService.saveSyncTask(非 SyncJobService/数据同步模块的 DnSyncJob)。原 saveSyncTask 无在线护栏、无持锁断言——前端只读可被 API 绕过。
- 修 ScriptService.saveSyncTask(更新分支): ①已上线→拒"请先下线再编辑"(服务端兜底三态) ②editLockService.assertHeld("SYNC",id)(他人持锁则拒, 与 R118 前端 SYNC 锁同款; 无人持锁 fail-open 不影响 AI/批量)。
- 顺带 SyncJobService.save(数据同步模块 DnSyncJob)也加 assertHeld, 但用独立锁类型 "SYNCJOB" 避与 DnSyncTask 的 "SYNC" 锁键(同 id 不同表)冲突。
- 真机验证(双 requests 会话, 远程库): 建草稿 DnSyncTask→viewer 抢 SYNC 锁→admin 保存被拒(code -1「viewer 正在编辑该内容, 你暂时无法保存」)→viewer 释放→admin 保存 code 0 成功→删测试数据。
- 部署: mvn package + deploy_jar.py 全量(md5 校验 8041b6b 匹配), 服务 active; SyncJobServiceValidateTest 构造补 null 参过。

## R120 [并发兜底收尾] ODS同步任务 乐观版本校验(对齐脚本) + 指标/规则确认已有
- 补 R119 遗留: DnSyncTask 乐观版本校验(Redis 锁 fail-open/超时时的覆盖兜底)。脚本侧早有, 同步缺。
  - DB: dn_sync_task 加 updated_by 列(sql/91, MySQL 不支持 ADD COLUMN IF NOT EXISTS→运维脚本判存幂等)。
  - DnSyncTask: +updatedBy +@TableField(exist=false) baseUpdatedAt(不入库基线)。
  - ScriptService.saveSyncTask: 更新分支 baseUpdatedAt≠old.updatedAt 则拒"已被他人修改(updatedBy)" + setUpdatedBy + 返回 selectById(DB 秒精度, 防毫秒≠秒连续保存误判); 新增 getSyncTask + GET /api/script/sync-task/{id}(取基线)。
  - 前端: openSyncTask 拉详情存 window.__syncBase[id]=updatedAt; intgSaveTask 带 baseUpdatedAt + 成功后刷新基线。
- 排查确认: 指标(__mBaseUpdatedAt)/质量规则(__qrBaseUpdatedAt)**早有乐观版本校验**(modal 编辑适用), scope 中"指标规则"无需补。
- 真机验证(双 requests, 远程库): 正确基线保存成功(updatedBy=admin, 新基线秒精度) → 旧基线保存被拒(code -1「已被他人修改(admin)」) → 新基线再成功(连续保存无误判) → 删测试。
- 部署: sql/91 应用(列已建) + mvn package + deploy_jar 全量(md5 a4f379f 匹配), 服务 active。

## R121 [业主令·IA重构] 数据同步并入数据运维(改名"数据集成") + 删独立实时日志 + 加强用户管理
- 业主令: ①去掉"数据同步"顶级模块, 并入"数据运维"改名"数据集成"; ②删独立实时日志模块; ③加强用户管理(信息太少)。
- Ultracode: Workflow 4 路并行测绘(导航/dbsync视图/运维视图/用户管理)定锚点 → 串行重构 → Workflow 对抗审查(3维×27 agent)→ 修真实缺口。
- **①②IA 合并**(关键: viewDbSync 与 viewScheduler 同为 ops-layout; dbsync 的模态/抽屉本就是 viewDbSync 的兄弟浮层, 无需迁移):
  - 删顶部"数据同步"header-tab; 运维 ops-sidebar 加"数据集成"项(id=dbsyncJobsNav→switchOpsTab(this,'integration'))+文件夹树(collapsed class 控显隐); 运维 ops-main 加 dbsyncJobsPanel; 删整个 viewDbSync 壳 + 独立实时日志面板(dbsyncLogsNav/dbsyncLogsPanel)。
  - switchOpsTab 加 integration 分支(显面板+按展开态切 folder+dbsyncLoadFolders/Jobs); navigateTo 顶部 dbsync→operations+integration **同步**重定向(消除 overview 闪烁); 删 ROUTES.dbsync/NAV_LABEL.dbsync; switchDbSyncTab 改垫片; 快捷键守卫 viewDbSync→数据集成面板可见; dbsyncRunJob 运行后开任务详情抽屉 log tab。
  - 实时日志函数(dbsyncAppendLog/ClearLogs/ApplyLogFilter/RefreshLogFilter)本就 null 守卫→删面板后自动安全 no-op; 日志仍经 WS→dbsyncDetailAppendLog 入抽屉。权限点 dbsync:* 保留不改名(避免动 PermCatalog/授权)。
- **③加强用户管理**: dn_user 加 6 列(email/phone/department/position/employee_id/remark, sql/92 判存幂等); DnUser 实体 +6 字段; RbacController.listUsers 回传 + createUser/updateUser 服务端校验(邮箱/手机格式+长度上限, 防绕过前端); 前端 umRenderUsers 表格加邮箱/手机/部门/岗位列+搜索扩展; umUserModal 表单加 6 字段; umSaveUser 带 profile; 新增 umViewUser 只读详情卡。
- 对抗审查 27 agent: 确认多为正确/防御到位(日志函数 null 守卫安全·WS链完整·新字段前后端通); 修真实缺口=后端档案校验防绕过 + 深链闪烁同步切换。
- 部署: sql/92 + mvn package + deploy_jar 全量。版本 ?v=u62。

## R122 [业主令·IA] 数据开发根目录规范化(固定6根)+ 根目录创建移至系统管理
- 业主令: 数据开发根目录只保留 数据源/ODS/DWD/ADS/DM/脚本; 用户不能在树中直接新建根目录, 要新建去别处。
- 现状: 根目录是 dn_script_folder(parent_id=0) 行, layer 驱动特殊子节点(数据源→数据源列表/ODS→同步任务)。树右键 ctxNewFolder 本就只建子目录(parentId=目标), 无根新建入口=已满足"不能直接新建根"。
- DB 规范化(sql/93, live已执行): DWS层1脚本迁回"脚本"根→删DWS层→删AI生成脚本E2E/AI脚本两空目录→新增DM层→重排序(数据源/ODS/DWD/ADS/DM/脚本)。
- 前端: 右键菜单 DWD/DWS/ADS/**DM**/DIM 都给 新建文件夹+新建SQL脚本(buildTree else 分支本就支持 DM 显脚本); 系统管理新增"开发目录"tab(loadDevRoots/devRootCreate/devRootDelete): 列出所有根目录, 数据源/ODS/脚本=系统保留不可删, 其余可删(须先清空), 新建根=name+layer→/api/script/folder parentId:0(settings:config 权限)。无后端改动(createFolder 本就允许根)。
- 真机验证(Playwright): 树根目录=6个顺序正确(DWS+AI已清); 系统管理开发目录列6项+保留/可删标识正确。fast_static 部署 ?v=u63。

## R123 [业主令] 新建目录要有排序
- 业主令: 新建目录要有排序。
- 后端: createFolder 未指定 sortOrder 时取同级 max(sort_order)+1 追加末尾(避免默认0抢前); 新增 updateFolderSort + POST /api/script/folder/sort; buildTree 节点补 sortOrder 回传。
- 前端(系统管理·开发目录): 新建表单加"排序"数字输入(留空则末尾); 列表加可编辑排序列(改值 onchange→/folder/sort 即时保存+重载树)。
- 部署: mvn package + deploy_jar 全量, ?v=u64。

## R124 [业主令] 数据集成目录改用数据开发树风格 + 逐项展开收起 + 自由建目录
- 业主令: 数据运维·数据集成的目录样式和逻辑保持和数据开发类似(但可自由新建目录), 展开收起也参考数据开发。
- 现状: 数据集成 folder 已支持层级(DnSyncFolder.parentId)+CRUD+按folder过滤job, 但用自有扁平样式(.dbsync-folder-nav-item)、整块折叠(无逐项展开)、内联操作按钮。
- 改(纯前端, 无后端): 重写 dbsyncRenderFolderTabs/dbsyncRenderFolderNodes 为数据开发树风格(.tree-folder/.tree-folder-header/.arrow/.folder-icon/.tree-children + 缩进), 复用全局 CSS。
  - 逐项展开收起: 箭头点击 dbsyncTreeToggle(toggle .open)+localStorage(INTEG_FOLDER_OPEN_KEY)持久化, 同数据开发 toggleFolder 机制。
  - 点文件夹名/图标→dbsyncSelectFolder(选中过滤job+高亮.active+自动展开); 顶部"全部"项; 底部"新建文件夹"(自由建根); 右键菜单 dbsyncFolderCtx(新建子文件夹/重命名/删除)同数据开发交互。
  - CSS 补 .tree-folder-header.active 高亮 + .leaf 箭头隐藏 + .tree-count 计数徽章。
- 真机验证(Playwright): 树风格渲染/箭头展开收起/子目录显隐/选中高亮/右键菜单三项 全绿。fast_static ?v=u65。

## R125 [业主报告·修] 数据集成目录去掉"全部"+精确过滤 + 修复删除无反应
- 业主报告: ①去掉"全部", 点文件夹只显示对应文件夹内容; ②文件夹点删除没反应。
- ②根因: R124 的 dbsyncCloseFolderCtx 用普通 function 声明, 但 workspace.html JS 在闭包内, 内联 onclick 在全局作用域解析→找不到→ReferenceError→整个删除 onclick 中断(同 R124 switchOpsTab 跨作用域同源教训)。修: 改 window.dbsyncCloseFolderCtx + addEventListener 引用 window.x。
- ①: dbsyncRenderFolderTabs 去掉"全部"项(仅渲染文件夹树, 空时提示); dbsyncApplyFolderFilter 改精确匹配(j.folderId===selected, 不再含后代 scopeIds), 未选(0)=未归类任务。
- 真机验证(Playwright): 无"全部"项; 右键删除→确认弹窗→确定→文件夹真删(delTestGone); 精确过滤生效。fast_static ?v=u66。
- 教训复记: workspace.html 内联 onclick/HTML 引用的函数必须挂 window, 闭包内 function/var 不可见(已第3次踩, 写入记忆)。

## R126 [业主令·参考原型] 完善指标管理: 指标详情驾驶舱(对标 base44 MetricDetail)
- 业主令: 参考原型 MetricDetail 完善指标管理模块。原型=指标详情运营驾驶舱(KPI头卡/AI解读/业务图谱/转化漏斗/异常归因树/异常预警诊断/预测/推荐分析)。
- 测绘: 我方后端很全(DnMetric定义+DnMetricValue时序history+DnMetricAlertRule阈值+DnMetricRef血缘+inputQuality质量+消费审计), DN组件有 line/donut/bars/statRow/card/pill/delta; 缺 target_value字段/聚合详情端点/DN.forecast。
- 后端: DnMetric+target_value(sql/94); 新增 MetricDetailService.detail(id) 聚合(当前值latest/目标/达成率/环比MoM[上期]/同比YoY[≈一年前±45天]/线性回归预测下期/趋势序列/告警越界诊断[复用 MetricAlertService.isBreach]/输入质量/血缘refs/同分类相关指标); GET /api/consumption/metric/{id}/detail。
- 前端: 新增 DN.forecast(历史实线+预测虚线+目标线 SVG); viewMetrics 加 metricsDetailPanel 详情面板; 指标名/「详情」按钮→openMetricDetail; renderMetricDetail 渲染对标原型: 返回+头卡(名称/启用·预警徽章/分类·负责人·编码/口径/立即计算·编辑·AI深度解读)+KPI七磁贴(当前/目标/达成率/环比/同比/预测/更新时间)+智能解读(模板化叙述)+趋势与预测图+异常与预警诊断(规则越界卡+环比/同比下降+输入质量信号)+指标血缘(来源→指标→消费)+相关指标; 编辑表单加目标值; switchMetricsTab 切签隐藏详情。
- 部署: sql/94 + mvn package + deploy_jar 全量, ?v=u67。待真机验证(设目标+计算造history)。

## R127 [业主令] 加强指标溯源 + 指标预警
- 业主令: 在 R126 指标详情驾驶舱基础上加强 指标溯源、指标预警。
- 后端(MetricDetailService.detail 扩展, 无新表): +alertIssues(该指标自动建的治理工单 issue_type=METRIC/object_ref=metric:id, 近10条=预警历史) +consumers(dn_consumption_log 近90天按消费方聚合 Top8=下游消费方) +trace(calcFormula+最近一次计算 calcSql/耗时/状态=计算溯源) +alert.rules 项补 ruleId(供前端编辑/删除)。预警自动建工单+通知 owner+恢复关单 R114 已具备, 本轮做前端可管理。
- 前端(详情页): 异常与预警诊断 → 加「+新建预警规则」+每条规则 编辑/删除(复用 /alert-rule/save、DELETE /alert-rule/{id}, 7 比较符+区间+3 严重度弹窗) + 预警历史表(工单#/严重度/状态/时间, 点击下钻治理工单); 指标血缘 → 升级「指标溯源」: 上游来源/维度表→指标→结果/消费 + 下游消费方 pill(近90天) + 计算溯源(公式 + 最近计算 SQL/耗时)。
- 部署: mvn package + deploy_jar 全量, ?v=u68。待真机验证。

## R128 [业主令·Ultracode] 全模块深度审计 → 整合(合并/增强/移除)
- 业主令: 深度探索所有模块, 类似功能合并/薄弱增强/孤立移除。
- 方法: Workflow 三阶段(11模块并行测绘→综合候选→对抗验证 grep static 防假阳), 15 agent。
- **结论(实事求是)**: 系统健康/成熟, 11模块分工清晰, 印证 structural-audit 前结论。
  - **移除**: 唯一候选(datamodel 4 死库列 fk_attr_id/ref_entity_id/dict_code/parent_entity_id)对抗验证 confirmed=false — 确为死列但 DROP 迁移风险>收益, 仅标注预留, 不动。测绘报的其余"死码"均假阳(dashboard端点被smLoad用/baseline属运维/projOpenDetailById已定义)——再证"前端动态拼URL/跨模块归属致grep漏报"陷阱。→ 无安全可删。
  - **合并**: 2候选(首页待办并 mySubmissions/myIssues、指标refs三处渲染统一)对抗验证均 confirmed=false(已wired正常, 合并破坏口径/联动)。→ 无值得合并真重复。
  - **增强**: 7项真薄弱(无需验证)。复核纠正1假阳(脱敏: preview 已 fail-closed 打码、profile 只返回统计量无原始值=非fail-open)。
- **本轮执行 S2(高价值低成本): 指标新鲜度/僵尸信号浮现**(后端 freshness/zombies/overview 早有, UI未消费): 指标列表加「新鲜度」列(最新/陈旧Nh/未取值 pill)+新鲜度筛选(陈旧/最新/未取值)+ 详情驾驶舱「更新时间」磁贴加陈旧/最新徽章。纯前端(stale 按 updatedAt+26h 前端算)。真机验证列/筛选/徽章。?v=u69。
- 遗留增强 backlog(已验真薄弱, 后续做): 运行日志分页+关键字+截断(防OOM,high)/首页工单卡按状态分布(low)/基线关联任务校验+选择器/血缘重建保留手工边/datamodel派生质量规则闭环(确认default status=0)。

## R129 [Ultracode·整合backlog] 薄弱功能增强批1
- 续 R128 审计 backlog, 做实3+1项薄弱(均先 grep 复核确为真薄弱):
  - **datamodel 派生质量规则闭环**: deriveQualityRules 派生规则 status 0→1。派生发生在 publishToAsset(物理表已落地)时, 直接启用使 建模→质量 闭环(原停用态用户感受不到价值)。
  - **运行日志查询加固(防OOM)**: TaskSchedulerService.getRunLog 重载(keyword 关键字按行过滤 + maxBytes 上限默认256KB, 超限保留尾部最新+标注截断); ScheduleMonitorController /run-log 加 keyword/maxBytes 参数。(日志存 DB 列, 原整返超大日志拖累)
  - **基线关联任务健壮性**: BaselineController.create taskId 缺失/非 Number 跳过(防 NPE + 脏关联致 SLA 误报)。
  - **首页"待处理工单"卡**(原"最新治理工单"只显 OPEN): 改取 OPEN+FIXING, 头部并排各态计数(N开放/M处理中)+表内状态列, 标题改"待处理工单", 治理进度可感知。
- 部署: mvn package + deploy_jar 全量, ?v=u70。待真机验证。
- 剩余 backlog: 血缘重建保留手工边(medium, 较复杂)/基线任务选择器UI(medium)。

## R130 [UI重构1000轮·第1轮] 全局现代化 polish 层
- 新目标(业主令): UI大师深研→全系统重构, 更现代/人性化/易用, 1000轮, 可任意增删改组件(不增报表类)。
- 调用 redesign-existing-projects 技能(审计优先/沿用现有栈/不破坏功能/小步)。研究: 设计系统已成熟(dn-design.css Slate&Indigo 全令牌+圆角阶+分层染色阴影+暗色+tabular-nums), 非通用AI风→演进而非重写。
- 第1轮(最高杠杆·低风险): 新增 css/modern.css(末位加载, 纯增量不改布局密度): ①字体 Geist 打头+CJK系统回退(数字/拉丁更现代, 中文不变); ②微交互(按钮 hover抬升+按压回弹/卡片悬浮抬升染色阴影/KPI磁贴抬升); ③focus-visible 键盘焦点环; ④现代细滚动条; ⑤平滑滚动+选区色; ⑥prefers-reduced-motion 尊重。
- 真机验证(Playwright): Geist 加载/modern.css 挂载/平滑滚动/按钮过渡 全绿; 首页截图无破坏。?v=u71。
- 后续轮: 逐视图 UX 人性化(登录/导航/空态/骨架/各模块体验), 现代体验组件(命令面板/侧滑/上下文动作, 非报表)。

## R131 [UI重构·第2轮] 登录页现代化
- 登录页(独立 login.html, 首印象)重设计: Geist 字体; 左品牌区网格渐变(indigo/slate radial mesh)+辉光+点阵+SVG颗粒(破平面)+价值特性chip(数据开发/治理质量/指标消费/数据集成); 右表单图标前缀输入+密码显隐eye切换+渐变主按钮(加载spin)+图标化内联错误; 入场动画(shell/字段 staggered)。保留全部 JS 逻辑与 ID。
- 真机验证(Playwright): 截图现代化无破坏; 密码显隐切换正常; admin 登录成功跳 workspace。?v=u71(login)。

## R132 [UI重构·第3轮] 全局命令面板(Ctrl+K) + 深色模式
- 现代体验组件(非报表): ①全局命令面板 dnCmdK(Ctrl/⌘+K 任意位置, glass+blur, 模糊搜索, 11模块前往+常用操作[新建指标/新建集成任务/AI/退出]+主题, ↑↓导航 Enter 执行 ESC关); ②深色模式 dnToggleTheme(令牌级 data-theme=dark 全站自动换色, localStorage 持久 + 早期内联 boot 防闪)。
- 头部新增 ⌘K 命令面板按钮 + 主题切换按钮; Ctrl+K 由原"数据集成快切"改为全局命令面板; 修 AI 按钮误标 Ctrl+K。
- 真机验证(Playwright): 面板16项/搜索过滤; 主题暗色持久/可切回/截图全站换色完美(导航/KPI/图表适配)。?v=u72。

## R133 [UI重构·第4轮] 数据开发欢迎页现代化
- viewWelcome 重设计(核心模块首屏): 渐变 hero(indigo+点阵纹理)+ Ctrl+K 快速导航提示 + 新建脚本玻璃按钮; 4 图标磁贴(脚本/上线/今日执行/失败, 类化享统一 hover 抬升); 3 左强调动作卡(hover 左边框点亮); 最近编辑列表(带图标标题)。移除重复内联 hover, 改 modern.css 类(.wlc-*)。
- 真机验证(Playwright): hero/4磁贴/3动作卡/⌘K提示渲染; 截图现代无破坏。?v=u73。

## R134 [UI重构·第5轮] 全局数据表现代化
- modern.css 加: 数据表(.quality-rules-table/.dbsync-exec-table 全站 metric/user/sync 共用)粘性表头(滚动表头不丢)+ 行 hover 左强调(inset 3px 主色)+ 列表容器圆角。纯 CSS 增量, 低风险高覆盖。
- 真机验证(Playwright): 指标列表表头 position:sticky 生效。?v=u73(css)。

## R135 [UI重构·第6轮] Toast 通知现代化
- showToast 重写: 原单条居中(多条重叠)→ 右上角 #dnToastStack 堆叠容器(多条不重叠), 玻璃卡(bg-card+彩色左强调3px+彩色圆形图标)+ 从右滑入(cubic-bezier)。全模块通用, 保留签名 showToast(msg,type)。
- 真机验证(Playwright): 3条同时堆叠 top/right 18px 不重叠。?v=u74。

## R136 [UI重构·第7轮] 记住上次所在模块(人性化)
- navigateTo 持久 currentRoute 到 localStorage('dn-last-route'); parseRoute 在无 hash(直接打开/刷新)时恢复上次模块, 无记录则首页。重进应用回到上次工作处, 减少重复导航。
- 真机验证(Playwright): 进指标→saved=metrics; 模拟无 hash parseRoute()→metrics。?v=u75。

## R137 [UI重构·第8轮] 全局表单控件统一聚焦光环
- modern.css: 全站输入/下拉/文本域(.prop-input/.dbsync-form-input/.iw-form-input/.intg-*/.g-modal-input/.ds-form-row input/quality-modal/textarea)聚焦统一 主色边框 + 3px 柔光环(原各处仅变边框无环, 不一致)。
- 坑: 首版选择器含 input:not([type=checkbox]):not(...):focus 复杂链疑被丢弃→改纯类列表; headless 程序化 .focus()+getComputedStyle 不渲染 :focus(连既有规则都不反映), 须真实 Playwright click 验证。
- 真机验证(Playwright 真实 click): 聚焦输入 box-shadow=主色3px光环 + border 主色。?v=u77。
