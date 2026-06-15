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
