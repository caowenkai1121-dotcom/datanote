# UI 打磨 backlog (五角色审计, 72 项)


## visual (13)
- [H] 抽屉关闭按钮无键盘焦点状态 | gov-modern.css:109 / .gov-drawer .dh .x | 补充焦点样式：.gov-drawer .dh .x:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }。当前缺乏键盘导航反馈。
- [H] ER图框线过细与圆角混乱(内联样式) | datamodel.js:658 / style inline border:1.5px 和 border-radius:6px | 改为 border:1px solid var(--primary); border-radius:var(--radius-lg); box-shadow:var(--shadow-md)。统一令牌，提升层级感。
- [M] 验证错误提示框硬编码色值脱钩 | datamodel.js:502-503 / rgba(224,65,78,.08) rgba(245,159,0,.08) 硬编码 | 改用令牌：background:var(--error-bg); border:1px solid var(--error-ring)。暗色模式下也能自适应。
- [M] ER实体卡头圆角与规范不统一 | datamodel.js:659 / border-radius:4px 4px 0 0 硬编码 | 改为 border-radius:var(--radius-md) 0 0 0 保证上圆角一致。与卡片边框对齐。
- [M] 项目KPI卡片缺失悬浮过渡反馈 | project.js:138 / box-shadow:var(--shadow-sm) 无 transition | CSS 中补充 .proj-kpi-tile { transition: all var(--dur) ease; } .proj-kpi-tile:hover { box-shadow:var(--shadow-lg); transform:translateY(-2px); }
- [M] 数据模型卡片圆角混用令牌与硬编码 | datamodel.js:741 / border-radius:var(--radius) 应升级 | 统一改为 border-radius:var(--radius-md) 确保所有中等卡片圆角一致。
- [M] 项目标签芯片颜色拼接透明度过淡 | project.js:256 / (t.tagColor || 'var(--primary)') + '22' | 背景透明度 34% 过浅致可读性差。改为 10% 或定义 CSS 类 .tag-chip { background:rgba(var(--primary-rgb),.1) }。
- [M] 项目搜索弹窗无最小宽度约束 | project.js:17 / width:560px max-width:92vw 无 min-width | 补充 min-width:280px 防窄屏挤压。改为 style="width:520px;min-width:280px;max-width:92vw;..." 并确保 input height:44px。
- [L] 表格圆角定义在多个 CSS 重复 | dn-design.css:269 vs gov-modern.css:152 / 圆角覆盖层级混乱 | 在 dn-design.css 中集中定义 .gov-tbl-wrap { border-radius:var(--radius-md); }，移除 gov-modern.css 重复定义。
- [L] 全局按钮过渡时长无分层 | modern.css:41-47 / 所有交互元素共用 var(--dur) .2s | 分层调整：按钮用 .2s，Tab 用 .12s，卡片用 .3s。增强响应感。改 transition 属性分别应用。
- [M] 亮暗色阴影对比度不足 | dn-design.css:62-68 (亮) vs 109-115 (暗) / --shadow-md 值偏弱 | 亮色 --shadow-md 第二层改 rgba(23,30,60,.08) 替代 .06；暗色改 .45 替代 .40。确保卡片浮起感。
- [L] 弹窗关闭按钮尺寸不统一 | dn-design.css:350 / .g-modal-close width:30px height:30px 应为 32px | 改为 width:32px; height:32px; font-size:16px 与 gov-drawer 关闭钮保持一致。
- [L] 统计数字卡右对齐宽度固定过小 | gov-modern.css:79 / .gov-bar .bv width:54px | 改为 min-width:54px; 并用 font-variant-numeric:tabular-nums 确保等宽显示。防6位数溢出。

## ux (16)
- [H] 缺少输入搜索防抖反馈优化 | 第381行 oninput事件 → table搜索,第381行 clearTimeout(_t) | 搜索框输入时虽设220ms防抖, 但用户看不到反馈。建议: ①输入时立即显示清空按钮(已做); ②搜索中显示「搜索中…」文本或加载动画; ③优化搜索框视觉反馈——当防抖等待时变浅opacity, 搜索完成恢复, 这样用户知道系统在响应
- [M] 登录页密码输入缺密码强度反馈 | 第138行 password input, 第79-82行CSS | 目前只有大写锁定提示(.caps-warn), 缺密码强度实时反馈。建议增加: ①密码输入时实时计算强度(长度/大小写/符号); ②显示强度条(弱/中/强) style='height:3px;background:red|orange|green'; ③弱密码禁用登录按钮或仅给警告色提示
- [M] 表单必填校验反馈不及时 | 第559-566行 DN.field定义, gov-health.js:552-552 required标记仅红星 | 当前必填字段仅用红星标记, 提交失败才显示错误toast。改进: ①焦点离开(blur)时即验证必填字段, 若为空显示实时错误提示(<span class='dn-error'>必填</span>接在input下方); ②input边框变红(border-color:var(--error)); ③submit按钮在有
- [H] 批量操作缺撤销/确认二次提示 | gov-classification.js:385-390 confirmLabels函数, gov-health.js:490 批删工单, gov-asset | 批量删除虽有DN.confirm弹窗, 但缺撤销功能。建议: ①成功后显示带撤销链接的toast: '已删除N条，<a href=javascript onclick=undo()>撤销</a>'; ②撤销链接可在后台保存deleteIds+timestamp; ③超过5s自动消失(不可撤销); ④用户点击撤销需再确认
- [M] 列表骨架屏加载缺超时兜底 | gov-assets.js:13,34,539,543 DN.skeleton调用, 无超时检测 | 加载时显示骨架屏, 但若API超时无任何提示。改进: ①在append骨架屏时记录startTime; ②设8s超时: setTimeout(()=>{ if(still skeleton) replace with DN.errorBox('加载超时', retryFn) }, 8000); ③错误框显示重试按钮, 
- [M] 空状态缺引导行动按钮 | 第318-327行 DN.empty函数, action参数可选但很多调用点未传 | 很多empty状态缺action按钮引导(如数据集列表空→「新增」; 资产表空→「采集全部」)。逐一添加: ①DN.empty('暂无术语', 'inbox', {label:'新增', onClick:()=>openAddForm()}) 替代纯文本; ②确保每个关键列表的空态都有一键快速操作, 减轻用户探索成本
- [M] 表单输入框缺即时校验错误显示 | gov-consumption.js:170-188 数据集表单, gov-security.js:234-251 策略表单, 仅提交时校验 | 当前所有校验(如编码规范/SQL语法/行过滤条件)都在点提交时才做。改进为即时反馈: ①datamodel编码输入时: oninput实时检查/^[a-zA-Z][a-zA-Z0-9_]{0,48}$/, 错误即显示红色提示; ②SQL字段: 检查'SELECT'开头, 禁止';'和注释, 逐行显示; ③代码行过滤: 
- [M] 下拉选择器缺搜索/快速筛选 | 第549-557行 DN.formSelect 纯HTML <select>, 无搜索 | 库/表/字段等下拉无搜索, 长列表难用。建议: ①升级 DN.formSelect → 支持 searchable:true 参数; ②数据量>10时自动显示搜索框; ③下拉选项中输入字母实时过滤(client-side filter); ④可用<input type=search>+<ul role=listbox>
- [M] 批删工单缺全选/反选功能 | 第327行 checkbox渲染, 工单列表无表头全选框 | 工单列表有checkboxes但无「全选」功能, 用户逐行勾选浪费时间。改进: ①在表头<th>第一列加<input type=checkbox class='select-all'>; ②全选框onchange: 批量toggle所有tbody tr的checkbox; ③同时更新「已选N/总M」计数; ④全选后批删
- [M] 异步按钮防重复提交缺视觉反馈 | gov-classification.js:19-21 lockBtn函数, gov-assets.js:1024 btn.style.opacity='.6' | 防重复提交用opacity降低(看起来模糊), 但缺spinner。改进: ①按钮添加 .is-loading类(已定义于dn-design.css:196-202); ②同时显示文字+spinner: '保存中 ⌛' 或 textContent='保存中…'; ③禁用pointer-events:none而不仅opa
- [L] 响应式窄屏缺移动友好设计 | login.html:101 @media max-width:720px隐藏左品牌区, css无更多响应式规则 | 工作台workspace.html无响应式设计(只有登录页响应)。建议: ①顶部header-tabs应横向滚动(已有overflow-x:auto); ②窄屏<700px时: 隐藏tab文字仅显示图标; ③表格列过多时: 隐藏非关键列或启用左右滑动; ④表单两列改单列; ⑤按钮改全宽48px高
- [L] 模态弹窗缺Escape关闭快捷键 | 第738-763行 DN.confirm, 有按Esc关闭但未提示用户 | confirm弹窗按Esc可关闭(第762行listener), 但UI无提示「按Esc关闭」。改进: ①添加title属性或轻提示text: '按 Esc 退出'; ②或在弹窗右上角加×按钮旁添加'[Esc]'键盘快捷键提示(font-size:11px;color:var(--text-muted))
- [L] 表单placeholder不够清晰,易混淆必填/可选 | gov-consumption.js:170-188 placeholder未区分必填vs可选, gov-health.js:546 placeholder=' | 改进placeholder清晰度: ①必填字段: placeholder='如：日活 *' (添加*符); ②可选: placeholder='如：备注 (可空)'; ③或统一用DN.field的required参数控制, 不要在placeholder里重复说明; ④对hint字段强调: hint='字母开头, 长度2
- [L] 数据地图/血缘图缺加载进度提示 | gov-lineage.js:20-29 重建血缘按钮, gov-govmap.js:全局无加载提示 | 重建血缘/绘图时卡顿无提示。改进: ①触发API时: btn.textContent='重建中…'; btn.disabled=true; btn.classList.add('is-loading'); ②若API超过3s未完成: 显示tooltip '预计需要1-2分钟,请耐心等待'; ③完成后: DN.toast
- [L] 键盘Tab焦点顺序未优化 | 第501行 tabindex检查, datamodel.js:152 清除按钮tabindex=0 | 抽屉内可tabindex可达(第501-507行已实现tab陷阱), 但搜索框清除按钮(×)tabindex=0导致可访问性问题。改进: ①搜索清除按钮改tabindex=-1(隐藏), 改用按ESC清空; ②或保持但确保点tab后聚焦顺序合理: 搜索框→清除→表格行; ③表格header th[role=button
- [L] 导出CSV后缺成功反馈和进度提示 | 第307-316行 DN.exportRows, 无toast反馈; ai-agent.js:271 CSV导出无提示 | 用户点导出后无任何反馈, 不知道是否成功。改进: ①导出前: btn.classList.add('is-loading'); btn.disabled=true; ②生成blob→下载: 同步完成, 立即DN.toast('已导出'); ③若数据量大(>5000行): toast改成'正在导出...' + 进度条(可

## nav (15)
- [H] 指标详情跳转 → 内联抽屉展示 | workspace.html:9237, 9480-9495 | onclick="openMetricDetail(id)" | 当前: openMetricDetail() 调用 navigateTo('metrics') 切页面后才加载详情面板。改成: 创建浮层Modal或抽屉(drawer), 在当前位置直接加载和显示指标详情面板(含KPI磁贴、告警、预测等), 点击关闭回到原页面。保留深链能力但优先用抽屉。
- [H] 质量规则跳转 → 内联预填面板 | workspace.html:8800 | onclick="dmBuildRule(column, dimension)" | 当前: dmBuildRule() 调用 navigateTo('governance', {prefillRule}) 跳到治理模块建规则。改成: 在数据地图/项目内弹出"新建质量规则"表单面板(Modal/抽屉), 预填字段、库表、维度, 用户可直接在原页面完成规则配置, 提交后显示成功提示并可选跳转或留在当前页。
- [H] 数据表链接跳转 → 内联表详情抽屉 | workspace.html:9300, 9859 | onclick="navigateTo('catalog', {openTable})" | 当前: 点击 db.table 链接跳到数据地图才能看字段、血缘、质量。改成: 弹出内联抽屉, 展示该表基本信息、字段列表(可搜索)、血缘简图、质量评分, 不跳页。深链仍可用但默认用抽屉。
- [M] 指标问题链接跳转 → 内联问题预览面板 | workspace.html:9607 | onclick="navigateTo('governance', {gov:'issues', issueId}) | 当前: 点击问题ID跳到治理模块查看。改成: 侧边抽屉展示问题标题、描述、优先级、负责人、进展, 可直接评论和状态更新。点"详情"再跳。
- [H] 项目详情跳转 → 内联项目卡面板 | js/project.js:615-625 | onclick="projOpenDetail(id)" | 当前: projOpenDetail() 打开全屏项目详情面板。改成: 改为内联模态抽屉(不全屏替换页面), 展示项目概览、成员、资产、任务卡片(可嵌入看板), 用户留在列表页上下文操作; 需要详情按钮跳全屏时再调navigateTo。
- [M] 数据模型详情跳转 → 内联模型预览面板 | js/datamodel.js:171, 306 | onclick="dmOpenModel(id)" | 当前: dmOpenModel() 跳转到datamodel视图加载模型详情。改成: 在列表或编辑器右侧打开内联抽屉, 展示模型结构、实体、字段(可折叠), 双击实体编辑。"完整编辑"按钮跳全屏。
- [H] 资产跨模块跳转 → 智能内联导航 | js/project.js:930-938 | projAssetGoto(type, assetId) | 当前: projAssetGoto() 针对资产类型(SYNC_JOB/QUALITY_RULE/METRIC等)调用 navigateTo() 跳页。改成: 提供"预览"抽屉(展示资产摘要+关键信息), 如同步任务显示执行历史迷你卡、质量规则显示最新执行结果、指标显示KPI。用户确认后跳"详情"页面。
- [M] 同步任务详情跳转 → 内联执行历史面板 | workspace.html:15619, 15638-15639 | onclick="dbsyncSetJobsView / onclick执行日志查看" | 当前: 任务列表点"查看"跳到同步详情页。改成: 表格行可展开(行内展开器), 展示该任务近期执行历史卡片(含状态、耗时、日志), 点"详情"跳全屏对标页。
- [M] 用户权限编辑跳转 → 内联权限面板 | workspace.html:12341-12347 | onclick="umViewUser / umUserModal / umViewUserPerms | 当前: 点"权限"跳到设置页权限面板。改成: 用户列表右侧打开内联权限抽屉, 可直接选角色/权限(复选框), 保存后不跳页。"详情"按钮展示更多用户属性。
- [M] 登出后页面跳转 → 在线验证后留页面 | workspace.html:12021, 13946, 13982, 14024 | location.replace('login.html') | 当前: 登出或认证失败直接跳login页。改成: 在当前页面弹浮层提示"会话已失效, 请重新登录", 用户点登录按钮才跳, 或设置自动倒计时(3s)后跳。改善UX: 用户不会突然被踢离。
- [M] 审计记录查看跳转 → 内联审计详情抽屉 | workspace.html:12852 | onclick="navigateTo('governance', {gov:'audit'})" | 当前: 主页面板点"查看全部审计"跳审计中心。改成: 主页面板内只显示近5条审计记录, 可滚动加载更多。点单条记录弹详情抽屉(包含操作细节、变更前后对比); "查看全部"按钮跳完整审计页(可选)。
- [H] AI助手启动跳转 → 浮窗/面板式启动 | workspace.html:7700-7705, js/home-dashboard.js:102 | onclick="openAiLauncher() / | 当前: 点AI按钮跳到assistant路由(或外链)。改成: 在右下方或右侧打开浮窗面板(类似ChatGPT侧栏), AI对话框嵌入当前页面, 用户可保持多页背景上下文。对话支持识别当前模块(catalog/governance等)自动带上下文。
- [M] 数据质量规则详情跳转 → 内联规则执行结果面板 | workspace.html:9237 行周边 | 质量规则点击查看 | 当前: 质量规则列表点击跳到quality视图。改成: 列表行右侧快速查看按钮弹内联抽屉, 展示规则最近5次执行结果(柱状图/通过失败统计)、告警设置、阈值; 点"编辑规则"跳编辑页。
- [L] 项目任务关联资产跳转 → 内联资产微预览 | js/project.js:1415 | onclick="projAssetGoto('refType', refId)" | 当前: 任务详情点关联资产链接跳到对应模块。改成: 鼠标悬停或轻点显示资产微卡(名称/类型/最后更新), 点卡片跳详情; 或点小icon弹tooltip预览。
- [L] 登录页面跳转 → 无缝重定向(不闪白页) | login.html:184, 211 | location.replace('workspace.html#/home') | 当前: 登录成功调用 location.replace() 跳workspace#/home 可能闪页。改成: 使用 history.pushState() + DOM切换或fetch预加载workspace, 登录后无刷新切换视图, 用户无感知跳转。

## dedup (15)
- [H] 合并重复的tone/round1/fmtInt工具函数到dn-common.js | gov-overview.js:98-101, home-dashboard.js:11-13, gov-assets.js:1006-1009 | 在dn-common.js中增加DN.tone()/DN.round1()/DN.fmtInt()导出，替换gov-overview/home-dashboard/gov-assets中的重复定义（目前各文件各写一遍）。gov-overview的tone已用于多处，更新后直接调DN.tone(v)；round1和fmt
- [M] 合并4处clip函数为通用的文本截断工具 | gov-assets.js:1012-1017, gov-govmap.js:69, gov-consumption.js:562, gov-lineage.j | 在dn-common.js中增加DN.clip(s, max)函数，兼容3种参数格式（返回DOM节点或字符串），替换gov-assets的返回DOM版本，其余4处改为调用DN.clip。
- [M] 删除governance.html独立页面（仅有跳转逻辑） | governance.html（整个文件） | 该文件仅包含重定向脚本：location.replace('workspace.html#/governance')，无实际内容。直接删除，旧链接由workspace的路由#/governance接管。若需SEO，在workspace.html的<head>中添加canonical或meta规范标签。
- [M] CSS：合并app.css与gov-modern.css中的.gov-card重复定义 | app.css:3836-3848（旧GV2定义），gov-modern.css:34-39（现代定义） | app.css中:is(#govModuleContent,#mdmModuleContent) .gov-card定义应全部删除，由gov-modern.css的:is(#govModuleContent,#mdmModuleContent,#homeCockpit,#metricsConsumption) .gov
- [H] CSS：合并.gov-stat:hover规则中的transform重复 | app.css:3856, gov-modern.css:23, dn-design.css:227-228, modern.css:59, redesign. | 五个文件都定义了.gov-stat:hover的transform: translateY(-2px)。保留gov-modern.css的定义（最全面），删除app.css和dn-design.css中的重复。modern.css作为polish层可保留（全局通用）。redesign.css中同样删除，由gov-mod
- [H] CSS：删除redesign.css中与dn-design.css/gov-modern.css重复的.gov-card hover | redesign.css:249（.gov-card:hover），dn-design.css:227（.gov-stat:hover），modern.css: | redesign.css第249行定义:is(#homeCockpit,...) .gov-card:hover重复了modern.css:56的定义。modern.css已是全局polish层，redesign.css作为驾驶舱专用覆盖层应避免重复。删除redesign.css:249行，保留modern.css版本
- [M] 合并errMsg错误处理函数的5种实现 | gov-classification.js:14, gov-health.js:659, mdm-pubsub.js:19, gov-consumption.j | 5个文件各写一个errMsg(e, fallback)，逻辑雷同。在dn-common.js中增加DN.errMsg(e, fallback)，统一处理e.message/string/default逻辑，各模块改为调用DN.errMsg。
- [M] 删除lockBtn函数重复（gov-assets与gov-lineage） | gov-assets.js:1019-1035, gov-lineage.js:17-? | 两处都实现了按钮禁用+加载态的lockBtn(btn, busyText)。在dn-common.js中增加DN.lockBtn(btn, busyText)导出，gov-assets和gov-lineage都改为DN.lockBtn调用。已在gov-assets.js中见过实现：加is-loading/disable
- [L] CSS：删除app.css中的重复声明:root令牌（--primary等） | app.css:3-32（:root令牌定义），dn-design.css:10-95（完整v3令牌） | app.css:3-32中的:root定义了--primary/--success等，但dn-design.css:10-95更新/更全（v3规范）。由于dn-design加载在后，cascade会覆盖app.css的:root，导致两套令牌混乱。删除app.css中的:root块，统一由dn-design.css单一
- [L] 删除无效的CSS变量前缀兼容写法（var(...,fallback)冗余） | gov-modern.css全文（约30处var()带fallback），dn-design.css:32（var(--text-secondary, #8d9 | gov-modern.css中大量出现var(--border,var(--border))、var(--primary,var(--primary))等，fallback与主值相同，无意义。删除fallback保留主值，简化为var(--border)等。现在所有令牌都在:root中定义，无需降级。
- [L] 删除已过时的斑马纹样式（table tbody tr:nth-child(even)） | app.css:3874（.gov-tbl tbody tr:nth-child(even)），dn-design.css:268（覆盖为background: | app.css定义斑马纹background:var(--bg-main)，dn-design.css v3改为透明（去斑马）。两处规则冲突。删除app.css:3874的斑马纹定义，保留dn-design.css的透明版本（v3设计规范）。
- [M] JS：删除gov-overview.js中已被home-dashboard.js替代的toneColor函数 | gov-overview.js:99（toneColor函数），dn-common.js:343（内部toneColor） | gov-overview.js:99定义toneColor完全重复dn-common.js:343。gov-overview中调用改为DN.statRow内部已用的toneColor逻辑，或统一改为DN.h的pill着色逻辑。删除gov-overview.js:99-100的toneColor和round1，改用全站D
- [M] CSS：合并.gov-pill样式定义中的色调重复（is-ok/is-warn/is-err） | app.css:3878-3883（gov-pill色调），dn-design.css:232-236（.gov-stat.tone-*重复）, gov-mod | 三个文件分别定义.gov-pill的ok/warn/err/info/muted样式，基础色值相同但变量不同。统一以gov-modern.css:66-72为标准，使用--success等语义色，删除app.css和dn-design中的重复定义。
- [L] 删除modernCSS中针对小屏媒体查询的冗余transform规则 | modern.css:356（@media max-width: 690px时 .gov-card:hover { transform: none }） | 此规则为小屏上禁用hover抬升（防密集表抖动）。但已在原主规则中为表格行做了这个判断。删除该媒体查询块或改为仅针对.table tbody tr（而非全局卡片）。
- [M] 合并重复的ellip/clip文本截断逻辑为统一的DN.truncate() | gov-classification.js:165（ellip），home-dashboard.js:17（trunc） | gov-classification定义ellip(s,n)返回字符串，home-dashboard定义trunc(s,n)也返回字符串，逻辑完全相同。在dn-common.js中增加DN.truncate(s, n)，两处改为调用。返回字符串版本（不返回DOM）。

## gaps (13)
- [H] 血缘图譜双向聚焦缺失下钻锚点 - 关系图中找不到特定表 | gov-lineage.js:502-510, renderGraph()后缺添加表检索框 | 图谱容器添加全局搜索框(输入表名→高亮节点+自动定位), 支持上下键快速跳转相邻节点。按 Ctrl+F 激活，避免与浏览器查找冲突。复用 DN.table 搜索样式。
- [H] 质量规则失败聚焦无自动修复建议 - 半成品诊断 | gov-quality.js:169-328, buildFailFocus()仅展示失败, 无根因+修复建议 | 失败规则行增加「AI诊断」按钮(已有框架在311行dnAskAi), 自动调用指标输入质量+血缘影响面接口，合成「该规则目标表的通过率/失败率分布+下游受影响表数」的诊断报告。
- [H] 工单→项目任务双向同步缺反向同步 - 两本账未闭环 | gov-health.js:399-441, issueToProjectTask()创建单向链接，无任务状态反同步 | 任务完成后自动推送工单状态(DONE→VERIFIED→CLOSED)，反向链接taskRef同步状态。后端定时任务/Webhook或前端轮询(低优)同步。
- [H] 数据消费驾驶舱缺指标热度预警 - 僵尸指标发现不及时 | gov-consumption.js:453-520, loadZombies()仅被动展示, 无阈值告警配置 | 「僵尸指标」卡片增加「预警规则」: 设定「N天未消费即告警」阈值，超期指标自动生成治理工单。同时补充消费趋势衰减曲线(近90天消费量周环比)，预示即将僵尸。
- [M] 血缘边覆盖率评估不完整 - 孤儿表检测缺关键指标 | gov-lineage.js:151-286, detectOrphans()仅列孤儿表, 缺全库血缘覆盖率+补全优先级排序 | 孤儿表检测增加「补全建议」: 按孤儿表+断边库/表对，推荐从哪些来源库补齐输入血缘。并给出全库「血缘覆盖率 Top20 待补库表」排行，指导补齐优先级。
- [M] 质量规则×血缘交叉分析缺失 - 规则影响评估不全面 | gov-quality.js:295-316, 失败规则影响面仅查目标表下游, 未查规则失败的上游表风险 | 影响面分析增加「来源表风险评估」: 规则失败→自动查该规则输入数据的所有来源表，标记为「高风险输入源」，提示修复前先要确认来源表数据完整性。
- [M] MDM黄金记录变更审批无审批规则配置 - 流程完全手工 | mdm-approval.js:118-164, submitDrawer()与reviewDrawer()审批人完全依赖后端取当前登录,无审批流定义UI | 在黄金记录详情页增加「审批规则配置」: 按实体类型定义审批链(谁审/需几个人批准/阈值自动跳过等)，存库供后续申请自动匹配。可复用 gov-health 工单流转框架。
- [M] 资产生命周期策略缺执行反馈 - 应用后无确认 | gov-assets.js:723-886, loadPolicies()展示PENDING/APPLIED，无主动查询后端执行进度机制 | 策略列表增加「同步状态」按钮: 后台定期(或用户点击)查 Doris 返回的 DDL 应用结果，实时更新策略状态。显示「APPLIED→真实行数/体量确认」确认政策生效。
- [M] 项目任务与治理工单缺上下文链接 - 跨域信息孤岛 | project.js缺治理工单深链入口; gov-health.js有工单→任务但无反向 | 项目详情「任务」标签页增加「来自治理工单」tab，自动聚合该项目相关成员的未关闭治理工单(按 owner 匹配)。反向给工单「相关项目任务」展示。
- [M] MDM属性编码缺批量词根合规检查 - 手工容易遗漏 | mdm.js:347-371, checkRoots()仅针对单属性编辑时校验, 无批量扫描UI | 实体详情页增加「编码合规检查」按钮: 扫描该实体全部属性编码, 汇总「词根未收录列表」并给出「建议新增词根」清单，一键跳标准词根管理去补录。
- [M] 质量规则×主数据交叉校验缺失 - 规则难复用 | gov-quality.js规则创建form无MDM属性绑定选项, 无复用主数据约束 | 质量规则新增「绑定MDM属性」字段: 选定实体属性后，自动继承该属性的「必填/唯一/数据类型/长度」约束，规则类型下拉自动同步该属性适配的检查类型(如DECIMAL→范围检查)，减少重复定义。
- [L] 数据消费排行缺成本维度 - 高消费表不知成本 | gov-consumption.js:80-101, loadRanking()仅按调用次数排, 无查询耗时/扫描字节数 | 排行表增加「查询耗时」与「平均扫描」列: 调用 /api/consumption/metric-ranking?include=cost 返回平均耗时与扫描字节数，帮助识别「低频但高成本」的指标，优化查询效率。
- [L] 主数据黄金记录缺去重/合并功能 - 多源数据整合不了 | mdm.js黄金记录仅CRUD, 无去重/合并/优先级规则 | 黄金记录列表增加「去重/合并」批量操作: 选多条记录后，弹抽屉让用户指定「主记录」与「合并策略」(保留字段优先级)，后端执行记录合并并生成审计日志。