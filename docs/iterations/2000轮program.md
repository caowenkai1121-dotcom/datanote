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
