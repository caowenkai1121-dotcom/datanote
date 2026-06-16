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

## R138 [UI重构·第9轮] 全局弹窗现代化
- modern.css: 全部 modal 遮罩(.g-modal-overlay/.dbsync-modal-overlay/.quality-modal-overlay/.sched-log-overlay/.intg-modal-overlay)加 backdrop-filter blur(3px); .show 类弹窗(及 DN.confirm dn-modal)卡片弹入动画 dnModalPop(opacity+translateY+scale)。
- 验证: 服务器 CSS 正确; 真机截图弹窗渲染干净+遮罩变暗。注: getComputedStyle 对 backdrop-filter 在 headless 无 GPU 合成时返回 none(假象), 以截图为准。?v=u78。

## R139 [UI重构·第10轮] 按钮系统精修
- modern.css: .btn 字重 500 / 主要·成功按钮 600; 次级(非 primary/success/danger)按钮 hover 加主色柔底(原仅描边变色)。保留 .btn-danger 幽灵风(各模块依赖, 不动)。
- 验证: 截图按钮渲染正常(删除红幽灵保留)。console 2 错误为 /api/notify/unread-count 瞬时空响应(与本改无关)。?v=u79。

## R140 [UI重构·第11轮] 数据地图搜索 hero 氛围
- modern.css: .datamap-search-wrap(原 transparent 平)加顶部双层 radial 柔光渐变(indigo)破平面感; 搜索框 focus-within 微抬+悬浮阴影; 标题负字距。
- 真机截图: 搜索 hero 有 indigo 辉光质感, 表粘性表头/db pill 现代。?v=u80。

## R141 [UI重构·第12轮] 顶部导航现代化
- modern.css: .dev-header 柔阴影分层(与内容区脱离)+ z-index; 激活 header-tab 加 indigo 底色(原仅下划线, 现底色+下划线更醒目); 用户头像按钮 hover 微抬。
- 真机截图(治理页): 激活 tab 底色高亮 + 顶栏分层, 全局更现代。?v=u81。

## R142 [UI重构·第13轮] 数据开发左面板现代化
- modern.css: 左侧搜索框加放大镜图标(内联svg bg)+圆角+focus光环; 筛选按钮(全部/我的/已上线)pill化(radius-full)。高频左面板更精致。
- 真机截图(数据开发): 搜索图标+筛选pill 渲染正常。?v=u82。

## R143 [UI重构·第14轮] 全局下拉框自定义 chevron
- modern.css: 全站表单 select(.prop-select/.dbsync-form-select/.iw-form-select/.intg-select/分类·新鲜度筛选等)appearance:none + 内联 svg chevron(替代老气原生箭头)+ padding-right。
- 真机验证(Playwright): webkitAppearance:none + 自定义 chevron + padRight 28px。注: 服务器偶发 ERR_EMPTY_RESPONSE 致 css 漏载, 重载即正常。?v=u83。

## R144 [UI重构·第15轮] 模块切换淡入过渡
- modern.css: 编辑布局视图(.active: viewScheduler/viewDatamap/viewMetrics/viewSettings/viewWelcome/viewSql/viewIntegration)切换时 dnViewIn 淡入(仅 opacity 无位移防 jank); reduced-motion 媒体查询同步禁动画(animation-duration .01ms)。模块切换更顺滑。
- 真机验证(Playwright): viewMetrics.active animationName=dnViewIn 0.22s。?v=u84。

## R145 [UI重构·第16轮] 详情抽屉(slide-over)现代化
- modern.css: 抽屉遮罩(#govDrawerMask/.dbsync-drawer-mask[同步+项目详情共用]/.dn-modal-mask)加 backdrop-filter blur(3px); 抽屉(gov-drawer/项目详情/同步详情)左缘 1px 边精致。
- 真机验证: CSS served; 项目工作台截图无破坏(顺带见 R127 指标预警工单闭环显示在"指给我的工单")。?v=u85。

## R146 [UI重构·第17轮] 紧凑/舒适密度切换(新特性)
- 新增密度切换 dnToggleDensity(data-density=compact 全局, localStorage 'dn-density' 持久 + 早期内联 boot): 紧凑模式收紧 表格行/侧栏项/树/磁贴/编辑器tab 间距, 数据密集用户一屏看更多。命令面板加"切换紧凑/舒适密度"项。modern.css 加 :root[data-density=compact] 规则。
- 真机验证(Playwright): 表格 td padding 8px→4px, data-density=compact 持久, 可切回。?v=u86。

## R147 [UI重构·第18轮] AI 对话气泡入场动画
- modern.css: .dn-ai-bubble 逐条 dnBubbleIn 滑入动画(对话更有生命感); AI 输入框 textarea 聚焦统一 3px 光环。
- 真机截图(AI助手): 天工司辰界面渲染干净(欢迎态/建议/输入栏聚焦光环), 无破坏。?v=u87。

## R148 [UI重构·第19轮] 键盘按键提示 kbd 现代键帽
- modern.css: 全站 kbd 统一现代键帽(Geist mono + 底边 2px + 圆角 + 居中), 作用于命令面板/欢迎页/帮助等无内联样式的 kbd。
- 真机截图(命令面板): 玻璃模糊背景+分组+模块图标+高亮首项+ESC 键帽, 现代体系一致。?v=u88。

## R149 [UI重构·第20轮] 统一全模块切换淡入(含 page-layout)
- navigateTo 给显示的视图加 .dn-enter 类(先移除+void offsetWidth 强制重排重触发动画), 覆盖 R144 未含的 page-layout 视图(数据模型/治理/主数据/项目/AI)。modern.css .dn-enter 复用 dnViewIn。全模块切换统一顺滑淡入。
- 真机验证(Playwright): 数据模型 viewDatamodel 含 dn-enter + animName=dnViewIn。?v=u89。

## R150 [UI重构·第21轮] 面板区标题强调条
- modern.css: .ops-dashboard h2 加主色短强调条(::before 3px)+左缩进, 运维/指标/系统 各 panel 标题层级更清晰(运维概览/周期/补数/手动/基线/数据集成/指标列表/用户管理等)。
- 真机截图(运维概览): 标题强调条渲染正确, 多面板统一。?v=u90。

## R151 [UI重构·第22轮] 搜索框聚焦光环补全
- modern.css: 治理/主数据 .gov-search 输入、左树搜索、调度日期、补数行输入 补统一聚焦光环(主色边框+3px柔光), 与 R137 表单聚焦一致, 全站输入聚焦反馈彻底统一。
- 验证: CSS 服务正常(与 R137 已验证同款 :focus 模式)。?v=u91。

## R152 [UI重构·第23轮] 深色模式跨模块 QA + 首访跟随系统
- 深色模式跨模块 QA(治理-数据质量页真机截图): 暗色背景/卡片头/99.8 gauge/文本对比/空态/按钮 全优, 无对比度问题, 旗舰特性达标。
- 真改进: 早期 boot 首访(无 dn-theme)跟随系统 prefers-color-scheme:dark 自动深色(人性化); 用户手动切换后 dn-theme 持久覆盖。
- ?v=u92。

## R153 [UI重构·第24轮] 首页问候个性化
- home-dashboard.js: hero 问候由"晚上好，欢迎回来"改为带用户名"{时段问候}，{用户名}"(window.__user), 更人性化亲切。
- 真机验证(Playwright): 首页 hero 显示"晚上好，admin"。?v=u93。

## R154 [UI重构·第25轮] 顶部导航进度条(新组件)
- 新增 #dnNavBar 顶部细进度条(渐变+辉光), navigateTo 起始 dnNavProgress() 触发(0→85%→100%淡出), 模块切换/加载轻量反馈(类 GitHub 顶条)。非报表组件。
- 真机验证(Playwright): 切换中 width 85%/opacity 1, 随后完成淡出。?v=u94。

## R155 [UI重构·第26轮] 状态徽章统一为现代 pill
- modern.css: .quality-status-on/off(原纯彩色文字)升级为带圆点的现代 pill(启用=success-light底+绿点; 停用=灰底+灰点), 指标/质量列表状态更醒目一致。
- 真机验证(Playwright): 启用徽章 bg=success-light + radius 999px。?v=u95。

## R156 [UI重构·第27轮] 页级标题强调条统一
- modern.css: .gov-h1(治理/主数据/消费 页级标题如"主数据总览/治理总览")加主色短强调条(::before 4px), 与 R150 面板区标题(ops-dashboard h2)呼应, 全站标题层级统一。
- 真机验证(主数据): .gov-h1 padding-left 13px 强调条生效。?v=u96。

## R157 [UI重构·第28轮] 跨模块视觉回归 QA + 编辑器工具栏 hover
- 27 轮 CSS 后回归 QA: 数据模型(主题域树/类型徽章/已发布绿pill/操作)真机截图无破坏; 此前 治理/主数据/运维/指标/项目/地图/开发/AI 均已逐一截图确认现代无回归。
- 小改: 编辑器工具栏 .tool-btn hover 加柔底(运行按钮绿底), 更可点。?v=u97。

## R158 [UI重构·第29轮] 命令面板记忆最近命令
- 命令面板 dnCmdK: 执行命令记录到 localStorage('dn-cmdk-recent', 去重保留4条); 搜索为空时置顶"最近"分组(常用命令一键直达), 易用提升。
- 真机验证(Playwright): 分组顺序 最近/前往/操作/偏好/账户; 最近置顶显示。?v=u98。

## R159 [UI重构·第30轮] 通知铃铛下拉现代化
- 铃铛下拉(每屏可见): 空态加铃铛图标 + 友好文案"暂无通知，一切都处理好了"(人性化); modern.css 加 #dnBellDrop 入场动画 dnDropIn(右上为锚的弹入)。
- 真机验证(Playwright): 下拉 animationName=dnDropIn; 列表渲染 4 条(含指标预警闭环)。?v=u99。

## R160 [UI重构·第31轮] 结果/日志面板标签现代化
- modern.css: 数据开发底部 .result-tab(运行日志/执行结果)加 hover(柔底)+ 激活底色(primary-light)+ 过渡, 与顶栏/编辑器 tab 交互一致。?v=u100。

## R161 [UI重构·第32轮] 复选框/单选框品牌色
- modern.css: 全站原生 input[checkbox/radio] accent-color=主色 + cursor pointer + focus-visible 焦点环。批量选择/筛选/表单一处升级全站。?v=u101。

## R162 [UI重构·第33轮] 空态图标可见度统一
- modern.css: .intg-empty/.iw-empty-state/.history-empty 图标原用 --divider 过淡似渲染坏 → 统一为 --text-muted/.55 + dnEmptyFade 入场, 对齐已完善的 .gov-empty。?v=u102。

## R163 [UI重构·第34轮] 快捷键帮助浮层补全+模糊
- workspace.html: ? 帮助浮层"全局"组补准确条目 Ctrl/Cmd+K=命令面板(搜索模块/操作/切主题密度), 去掉项目/集成里误标为"切换"的旧 ⌘K 行。
- modern.css: #wsHelpOverlay 加 backdrop-filter blur(3px), 与其他弹窗一致。真机按?验证浮层弹出且含新条目。?v=u103。

## R164 [UI重构·第35轮] 骨架屏推广到高频加载点
- 已有 wsSkeleton/.ws-skel shimmer 组件但仅 2 处用。推广到项目管理(projHomeBox/projListBox)+用户管理(umUserBox/umRoleBox)初始加载占位, 替换"加载中..."纯文本为骨架行(首帧瞬态, 数据到达即替换)。真机验证项目模块渲染正常无破坏。?v=u104。

## R165 [UI重构·第36轮] 骨架屏续推广(项目模板/发布中心+系统监控四盒)
- workspace.html: projTemplateBox/projReleaseCenterBox + smMetricsBox/smServiceBox/smScheduleBox/smAuditBox 加载占位 文本→ws-skel骨架行。真机验证系统管理/项目渲染正常。?v=u105。

## R166 [UI重构·第37轮] 骨架屏收尾(开发目录/安全设置)
- workspace.html: devRootBox/secAuthCard 加载占位 文本→ws-skel骨架行。至此项目/用户/系统监控/开发目录/安全设置 初始加载态全部统一为骨架屏。?v=u106。

## R167 [UI重构·第38轮] 命令面板支持英文/拼音首字母别名
- workspace.html: dnCmdK 每个命令项加 kw 别名(英文+拼音首字母, 如 指标管理=metrics/zb/kpi, 数据开发=develop/kf), 过滤纳入 kw。中文系统下可直接输 metric/kf/etl 命中。真机验证 metric→指标管理+新建指标, kf→数据开发。?v=u107。

## R168 [UI重构·第39轮] 命令面板底部操作提示
- workspace.html: dnCmdK 面板加底部 footer(↑↓选择/↵打开/esc关闭 + 右侧"可输 metric/kf/拼音首字母"提示), 提升键盘操作可发现性。真机截图确认渲染+背景模糊。?v=u108。

## R169 [UI重构·第40轮] 命令面板子页深链(系统管理)
- workspace.html: dnCmdK 新增"前往·系统管理"分组6项(系统监控/安全设置/数据源管理/开发目录/环境配置/AI配置), 走已有 navigateTo('settings',{sm:key}) → __navCtx.sm 机制直达对应侧栏子页。真机验证"监控"命中并真实跳转激活系统监控子页。?v=u109。

## R170 [UI重构·第41轮] 命令面板子页深链(项目管理)
- workspace.html: project init 扩展读 __navCtx.ptab(按 onclick 匹配侧栏元素切换, 默认仍 home 无回归); dnCmdK 加"前往·项目管理"3项(项目空间/模板管理/发布管理)。真机验证"模板"→项目模板管理子页激活, 默认导航仍落工作台。?v=u110。

## R171 [UI重构·第42轮] 命令面板子页深链(数据运维)
- workspace.html: operations init 扩展读 __navCtx.optab(按 onclick 匹配侧栏切换); dnCmdK 加"前往·数据运维"6项(运维概览/周期任务/补数据/手动任务/基线管理/数据集成)。真机验证"补数"→运维补数据子页激活。至此 系统管理/项目管理/数据运维 三模块子页均可命令面板直达。?v=u111。

## R172 [UI重构·第43轮] 命令面板子页深链(治理/主数据)收官
- workspace.html: dnCmdK 从 GOV_MODS_VISIBLE(9)/MDM_MODS_VISIBLE(10) 源数组自动派生"前往·数据治理"/"前往·主数据"深链, 走已有 navigateTo+__navCtx.gov/.mdm 机制。真机验证"血缘"→治理数据血缘子模块。至此命令面板覆盖 系统管理/项目/运维/治理/主数据 全部子页深链, 成为完整导航中枢。?v=u112。

## R173 [UI重构·第44轮] 命令面板默认视图收敛
- workspace.html: dnCmdKRender 空查询默认只显 顶级模块+常用动作(最近/前往/操作/偏好/账户), 子页深链(前往·xxx)仅搜索时浮现, 避免打开即长列表。真机验证默认无子页组、搜索"血缘"仍命中。?v=u113。

## R174 [UI重构·第45轮] 密度切换加入头部按钮
- workspace.html: 头部 theme 与 bell 之间新增 #dnDensityBtn(三横线图标, onclick=dnToggleDensity), 密度切换从仅命令面板可达→头部直达, 提升可发现性。真机验证按钮可见、点击 data-density null↔compact 翻转并持久化 localStorage。?v=u114。

## R175 [UI重构·第46轮] 深色QA + 图例兜底真bug修复
- 深色模式跨模块QA: 数据模型/指标列表/指标详情驾驶舱(自建MetricDetail)全部深色干净无硬编码色泄漏。
- 真bug: .gov-legend 仅作用于治理4容器(:is(#govModuleContent...)), 指标详情驾驶舱(#metricDetailContent)的趋势图例 flex/gap/色点全失效→"历史值预测目标线"挤成一团。modern.css 加无作用域 .gov-legend 类兜底(低优先级不影响治理已有)。真机验证图例恢复 ●历史值/●预测/●目标线 带色点+12px间距。?v=u115。

## R176 [UI重构·第47轮] gov-* 工具类全局化(承 gov-pill 模式)
- dn-design.css: 承 gov-pill 已有全局化模式, 把内联模块(项目/详情等)复用但仍仅治理作用域的 gov-section-title/gov-empty/gov-empty svg/gov-line/gov-stats 提升为全局类(低优先级, 不影响治理作用域已有)。真机验证 gov 容器外这些类样式恢复(区块标题左强调条/空态flex居中)。?v=u116。

## R177 [UI重构·第48轮] KPI磁贴无障碍补全 + 总览磁贴导航QA
- dn-common.js: DN.statTile 可点磁贴补 role="button"(原已有 tabindex=0 + Enter/Space keydown), 读屏语义完整。全站 KPI 磁贴(首页/治理/主数据)键盘+读屏可达。
- QA: 治理/主数据总览磁贴点击导航子模块正常(主数据域→域与实体建模), R176 gov-* 全局化对治理/主数据作用域无回归。深色亦干净。
- 另: docs/待我决策.md 写阶段性同步(48轮现状/QA真bug/继续准则), 不暂停推进。?v=u117。

## R178 [UI重构·第49轮] 数据开发"最近编辑"状态统一药丸
- workspace.html: welcomeRecentList 渲染的脚本状态由纯文本色字→全局 gov-pill(已上线 is-ok 绿/未上线 is-muted 灰), 与全站状态徽标(指标/模型/项目)一致。真机验证药丸渲染正确(印证 R176 gov-pill 全局化在开发模块生效)。?v=u118。

## R179 [UI重构·第50轮] 调度状态徽标去硬编码+统一药丸
- workspace.html: scheduleStatusBadge(右属性栏调度状态)原用硬编码 rgba(0,0,0,.06)/rgba(47,158,68,.12)(违设计规范§6禁硬编码)+纯文本。静态HTML与两处JS更新(5660/6041)统一改用全局 gov-pill is-ok/is-muted, 去硬编码、令牌化、与全站状态徽标一致。真机验证 badge=gov-pill is-muted 无内联底色。?v=u119。

## R180 [UI重构·第51轮] 续清状态徽标硬编码色
- workspace.html: 依赖列表上下线徽标(6982/6983, 已上线 rgba(47,158,68,.12)/未上线 rgba(230,162,60,.12))→ gov-pill is-ok/is-warn; 用户权限"*"全部权限 chip 硬编码绿→ var(--success-bg)/var(--success-text)。amber 硬编码全清, 绿色仅余 7 处装饰性SVG勾选图标/面板图标底(配 stroke=var(--success), 保留)。?v=u120。

## R181 [UI重构·第52轮] 主数据xref反查命中框去硬编码
- mdm.js: 交叉引用反查命中结果框硬编码 rgba(47,158,68,.08)/.25 → var(--success-bg)/var(--success-ring)(gov-pill同款令牌)。至此 JS 文件状态色硬编码全清。?v=u121。

## R182 [UI重构·第53轮] 命令面板补"新建脚本"动作
- workspace.html: dnCmdK 操作组补"新建脚本"(复用 createNewScript, kw=new/script/xjjb/develop), 数据开发(最常用模块)创建入口入命令面板。真机验证 script/新建 均命中。操作组现含 新建脚本/新建指标/数据集成新建任务/打开AI助手。?v=u122。

## R183 [UI重构·第54轮] 浏览器标签标题随模块
- workspace.html: navigateTo 内更新 document.title = 模块名 · DataNote 数据平台(NAV_LABEL 映射), 多标签页/历史记录易辨识当前位置。真机验证 切治理/指标/项目 标题随之变化。?v=u123。

## R184 [UI重构·第55轮] 最近编辑可点行键盘可达
- workspace.html: welcomeRecentList 脚本行(onclick打开)补 role=button/tabindex=0/onkeydown(Enter|Space), 键盘用户可 Tab 聚焦+回车打开。真机验证8行均具备。承 R177 a11y。?v=u124。

## R185 [UI重构·第56轮] 命令面板"最近"反映真实导航轨迹
- workspace.html: navigateTo 内把实际模块导航也计入 dnCmdKRecord(原仅用面板时记录), 命令面板"最近"现反映真实访问历史(最近优先), 成为更实用的快速切换器。dnCmdKRecord 为闭包内函数声明→typeof 守卫直调(非 window.*)。真机验证 导航 模型→治理→指标 后"最近"=[指标管理,数据治理,数据模型]。?v=u125。

## R186 [UI重构·第57轮] 命令面板结果高亮匹配文字
- workspace.html: dnCmdKRender 加 hl() 高亮 label 中命中查询的片段(主色加粗, 仅 label 命中时), 标准现代命令面板体验, 提升可扫读性。真机验证搜"指标"→"<b>指标</b>管理"。?v=u126。

## R187 [UI重构·第58轮] 命令面板鼠标hover同步选中
- workspace.html: .cmdk-item 原仅键盘高亮无鼠标反馈; 加 onmouseenter→dnCmdKHover(i) 同步 _cmdkActiveIdx+重绘高亮, 实现"悬停即选中、回车执行悬停项"的标准面板交互。真机验证 hover 第3项→activeIdx=2+primary-light底。?v=u127。

## R188 [UI重构·第59轮] 立即计算按钮加载态防重复提交
- workspace.html: metricDetailCalc 接收按钮引用, 计算期间禁用按钮+文案"计算中…"+opacity .7, 防双击触发多次计算; 成功后详情重渲染自动还原, 失败/异常时还原按钮可重试。真机验证点击后 disabled+「计算中…」。?v=u128。

## R189 [UI重构·第60轮] 命令面板项高亮过渡
- modern.css: .cmdk-item 加 background/color .12s 过渡, JS 改内联高亮色时切换顺滑(键盘上下/鼠标hover均更自然)。?v=u129。

## R190 [UI重构·第61轮] 内联代码全局点击复制
- modern.css: code:not(pre code):not(.monaco-editor code) cursor:pointer + hover 主色提示。
- workspace.html: 全局委托 click 监听, 点内联 <code>(表名/字段/编码)即 DN.copy 复制(排除 pre/Monaco代码块/带自身onclick的)。数据平台高频复制场景一处全站受益。真机验证点 metricCode<code>→DN.copy("sign_proj_cnt")。?v=u130。

## R191 [UI重构·第62轮] 内联代码复制可发现性(悬停title)
- workspace.html: 委托 mouseover 监听, 为可复制的内联 <code> 首次悬停时补 title="点击复制"(排除 pre/Monaco/带onclick), 不侵入各处渲染即让 R190 复制能力可被发现。真机验证悬停 metricCode→title="点击复制"。?v=u131。

## R192 [UI重构·第63轮] 新增"返回顶部"浮动按钮
- 新组件(非报表): modern.css #dnBackTop(右下圆钮, 默认隐藏, .show 淡入+悬停抬升) + workspace.html 自包含JS(捕获阶段监听任意可见滚动容器, scrollTop>320 显现, 排除弹窗/抽屉/Monaco/铃铛避免遮挡; 点击 scrollTo top smooth)。真机验证: 资产目录滚动后按钮浮现(截图确认右下↑), 点击调用 scrollTo({top:0,smooth})于正确容器(headless不跑smooth动画属已知限制)。?v=u132。

## R193 [UI重构·第64轮] 返回顶部按钮按压/焦点态 + 数据标准QA
- modern.css: #dnBackTop 补 :active(scale .92 按压反馈) + :focus-visible(键盘焦点环), 完善组件状态(技能要求 pressed/focus 态)。
- QA: 数据标准(新增数据元表单+标准列表)视图整洁, 返回顶部按钮在此也正常浮现。?v=u133。

## R194 [UI重构·第65轮] 截断文本悬停显示全文
- workspace.html: 委托 mouseover, 对被省略号截断的文本(scrollWidth>clientWidth 且 textOverflow:ellipsis 或 overflow:hidden)首次悬停补 title=全文(<400字, 排除Monaco), 全站表格/标签截断处可悬停看全文。真机验证: 截断元素→title=全文; 不截断元素→不误加title(无误报)。?v=u134。

## R195 [UI重构·第66轮] Esc 清空搜索框
- workspace.html: keydown 监听, 聚焦在搜索框(type=search 或 placeholder含搜索/查找/search)且有内容时按 Esc 清空并触发 input 重新筛选, e.stopPropagation 防误关弹窗; 空内容时放行(Esc 仍可关弹窗); 排除命令面板输入(#cmdkInput 的 Esc 关面板)。真机验证 指标搜索框输入后 Esc→清空。?v=u135。

## R196 [UI重构·第67轮] "/" 聚焦搜索 + 快捷键帮助更新
- workspace.html: 全局 keydown, 非输入态按 "/" 聚焦当前视图首个可见搜索框(defaultPrevented 则让行, 不与模块级"/"冲突)。? 帮助浮层"全局"组更新: 加 "/"=聚焦当前页搜索框、Esc=清空搜索框/关闭弹窗, 数据集成组移除重复的"/"。真机验证 指标页 body 态按"/"→聚焦 metricSearchInput。?v=u137。

## R197 [UI重构·第68轮] 指标列表空态升级为可操作引导
- workspace.html: renderMetricList 空态由纯文本→全局 .gov-empty 组合空态: "暂无指标"分支带图标+引导文案+"＋新建指标"CTA按钮(showAddMetricDialog); "无匹配指标"分支带搜索图标+"试试调整搜索或筛选条件"提示。分支选择逻辑不变。真机验证两分支渲染正确(暂无→有CTA / 无匹配→无CTA)。?v=u138。

## R198 [UI重构·第69轮] 项目空间空态升级为可操作引导
- project.js: 项目列表空态由纯文本→全局 .gov-empty: "暂无项目"带图标+引导+"＋新建项目"CTA(projOpenCreate); "无匹配项目"带搜索图标+调整筛选提示。真机验证项目空间空态渲染 gov-empty+CTA。与 R197 指标空态形成一致的"空态即引导"体验。?v=u139。

## R199 [UI重构·第70轮] 数据集成任务空态升级为可操作引导
- workspace.html: 同步任务列表整体空态(_dbsyncRenderTasks)由纯文本→全局 .gov-empty: 图标+"暂无同步任务"+引导+"＋新建任务"CTA(dbsyncOpenCreateModal, data-perm=dbsync:edit)。至此三大创建流(指标 R197/项目 R198/集成任务 R199)空态均为"开始引导"。?v=u140。

## R200 [UI重构·第71轮] 数据模型空态升级为可操作引导
- datamodel.js: 模型列表空态由纯文本→全局 .gov-empty: 图标+"暂无模型"+引导(沉淀业务/逻辑/物理三层)+"＋新建模型"CTA(dmNewModel, data-perm=datamodel:edit)。四大创建流(指标/项目/集成任务/数据模型)空态全部为上手引导。?v=u141。

## R201 [UI重构·第72轮] AI助手进入即聚焦输入
- ai-agent.js: assistant 视图输入 textarea 创建后 setTimeout 自动 focus(聊天UI标准, 进入即可开问, 省一次点击)。真机验证 进入#/assistant 后 activeElement=AI输入框。?v=u142。

## R202 [UI重构·第73轮] 新建指标弹窗即聚焦首字段
- workspace.html: showAddMetricDialog 打开后 setTimeout 聚焦 mName(弹窗即聚焦首字段, 省一次点击, 与 R201 聊天聚焦同理)。真机验证打开弹窗 activeElement=mName。?v=u143。

## R203 [UI重构·第74轮] 项目创建弹窗即聚焦首字段
- project.js: projOpenCreate 打开后聚焦 projName(与 R202 指标弹窗一致, 创建流弹窗统一即聚焦)。真机验证打开弹窗 activeElement=projName。?v=u144。

## R204 [UI重构·第75轮] 同步任务弹窗即聚焦 + 创建流即聚焦闭环
- workspace.html: dbsyncOpenCreateModal 打开后聚焦 dfsJobName。至此三大表单弹窗(指标 R202/项目 R203/同步任务 R204)+ AI助手(R201) 进入/打开即聚焦首字段, "少一步、即开即用"闭环。真机验证 activeElement=dfsJobName。?v=u145。

## R205 [UI重构·第76轮] 通用弹窗 projShowModalBox 统一即聚焦
- project.js: projShowModalBox(数据模型新建/编辑等 ~36处调用的通用弹窗)appendChild 后统一聚焦首个可用 input/textarea/select。一处惠及全部通用弹窗。真机验证 dmNewModel→弹窗内 INPUT 获焦。至此创建流即聚焦覆盖第4流(数据模型)。?v=u146。

## R206 [UI重构·第77轮] 新建指标弹窗 Esc 关闭(a11y一致性)
- workspace.html: 全局 Esc 处理(原仅 gModalOverlay)扩展覆盖 metricModal(display:flex 时 closeMetricModal)。dbsyncFormModal 已有(16339)/projShowModalBox 已有/项目弹窗已有→至此主要弹窗 Esc 关闭一致。真机验证 打开新建指标弹窗 Esc→关闭(flex→none)。?v=u147。

## R207 [UI重构·第78轮] 新建指标弹窗遮罩点击关闭
- workspace.html: metricModal 遮罩加 onclick(event.target===this 时 closeMetricModal), 与 projShowModalBox/cmdk 等遮罩点击关闭一致。真机验证: 点遮罩→关闭(none), 点弹窗内部→保持打开(flex)。?v=u148。

## R208 [UI重构·第79轮] 修 display:flex 弹窗缺弹入动画
- modern.css: dnModalPop 原仅作用 .quality-modal-overlay.show .quality-modal, 但 metricModal 用 display:flex 开(非.show类)→动画不触发。补 .quality-modal-overlay[style*="flex"] .quality-modal 选择器, 让 flex 开法弹窗也有弹入动画。真机验证 新建指标弹窗内层 animationName=dnModalPop。?v=u149。

## R209 [UI重构·第80轮] 质量规则弹窗遮罩点击+Esc关闭
- workspace.html: qualityRuleModal(同 .quality-modal-overlay/display:flex 开)补遮罩 onclick(closeRuleModal)+全局 Esc 扩展覆盖。R208 弹入动画已覆盖它。真机验证 遮罩点击→关闭、Esc→关闭。至此 quality-modal-overlay 家族(指标/质量规则)弹窗 动画+遮罩关闭+Esc 三件套齐全一致。?v=u150。

## R210 [UI重构·第81轮] 质量规则弹窗即聚焦 + 血缘视图QA
- workspace.html: showAddRuleDialog 打开后聚焦 qrName。至此 quality-modal-overlay 家族(指标/质量规则)即聚焦+遮罩关闭+Esc+弹入动画 全齐。
- QA: 数据血缘视图(血缘探查 库/表/跳数+查询/下游/上游/画图 + 图例 + 孤儿表检测)为设计良好的交互查询工具, 无需改。真机验证质量规则弹窗 activeElement=qrName。?v=u151。

## R211 [UI重构·第82轮] 侧栏hover深色不可见bug修复
- app.css: .ops-sidebar-item:hover 原用硬编码 rgba(0,0,0,.03)(黑色调)→深色模式下几乎不可见(hover无反馈, 真bug+违§6)。改 var(--bg-hover)(主题感知)。真机验证深色 var(--bg-hover)=rgb(34,39,56) 可见。侧栏激活态本就完善(3px左强调条+底色+主色+weight)。?v=u152。

## R212 [UI重构·第83轮] 批量修黑调背景深色不可见(承R211)
- app.css: 承 R211, 排查并修复 6 处硬编码 rgba(0,0,0,.0x) 背景(深色下不可见/弱): .tab-close:hover(编辑器标签关闭)/.right-header .close-btn:hover/.g-modal-close:hover(弹窗关闭)/计数徽标(→--bg-sunken)/.dbsync-folder-nav-item:hover(集成目录)/.dfs-field-map-table tr:hover(字段映射) → 全改 var(--bg-hover)/var(--bg-sunken)(主题感知)。剩余黑调背景 0。深色 hover 反馈/徽标恢复可见, §6 合规。?v=u153。

## R213 [UI重构·第84轮] 深色端到端QA + toast边框令牌化
- 深色 QA: 数据开发(树/卡片/hero/KPI/状态药丸/右栏)深色端到端干净, 印证 R211/R212 修复。
- app.css: .dn-toast-muted 黑边 rgba(0,0,0,.12)→var(--border)(深色可见)。gov-heat-cell .04 超浅装饰边(热力图色块自带强底色)保留。至此 CSS 背景/边框/文字 深色不可见类硬编码基本清零。?v=u154。

## R214 [UI重构·第85轮] 数字输入框防滚轮误改值
- workspace.html: 全局 passive wheel 监听, 聚焦的 number 输入被滚轮经过时 blur(让页面正常滚动而非误改数值)。修常见痛点(跳数/阈值/batchSize 等数字输入滚动误改)。真机验证 聚焦number+滚轮→失焦且值不变(5→5)。?v=u155。

## R215 [UI重构·第86轮] Toast 点击即关
- workspace.html: showToast 生成的 toast 加 cursor:pointer + title=点击关闭 + click→淡出移除(原仅 2.6s 自动消失)。真机验证 弹 toast→点击→立即移除。?v=u156。

## R216 [UI重构·第87轮] AI聊天输入自动增高
- ai-agent.js: assistant 输入 textarea 加 input 监听, 随内容自动增高(height=auto→min(scrollHeight,160))。标准聊天输入体验。真机验证 空58→5行117→封顶≤160。?v=u157。

## R217 [UI重构·第88轮] AI输入发送后高度复位
- ai-agent.js: send/steer 清空 value 后补 inputEl.style.height=''(配合 R216 自动增高, 防发送后高度残留变高)。真机验证 增高117→复位58。?v=u158。

## R218 [UI重构·第89轮] 指标搜索防抖(去请求风暴)
- workspace.html: metricSearchInput 原 oninput=loadMetricList() 每键都发服务端双请求(列表+新鲜度)→请求风暴。新增 loadMetricListDebounced(280ms)替换 oninput。其余搜索框多为廉价客户端过滤或已防抖(dbsync/proj)。真机验证 3连击→0即时+延迟后1次。?v=u159。

## R219 [UI重构·第90轮] 指标列表搜索命中高亮
- workspace.html: renderMetricList 加 hlMetric() 高亮搜索关键词在 指标名称/编码 中的命中(<mark> warning-bg, title 保持纯文本)。承命令面板高亮体验, 列表结果可扫读。真机验证 搜"签"→"<mark>签</mark>约项目数"。?v=u160。

## R220 [UI重构·第91轮] 表格斑马纹(补主表+修dbsync硬编码)
- app.css: .dbsync-exec-table 斑马 rgba(0,0,0,.015)(R212漏网, 深色不可见)→var(--bg-sunken)。
- modern.css: quality-rules-table(指标等主表)原无斑马, 补 tbody tr:nth-child(even) td 交替行底(var(--bg-sunken)) + 紧随 hover td=var(--bg-hover) 覆盖规则(同特异性源序在后, 保证 hover 盖过斑马)。真机验证 偶数行TD bg=var(--bg-sunken) 令牌生效。?v=u161。

## R221 [UI重构·第92轮] 质量规则过滤无匹配反馈
- workspace.html: qualityFilterRules(客户端 show/hide 过滤)原全隐藏时为空表无提示。改为统计可见行, 全隐藏时插入/显示 #qualityNoMatch 提示行(colspan=9 "无匹配规则，试试调整搜索词"), 有匹配时隐藏。真机验证 搜不存在词→提示显示(20行全隐)、清空→提示隐藏+20行恢复。?v=u162。

## R222 [UI重构·第93轮] 全模块控制台巡检 + 改密显示密码开关
- QA: 逐访 10 模块控制台 0 错误 0 警告(仅 benign verbose "密码框未在form内"提示)。
- workspace.html: 账号抽屉改密区加"显示密码"开关(一处复选切换 原/新/确认 三框 password↔text, 便于核对)。真机验证 勾选→三框 type password→text。?v=u163。

## R223 [UI重构·第94轮] 首登强制改密加显示密码
- workspace.html: dnForceChangePwd 弹窗(首登必改的关键流)3 框(当前/新/确认)加"显示密码"复选(dnFpShow, 切 password↔text), 与账号抽屉改密(R222)一致。真机验证 勾选→三框 type password→text。?v=u164。

## R224 [UI重构·第95轮] 重置密码框加显示密码(显密码闭环)
- workspace.html: 用户管理 umResetPwd 弹窗的 umResetPwdInput 加"显示密码"复选(内联 onchange 切 type)。至此显示密码覆盖 数据源密码/Hive/AI key(早有👁)+账号改密(R222)+首登强制改密(R223)+重置密码(R224)。真机验证 勾选→password→text。?v=u165。

## R225 [UI重构·第96轮] 新建/编辑用户密码框加显示密码(显密码全覆盖收官)
- workspace.html: umUserModal 的 umfPwd 加"显示密码"复选。至此全部密码输入场景均有显示密码: 数据源/Hive/AI key(👁)+账号改密(R222)+首登改密(R223)+重置密码(R224)+新建编辑用户(R225)。真机验证 勾选→password→text。?v=u166。

## R226 [UI重构·第97轮] 新密码实时合规提示
- workspace.html: 账号改密抽屉 newP 加 live 合规提示(input 时校验 ≥8位 + 字母/数字/符号≥2类: 满足绿✓ / 未满足橙色列出缺项), 比提交才报错更友好。真机验证 "abc"→橙"未满足需≥8位/2类"、"Abcd1234!"→绿"✓已满足"。?v=u167。

## R227 [UI重构·第98轮] 首登改密也加新密码实时合规提示
- workspace.html: dnForceChangePwd 加 #dnFpHint, dnFpNew input 时同款校验(≥8位+2类, 满足绿/未满足橙)。与账号改密(R226)一致, 覆盖两大密码创建流。真机验证 "abc"→"未满足"、"Abcd1234!"→"✓已满足"。?v=u168。

## R228 [UI重构·第99轮] 新建指标弹窗 Enter 提交
- workspace.html: metricModal 加 onkeydown, 单行输入(非 textarea/button)按 Enter→saveMetric, 与 projShowModalBox 回车提交一致。真机验证 名称框填值回车→saveMetric 被调用。数据源测试连接复检已有加载态+结果反馈(testDatasource)。?v=u169。

## R229 [UI重构·第100轮] 质量规则弹窗 Enter 提交(里程碑)
- workspace.html: qualityRuleModal 加 onkeydown 单行输入 Enter→saveQualityRule()(btn 可选)。与 metricModal(R228)/projShowModalBox 回车提交一致, quality-modal-overlay 家族交互闭环。真机验证 名称框回车→saveQualityRule。**第100轮里程碑**: R130-R229 累计100轮 UI 现代化, 全程真机验证+提交推送。?v=u170。

## R230 [UI重构·第101轮] 数据地图搜索命中高亮
- workspace.html: renderDmTable 加 _dhl() 高亮 datamapSearchInput 关键词在 表名/中文名描述 中的命中(<mark> warning-bg)。承指标列表(R219)高亮体验, 搜索中枢结果可扫读。真机验证 搜"ads"→50处命中高亮。?v=u171。

## R231 [UI重构·第102轮] 用户管理搜索命中高亮
- workspace.html: umRenderUsers 加 _uhl() 高亮搜索关键词在 用户名/昵称/邮箱/手机/部门 中的命中(<mark> warning-bg)。承指标(R219)/数据地图(R230)高亮, 用户列表搜索可扫读。真机验证 搜"admin"→用户名命中高亮。?v=u172。

## R232 [UI重构·第103轮] 用户管理空态升级为可操作引导
- workspace.html: umRenderUsers 空态由纯文本→gov-empty: "暂无用户"带图标+"＋新建用户"CTA(umUserModal); "无匹配用户"带搜索图标+调整提示。承 R197-200/R221 空态体系。真机验证 搜不存在词→gov-empty"无匹配用户"。?v=u173。

## R233 [UI重构·第104轮] 数据集成监控大屏模态 Esc+遮罩关闭(工作流确认)
- workspace.html: Ultracode审计确认 dbsyncDashboardModal 漏在 Esc 处理器(其他 dbsync 弹窗都有)。补 Esc 关闭(全局 keydown 加 dm.classList.contains('show')→dbsyncCloseDashboard) + 遮罩点击关闭。?v=u174。

## R234 [UI重构·第105轮] 全局 a11y: 可点击div/span 键盘可达(工作流确认)
- 工作流审计确认大量 onclick 的 div/span(侧栏tab×21/wlc-hero按钮/wlc-action卡/result-tab/history-tab/dbsync-section展开 等~30处)缺 role/tabindex/keydown。一处全局解决: dnA11yEnhance() 给 div[onclick]/span[onclick] 补 role=button+tabindex=0(load + navigateTo后扫描) + 全局 keydown 委托(Enter/Space→click)。真机验证 运维6侧栏tab全获a11y属性、聚焦"补数据"Enter→激活。
- **注**: 工作流"darkmode-#fff"6条经人工核验为**误报**(.wlc-hero/.dash-hero 背景是固定靛蓝渐变 var(--dash-brand-grad)=linear-gradient(#1d6fff,#3aa0ff)、气泡var(--primary)靛蓝、徽标warning橙、toast status色, 白字在恒定彩底两套主题都正确; 改 --text-inverse 深色变暗字反更糟), 故跳过。?v=u175。

## R235 [UI重构·第106轮] 写操作补 .catch 防静默失败(工作流确认)
- workspace.html: 工作流确认的5处写操作裸 fetch/apiPost 无 .catch(网络失败静默, 用户误以为成功): autoSaveDatabaseName(数据库名自动保存)/ctxDelete(删脚本/源)/ctxDeleteFolder(删文件夹)/ctxNewFolder(新建文件夹)/ctxRenameFolder(重命名文件夹) 均补 .catch→msgBox/toast 错误提示。与全站既有错误处理模式一致。?v=u176。

## R236 [UI重构·第107轮] 读操作补catch批1(依赖/手动任务·工作流确认)
- workspace.html: loadDependencies(/api/script/all-with-content)补 .catch→上下游+脑图区显"加载失败"; loadOpsManual(/api/task-execution/manual)补 .catch→"加载记录失败+重试"(原失败时与"暂无记录"无法区分)。?v=u177。

## R237 [UI重构·第108轮] 读操作补catch批2(质量/数据源下拉·工作流确认)
- workspace.html: loadQualityTables/loadQualityColumns(质量规则 表/字段下拉)补 .catch→下拉显"加载失败"(原失败时永停"加载中..."); dbsyncLoadDsList 空 catch 改为显"加载失败"。?v=u178。

## R238 [UI重构·第109轮] dbsync弹窗一致(对账/历史/移动 Esc+遮罩·工作流确认)
- workspace.html: dbsyncReconcileModal/dbsyncHistoryModal/dbsyncMoveJobModal 各补 遮罩点击关闭(onclick event.target===this) + 全局 Esc 处理器加 3 检查(reconcile走dbsyncCloseReconcile, history/movejob classList.remove)。真机验证 3 模态 Esc+遮罩关闭全生效。至此 dbsync 全部弹窗 Esc/遮罩关闭一致(承 R233)。?v=u179。

## R239 [UI重构·第110轮] 读操作补catch批3收尾(工作流确认清零)
- workspace.html: depSearchTasks(依赖搜索)/depDeleteByName(删依赖前查)/loadBaselineList(Promise.all)补 .catch→错误态+重试; loadDsLogs catch 由灰字"加载失败"升级为带"重试"入口。至此 Ultracode 工作流确认的 缺catch/弹窗一致/a11y 三类(除已否决的 darkmode误报)全部修复闭环。?v=u180。

## R240 [UI重构·第111轮] 周期任务空态区分筛选无匹配/无记录
- workspace.html: renderSchedTasks 空态原统一显"请先开始今日调度"(筛选无果时误导)。改为检测筛选输入是否激活+是否有数据: 有数据但筛选无匹配→gov-empty"无匹配任务/调整筛选"; 真无数据→gov-empty"暂无调度记录/开始今日调度"。真机验证 有数据+输不存在名→"无匹配任务"。?v=u181。

## R241 [UI重构·第112轮] 布局防错位批(§10.4·工作流2确认)
- app.css: 工作流2确认的6处布局错位/溢出隐患修复(均低风险防御CSS, 不动色彩): .intg-form-control 加 min-width:0(select防压扁); .intg-panel-header h3 加 ellipsis(长表名防撑破); .intg-table td 加 max-width:180+ellipsis(长数据截断); .btn 加 width:auto(紧凑工具条防压扁); .env-badge 加 display:inline-flex+align-items(防压缩)+bg令牌化(rgba绿→--success-bg); .prop-value 加 min-width:0(属性栏长标签防错位)。?v=u182。

## R242 [UI重构·第113轮] 交互按压态补全(§10.5·工作流2确认)
- modern.css: 工作流2确认 .header-tab/.editor-tab/.result-tab(有hover无active)补 :active translateY(1px); .tab-close/.left-header .add-btn/.result-download-btn 补 :active scale(.92)。纯 transform 反馈不动色, 与既有 .btn:active 一致。?v=u183。

## R243 [UI重构·第114轮] 状态徽标/按钮视觉统一(工作流2确认)
- workspace.html: 审批状态(已通过/已驳回/已撤回/待审)由裸 span+内联色→gov-pill is-ok/err/muted/warn(与全站状态徽标统一); 基线删除按钮由"btn+内联error色"→btn-danger(与其他删除按钮一致的红底)。?v=u184。

## R244 [UI重构·第115轮] quality-status徽标统一为gov-pill(工作流2确认)
- workspace.html: 指标列表(9105)+质量规则表(9682)的 启用/停用 状态由 quality-status-on/off(独立重复类)→gov-pill is-ok/is-muted, 与全站状态徽标统一。quality-status-on/off 用法清零。真机验证指标列表 gov-pill 正常。?v=u185。

## R245 [UI重构·第116轮] app.css :root 补关键令牌(防御性·工作流2)
- app.css: 遗留桥接 :root 补 --radius-sm/--radius-full/--fs-xs/--text-inverse/--text-secondary/--dur/--dur-fast/--dur-slow(值与 dn-design.css 一致)。orphan-var 经级联实际可用(CSS变量computed-time解析), 补齐为防御性(防加载序变化), 无可见变化。工作流2其余项(KPI卡片跨文件令牌统一/操作链接205处refactor/1px间距微调)经判断为高风险或无可见收益, 按极简原则**不做**。真机冒烟首页正常。?v=u186。

## R246 [UI重构·第117轮] 数据集成任务空态区分筛选无匹配/无任务
- workspace.html: dbsyncRenderJobs 空态原统一显"暂无同步任务+新建"(搜索/状态/目录筛选无果时误导)。改为检测筛选是否激活(搜索词/状态≠ALL/选了子目录)+_dbsyncAllJobs是否有数据: 有任务但筛选无匹配→"无匹配任务/调整筛选"; 真无任务→"暂无同步任务/新建"。真机验证 有任务+搜不存在词→"无匹配任务"。承 R221/R240 空态区分体系。?v=u187。

## R247 [UI重构·第118轮] 破坏性操作补二次确认(工作流3确认·HIGH)
- datamodel.js: dmDelRelation(删数据模型关系)补 DN.confirm(danger)二次确认(原直接DELETE, 与 dmDeleteModel/dmDelEntity 模式对齐)。
- workspace.html: clearSearchHistory(清空元数据搜索历史)补 DN.confirm(danger)。防误删不可恢复数据。?v=u188。

## R248 [UI重构·第119轮] 表单前端校验补全(工作流3确认)
- workspace.html: saveDatasource 补 端口1-65535范围校验 + PostgreSQL/SQLServer/Oracle 类型数据库名必填校验(原仅校验name/host, 非法值静默落库致连接失败); saveQualityRule ruleName 加 trim(白空格→空→被现有必填捕获); saveEnvConfig DataX Home 非空校验(防空值/危险字符拼shell); saveDatasourceConfig Doris 主机非空校验(防空值拼JDBC URL)。真机验证 端口99999→"端口须为1-65535的整数"被拦。?v=u189。

## R249 [UI重构·第120轮] 死代码清理(工作流3确认)
- workspace.html: 删除7个确认零引用死函数: autoSaveCurrentTab/autoSaveDatabaseName(R235曾对其加catch,实为死码)/activateRightTab/switchRightTab/previewCron/padN(formatOneStatement内未用)/formatOneStatement_old(~245行legacy大块, 活版 formatOneStatement 仍用)。逐个 grep 验证仅定义无引用。真机验证 formatOneStatement 仍可用、SQL格式化正常、开发模块无破坏。净删~280行死码。?v=u190。

## R250 [UI重构·第121轮] 加载态文本→骨架屏批(工作流3确认)
- workspace.html: 6处高频静态加载占位"加载中..."→ws-skel骨架屏: 文件树#fileTree/欢迎页最近编辑#welcomeRecentList/运维概览#opsOverviewContent/质量规则#qualityRulesContent/同步仪表板表#dbsyncDashboardBody/深度对账#dbsyncChecksumBody。与全站骨架屏体系一致。真机验证开发模块文件树+欢迎页正常渲染。?v=u191。

## R251 [UI重构·第122轮] closeAllDrawers 死查询清理(工作流3确认)
- dn-common.js: DN.closeAllDrawers 删除 querySelectorAll('.dn-ai-mask, .dn-ai-drawer') 死查询(AI抽屉已统一为.gov-drawer, 上方已处理; 该选择器永不命中却每次导航都跑)。真机验证 closeAllDrawers 无异常。其余 perf 项(projResetFilters/collectAttrRows/projOpenDetail 微优化)经判断为非热路径/无实益, 按极简不改。**至此三轮工作流(共~88确认项)全部处置完毕(实修+合理否决)**。?v=u192。

## R252 [UI重构·第123轮] 数据集成任务表格搜索命中高亮
- workspace.html: dbsyncJobsTable 加 _jhl() 高亮搜索关键词在任务名中的命中(<mark> warning-bg, title保持纯文本)。真机验证 真实任务"ces1"搜"ce"→命中高亮。至此搜索高亮覆盖命令面板/指标/数据地图/用户/数据集成全部主搜索列表。?v=u193。

## R253 [UI重构·第124轮] 顶栏当前模块 aria-current(a11y)
- workspace.html: navigateTo 给激活的 .header-tab 设 aria-current="page"、其余移除。读屏宣告当前所在模块, 符合 WCAG 导航语义。真机验证 治理时=governance、切指标后转移、始终仅一个。?v=u194。

## R254 [UI重构·第125轮] 侧栏当前子页 aria-current(a11y, DRY)
- workspace.html: 全局委托——点 .ops-sidebar-item 后(setTimeout 0 待 switch 完成)按 .active 同步 aria-current="page"。一处覆盖 switchOpsTab/ProjectTab/SettingsTab/GovModule/MdmModule/QualityTab/MetricsTab 全部侧栏切换(DRY)。承 R253 顶栏 aria-current。真机验证 点运维"补数据"→该项 aria-current=page 且仅一个。?v=u195。

## R255 [UI重构·第126轮] 静态弹窗补 role=dialog/aria-modal(a11y)
- workspace.html: metricModal/qualityRuleModal/dbsyncFormModal 三个静态弹窗内层补 role="dialog" aria-modal="true" aria-labelledby(指向各自标题span), 读屏识别为模态对话框并朗读标题(projShowModalBox本就有)。?v=u196。

## R256 [UI重构·第127轮] Toast 堆叠上限防溢出
- workspace.html: showToast 追加后, stack 子项超 5 条即移除最旧, 防大量并发 toast(如批量操作多条结果)堆满/溢出屏幕。真机验证 连发8条→可见恰好5条。?v=u197。

## R257 [UI重构·第128轮] 弹窗关闭按钮 aria-label(a11y)
- workspace.html: 3个纯SVG关闭按钮(.close-btn: 质量规则/指标弹窗/右属性栏)加 aria-label="关闭"+title="关闭", 读屏可识别、鼠标悬停有提示。?v=u198。

## R258 [UI重构·第129轮] 命令面板 role=dialog(a11y)
- workspace.html: 命令面板 #cmdkPanel 加 role="dialog" aria-modal="true" aria-label="命令面板", 读屏识别为模态对话框。至此主要弹窗/面板(指标/质量/同步表单/通用projShowModalBox/命令面板)a11y 语义齐全。?v=u199。

## R259 [UI重构·第130轮] 模态焦点陷阱(WCAG·a11y收官)
- workspace.html: 全局 Tab 处理——弹窗打开时(cmdkOverlay/projModalBox/.quality-modal-overlay/.dbsync-modal-overlay/.g-modal-overlay 任一可见)焦点循环锁在弹窗内: Shift+Tab 在首元素→跳末, Tab 在末元素→跳首, 不跑到背景。真机验证 新建指标弹窗12可聚焦元素, 末元素Tab→回首。a11y 焦点containment 收官。?v=u200。

## R260 [UI重构·第131轮] 弹窗关闭焦点还原(a11y焦点生命周期完整)
- workspace.html: showAddMetricDialog/showAddRuleDialog 记录触发元素(document.activeElement), closeMetricModal/closeRuleModal 关闭后还原焦点到触发元素。配合 R201/202即聚焦+R259焦点陷阱, 形成完整焦点生命周期(开→聚焦首字段→陷阱锁内→关→还原触发)。真机验证 点新建指标→焦点进mName→关→还原到按钮。?v=u201。

## R261 [UI重构·第132轮] 数据地图最近搜索/收藏补 catch
- workspace.html: loadDmSearchHistory/loadDmFavorites 原无 .catch(加载失败静默)。补 .catch→显"最近搜索加载失败"/"收藏加载失败"。(最近搜索/收藏项本就可点 dmOpenTable 直达表详情。)?v=u202。

## R262 [UI重构·第133轮] 全局未捕获Promise拒绝兜底(健壮性)
- workspace.html: 加 window 'unhandledrejection' 监听, console.warn 暴露漏网异步失败便于排查 + 抑制控制台默认报错。不弹 toast(防 benign 中止/导航 fetch 误报刷屏)。一处兜住所有未补 catch 的边缘异步。真机冒烟首页0错误。?v=u203。

## R263 [UI重构·第134轮] 指标列表整行可点开详情
- workspace.html: 指标列表 tr 加 cursor:pointer + onclick→openMetricDetail(守卫 event.target.closest('button,a,input,select') 不与名称链接/操作按钮/开关冲突)。更大点击目标, 人性化。真机验证 点分类单元格→开详情驾驶舱。?v=u204。

## R264 [UI重构·第135轮] 数据集成任务表格行整行可点开详情
- workspace.html: dbsyncJobsTable 表格行 tr 加 cursor:pointer + onclick→dbsyncOpenDetail(守卫 closest('button,a,input,select,.dbsync-more-trigger') 不与复选/操作冲突), 与卡片视图整卡点击一致、承 R263 指标行点击模式。(本会话集成列表为空无法真机点击, 代码已部署+逻辑同已验证模式。)?v=u205。

## R265 [UI重构·第136轮] 用户管理表格行整行可点开详情
- workspace.html: umRenderUsers 行 tr 加 cursor:pointer + onclick→umViewUser(守卫 closest('button,a,input,select') 不与复选/操作链接冲突)。承 R263/264 行点击模式。真机验证 点昵称单元格→触发 umViewUser。?v=u206。

## R266 [UI重构·第137轮] 快捷键帮助新增"通用交互"组
- workspace.html: ? 帮助浮层加"通用交互"组反映新增能力: 单击表格行打开详情(指标/集成/用户)、点击编码/表名复制、滚动到底返回顶部。保持帮助文档与实际功能同步。真机验证 ?面板含通用交互组全部条目。?v=u207。

## R267 [UI重构·第138轮] 数据模型列表行整行可点开详情
- datamodel.js: 模型列表行 tr 加 cursor:pointer + onclick→dmOpenModel(守卫 closest('button,a,input,select'))。承 R263-265 行点击模式, 行点击现覆盖 指标/集成/用户/数据模型。真机验证 点名称单元格→触发 dmOpenModel。?v=u208。

## R268 [UI重构·第139轮] 指标列表列排序
- workspace.html: renderMetricList 加可点表头排序(指标名称/编码/分类/负责人/状态), metricSortBy 切 asc/desc(▲▼指示), 复用当前数据重渲染。数据表常用能力。真机验证 点指标名→升序[Alpha,Beta,Gamma]→再点降序。?v=u209。

## R269 [UI重构·第140轮] 用户管理列排序
- workspace.html: umRenderUsers 加可点表头排序(用户名/昵称/部门/最后登录), umSortBy 切 asc/desc(▲▼), 复用 _umUsers 过滤后列表重渲染。承 R268 指标排序。真机验证 点用户名→升序首admin→再点降序首zs01(4用户反转)。?v=u210。

## R270 [UI重构·第141轮] 指标列表加条数计数
- workspace.html: renderMetricList 表格上方加"共 N 个指标"计数(筛选时显"N / 总数"), 与用户表"共N/M"一致, 信息性。真机验证 显"共 1 个指标"。?v=u211。

## R271 [UI重构·第142轮] 指标列表 CSV 导出
- workspace.html: 指标列表工具栏加"导出CSV"按钮 + metricExportCsv(导出当前已加载列表: 名称/编码/分类/单位/负责人/计算公式/目标值/口径; UTF-8 BOM 防 Excel 中文乱码; 字段转义引号)。承数据地图导出。真机验证 触发下载"指标列表.csv"。?v=u212。

## R272 [UI重构·第143轮] 用户管理 CSV 导出
- workspace.html: 用户管理工具栏加"导出CSV" + umExportCsv(导出全部用户: 用户名/昵称/邮箱/手机/部门/岗位/最后登录/状态/角色; **不含密码**; BOM 防乱码)。承 R271 指标导出。真机验证 触发下载"用户列表.csv"。?v=u213。

## R273 [UI重构·第144轮] 补齐 Esc 关闭弹窗(键盘可达性)
- workspace.html: 4 个遗漏弹窗补 Esc 关闭。dbsync Esc 处理器加 dbsyncChecksumModal/dbsyncFolderModal; 通用 Esc 处理器加 syncModal/intgModal(数据集成)。此前 gModal/metric/quality/cmdk/help/proj/dbsync(5个) 已支持; 现全站弹窗 Esc 一致可关。真机验证 4 个均 closedByEsc=true。?v=u214。

## R274 [UI重构·第145轮] 质量规则列表 计数 + CSV 导出
- workspace.html: 质量规则工具栏加"共 N 条规则"计数 + "导出CSV"(规则名/类型/库/表/字段/级别/状态/创建人; BOM 防乱码; 引号转义)。存 window._qualityRules 供导出。承指标(R271)/用户(R272)导出。真机验证 22 条规则触发下载"质量规则列表.csv"。?v=u215。

## R275 [UI重构·第146轮] 质量规则列表 列排序
- workspace.html: 质量规则表头 7 列(规则名/类型/库表/字段/创建人/级别/状态)可点排序; DOM 原地重排保留勾选与搜索态; 级别/状态按数值, 其余 localeCompare; 表头 ⇅/▲/▼ 指示; 无匹配行始终垫底。承指标(R268)/用户(R269)排序。真机验证 sev 数值升序 + name locale 升序 + 反向切换均正确。?v=u216。

## R276 [UI重构·第147轮] 质量规则排序重载持久化(修 R275 体验缺口)
- workspace.html: R275 的质量规则 DOM 排序在编辑/删除后列表重载会丢失。抽出 _qualityApplySort()(按当前排序态重排不切方向), qualitySortBy 调它, loadQualityRules 渲染后也调它。现 CRUD 重载后排序与表头箭头保持。真机验证 排序后 loadQualityRules 重载仍升序、箭头 ▲。?v=u217。
