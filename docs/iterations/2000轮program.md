# 2000 轮持续迭代 program(业主令, 续 100 轮易用性后)

方向(业主四选): ①结构性精简(合并/删除冗余) ②新功能/模块做厚 ③深度真机回归测试 ④无障碍/性能/工程化深扫。
轮数: 业主令 2000 轮硬刷; 优先真实价值, 凑数/边际项如实标注。轮号续 R101+。

## 进度
- R101 [无障碍] 可点击 div user-btn → role=button+tabindex+aria-label+键盘Enter/Space; 项目/发布弹窗关闭× 加 aria-label。已部署。
- R102 [删除] 死特性 用户组(Group): GroupController/GroupService/DnGroupMapper/DnGroupMemberMapper/DnGroup/DnGroupMember 共 6 文件 + PermInterceptor /api/group 规则。证据: GroupService 零外部调用方, /api/group 前端 0 引用, RBAC 用角色非组。E2E: api/group 已移除。
- R103 [删除] 死特性 告警配置(alert-config): AlertConfigController/Service/Mapper/Model 共 4 文件 + /api/alert-config 规则。证据: AlertConfigService 仅自控制器调用、mapper/model 包外零引用、前端 0 引用; 真实告警走 AlertService(webhook)+NotificationService(站内信)。
- 注: dn_group/dn_group_member/dn_alert_config 表保留在库(删表破坏性, 留作历史; 无代码引用无害)。
- 深研结论: 重复功能审计=系统分工清晰无可合并(代理确认); 死代码审计代理 9 控制器报警中 6 个假阳(snippet/data-acl/baseline/cdc/datax/sync-folder 均活)——已逐一 grep 复核, 仅 Group/alert-config 真死并删除。结构性冗余总体很少。
