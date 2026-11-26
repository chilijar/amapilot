# Amapilot 车机 UI 设计规范

依据：Google Maps AA 夜间导航参考截图（第三方素材，版权原因未随仓库分发）。
原则：**布局与操作逻辑**对齐 Google Maps AA；**转向卡视觉**沿用 Amapilot 手机端自研 TBT 风格。

## 整体

- 夜间深色主题（`#040404` 卡片 / 深蓝地图 `#203d69`）
- 地图全屏铺底（高德夜间样式），UI 元素浮于其上

## 布局（相对车机屏，参考 800x400）

| 区域 | 位置 | 内容 |
|------|------|------|
| 转向卡 | 左上 y=10，x 15%~55% | 见下 |
| 底部信息卡 | 左下，x 2%~45% | 大字剩余时间 + 灰字距离（纯信息，无按钮） |
| 地图控件（宿主） | 右侧圆形竖排 mapActionStrip | zoom+ / zoom− / 相机三态 toggle（上限 4 图标） |
| 搜索入口 | 顶栏 ActionStrip | 空闲/巡航 = 搜索钮（push SearchTemplate）；导航态 = 四功能钮（见「交互」） |
| 车速环 | 右下·竖排左侧 (660,328) 56dp | 导航+巡航统一；锚定竖排几何（右缘+8=竖排左缘、同底锚）→ 副窗口随竖排左滑不重叠；极窄窗隐藏；超速分档黄/橙/红 |
| 地图 | 全屏 | 高德夜间样式 + 蓝色主线（细路况彩线内嵌）+ 灰色已走段 + 终点 Pin + 贴地箭头 |

## 转向卡（沿用手机端 TbtScreen.TurnCard 风格）

- 深色 Surface，圆角 24dp
- 左侧 **ACCENT 色机动图标**（56dp，含 45° 缓转变体与车道图标体系）
- 中部：**ACCENT 大号距离**（displaySmall）+ 加粗动作/路名（titleLarge）
- 底部小字：「沿 X」当前道路（onSurfaceVariant）
- 车道抽屉：有车道数据时自卡下缘展开（箭头深色 / 推荐蓝）

## 底部信息卡

- 近黑 `#040404`，圆角 24dp
- 主行：白色大号剩余时间；次行：灰字 `剩余距离 · 当前时刻 · 巡航车速`

## 交互与模板

**模板决策**：空闲/巡航/导航**三态都发裸 NavigationTemplate**（无 NavigationInfo、
无 mapActionStrip），宿主只对地图类模板派发 Surface 手势，且 `SurfaceCallback.onClick`
与 `onScroll` 坐标与车机窗口 1:1 → 自绘控件真实可点、地图可拖。MapWithContentTemplate
只用于设置/选路半屏（**不派发手势**）。

**宿主控件 = 旋钮焦点通道**：
- `NavigationTemplate` 强制 `ActionStrip` ≥1 动作（省略抛 IllegalStateException → 模板
  失败 → 不派发 Surface）
- **顶栏 ActionStrip**：空闲/巡航 = 搜索钮；导航态 = 按功能独立四钮
  [⑂ 选路][📍 途经点][⚑ 改目的地][⚙ 设置]（旋钮焦点环直达）。**上限 4、禁文字动作**
- **mapActionStrip 右竖排**：三态统一 [zoom+][zoom−][相机三态 toggle]（旋钮可焦；
  相机 toggle 纵列最下 = 跟随/北向上/全览循环，FOLLOW 拖偏态再点 = 回正 = 唯一
  "居中类"控件）；与顶栏同样空闲自动隐藏——旋钮命令链连发（≤1s）或先触摸唤醒
- 导航功能触摸路径 = 顶栏钮 / 宿主卡 ✕ / 地图序号徽标
- 旋钮 >4 选项走宿主列表容器（MapTemplate/PlaceListMapTemplate.setItemList 侧栏或
  ListTemplate 屏，无上限可滚动）
- 按键事件不达自绘窗口（Dialog/Presentation.dispatchKeyEvent 无探针）；真车旋钮只
  驱动宿主渲染元素 → **所有自绘交互必须留宿主等价入口**
- DHU 旋钮注入：`-i hybrid` 启动开 rotary 通道；`dpad rotate` = 域内焦点环、
  `dpad up/down` = 推杆跨域、`dpad click` = 激活

**底图字体缩放（MapView 几何缩放）**：高德无公开地名字号 API（仅 `showMapText` 开关 /
线上自定义样式）。`CarMapPresentation` 把 MapView 按 1/0.6 尺寸布局 + pivot(0,0)
`scaleX/Y=0.6`（`MAP_VIEW_SCALE`），底图文字等比缩到 ≈ 卡片字号。补偿清单（屏上尺寸
不变）：线宽经 `glw()`、序号徽标/车箭头位图 ×1/SCALE、`setPointToCenter` 与
`newLatLngBoundsRect` 留白 ÷SCALE、`hitRouteLine` 的 `toScreenLocation` ×SCALE；
`MapHolder.reset` 归 1 供手机屏复用。GL 表面超 2048（真车 1920 宽）时 `mapViewScale`
按 `max(0.6, W/2048, H/2048)` 抬高。

**自绘控件与命中坐标**（`CarUiGeometry.kt` 单一来源，绘制与 handleClick 共用）：

| 控件 | car 坐标 | 说明 |
|------|---------|------|
| 复合行程卡（兜底） | 卡 (8,260) 240x132；卡内钮 (42/99/156/213, 358) | 仅在 `hostTripCard=false` 时渲染；时间大字+距离·ETA+四钮（✕/⑂/📍/🔍，Maps 序）；卡高三档退化 |
| 全览胶囊 | (405,368) 底部居中 | 「返回导航视角」，全览态显示 |
| 右竖排（mapActionStrip） | 三态统一三钮 | 旋钮可焦；相机 toggle 纵列最下；自绘右竖排已删除 |
| 车速环 | (660,328) 56dp（竖排左侧、同底锚） | 导航/巡航共用 SpeedRing；`speedBadgeFits` 极窄守卫 |
| 顶栏功能钮 | (528/604/680/756,42) 导航态 | 四功能钮；静止自动隐藏；空闲态 = 搜索钮；取消 = 宿主卡 ✕ |
| 设置半屏面板 | MWCT 侧栏 | [路线配置·当前 ›][音频播报·当前 ›] → 二级；选中即存即关，地图不遮蔽 |
| 选路 pushed 屏 | 宿主 ListTemplate 行 | `CarRouteSelectScreen`：逐行 click 单击定稿，无 BACK header，无取消行 |
| 宿主返回 | (40,42) | **仅在已知 pushed 屏上点一次**，点后等 ≥8s |

**搜索 = 宿主 SearchTemplate 首页**（`CarSearchScreen.kt`）：顶置搜索栏（键盘不自动弹，
点栏/麦克风唤起 = 宿主固有行为）+ 首页列表 = 历史**地点**行（点击直达算路选路）+「我的
地点 ›」「附近 ›」二级入口（`CarPlacesScreen` / `CarNearbyScreen` 半屏 ListTemplate）；
输入/手机中继切结果态（导航中置顶途经点开关行）。手机中继 `CarSearchRelay` 有输入自动
push 首页。

**复合行程卡 = 宿主渲染**（名称+时间大字+距离·ETA+自带 ✕，`hostTripCard=true` 用户
定稿启用）：✕ = `NavigationManagerCallback.onStopNavigation`（app 须先
`navigationStarted()`）；「算路中…」= overlay 顶部 `searchStatus` pill。自绘整卡仅
`hostTripCard=false` 兜底。结构化 lanes 必须同时 `setLanesImage` 否则
`IllegalStateException`。

**模板只在会话状态切换时 invalidate**：ticker 每秒重发会让宿主重渲染内容卡（入场动画
反复重播 = 搜索框闪烁）；秒级数据全部走自绘叠加层。勿放 `CarIcon.PAN`（宿主无响应）。

**重导航**（导航中再选目的地）：`CarRouteStarter` 先 `NaviSession.stop()` 再算路
（引擎导航中吞掉 `calculateDriveRoute` 回调 → 协程挂死；`startNavi` 导航中也被忽略），
算路带 15s 超时兜底，`start()` 有重入守卫。

## 选路模式（pushed 屏）

- 模板 = **MapWithContentTemplate pushed 屏**（`CarRouteSelectScreen`）：自绘地图
  Surface + 宿主侧栏 ListTemplate 行（旋钮逐行可焦 / 触摸可点）；back/dpad back =
  pop 回栈下屏；地图切 **2D 北向上全程视野**
- **单击定稿**：行 = 逐行 `setOnClickListener`；**不挂 header BACK 钮**（否则旋钮初始
  焦点落"返回"）；取消 = 宿主返回通道
- 路线线/序号徽标 = **Compose Canvas 自绘**（`SelectGraphicsLayer`：投影换算 +
  cameraTick 逐帧重投影；MWCT 态引擎覆盖物新增不进渲染层，引擎层弃用）；线宽
  11/13/19px、徽标 44px（当前=蓝底白字、其余深底灰框、弧长比例 0.50/0.25/0.80 错开）；
  取景 = `fitPathsToVisible` 手动可见区拟合
- 选择途径：侧栏行（旋钮/触摸）/ 地图线（40px 命中）。选择或取消后回 3D 车头向上
- 数据实时性：打开即 `NaviSession.refreshRouteData()` 重导入 naviPaths；`onNaviRouteNotify`
  订阅自动刷新面板行与地图线

## 预导航选路

- 搜索行点击 → overlay pill「算路中…」→ `CarRouteStarter` 先 `planDriveRoutes`；
  >1 条开预导航选路侧栏（与导航中选路同形态，`CarMapControl.preNavSelectMode`），
  行点击 `commitPreNav` 以该路线 `NaviSession.start(initialPathId)`（`selectRouteId` 仅
  导航前生效，先 start 再切会被拒绝）；单路线直接 start
- 与导航中选路的差异：行点击不 `selectRoute` 而 commitPreNav；退出不 `exitRouteSelect`
  地图态；重发搜索即清预导航态

## 路线渲染（线宽体系 + 图层序）

设计意图：**路线是主信息，路况是辅助信息**——细蓝主线打底，中央嵌一条更细的路况彩线，
已走段用同宽灰线盖掉。（勿改回"整条路线按路况染色"的粗线方案。）

| 层 | 内容 | 宽度 | zIndex |
|----|------|------|--------|
| 底 | 蓝色主线 `#1A73E8` | `ROUTE_WIDTH` = 13 | 10 |
| 中 | 路况细彩线（`colorValues` 逐段） | `TRAFFIC_WIDTH` = 6 | 11 |
| 上 | 已走灰线 `#6B7280` | `PASSED_WIDTH` = 13 | 12 |
| 顶 | 车头箭头 Marker | — | 999 |

- **所有关键 marker 显式高 zIndex**（箭头 999、终点 Pin 30、途经点 31）：polyline 带
  显式 z 时默认 marker 会被压到线下
- 路况配色（`AMapTrafficStatus.status`）：1 畅通 `#00A850` / 2 缓行 `#FFB300` /
  3 拥堵 `#E53935` / 4 严重拥堵 `#B71C1C` / 未知 = 主线蓝（视觉隐形）
- 数据源 `NaviSession.routeTraffic`：`AMapTrafficStatus.linkIndex` 是 **link 索引**，
  必须经 `NaviSession.routeLinkStarts`（steps→links 展平+比例缩放）换算成坐标偏移；
  1Hz 哈希比对，仅真变化才重建细彩线（主线不动 → 无闪烁）
- **灰线边界 = GPS 窗口化投影为主、引擎 remain 兜底**：`projectOnRoute` 投影到弧长
  （与箭头同源），搜索限上一拍边界 ±250m（防掉头段吸到对向段）；垂距 >60m 退回引擎
  traveled-based（`grayTraveledBoundary`，首帧锁存；引擎 remain 比几何弧长短 ~300m，
  勿直接当弧长）；统一棘轮（只进不退+速度帽）。边界点跨顶点线性插值、`setPoints`
  原地换点
- **车辆屏幕锚点**：相机 target = 车辆位置沿航向前推 `CAR_AHEAD_SCREEN_PX`=48px（按
  zoom 换算米）→ 车在屏幕中心下方 ~48px（y≈248）；灰/蓝边界应落在该处附近
- 终点 Pin / 途经点 / 白描边 / 序号徽标见「选路模式」

## 多窗口 / 副窗口自适应

宿主切多窗口、展开媒体副窗口或用 chrome 遮住部分表面时，**表面宽高与可见区都会变**。

**尺寸单一化**：`CarScreenMetrics(width, height, inset*)` → 内容区（`CarMapControl.metrics`
State）。叠加层读取即订阅；`handleClick` 读同一份值 → 绘制与命中永远一致。

**三条感知路径**：①`onSurfaceAvailable` 新尺寸 → `VirtualDisplay.resize(w,h,160)` +
更新 metrics；②`onVisibleAreaChanged(Rect)` → 更新内缩（副窗口走这条）；③Presentation
`decorView` 的 `OnLayoutChangeListener` 安全网。

**密度对齐宿主 dpi**：`vdDensityDpi` 跟随 `SurfaceContainer.getDpi()`（兜底 160）。
换算边界：Compose 叠加层 metrics 经 `scaledBy(160/D)` 归 dp；`handleClick` px ×(160/D)；
相机/取景/投影恒表面 px（`hitRouteLine` 用引擎投影原值）。

### 宿主可见区逐边策略（勿一刀切）

宿主报的 Rect 与屏幕上的实体 chrome 不完全一致：

| 状态 | 宿主报的 Rect（实测） | 实体 chrome |
|------|--------------|----------------------|
| 常态 | (12, 12 - 788, 388) | 无（地图画到表面边缘） |
| 图标条显示 | (12, **88** - 788, 388) | 无（bar 左侧透明，只在右侧画图标），12↔88 反复跳 |
| 副窗口展开 | (**22**, **34** - **464**, 376) | 左/上/右各 ~10px 黑边 + 右侧媒体面板（x≈464 起） |

最终策略（`CarScreenMetrics.of`）：

| 边 | 规则 | 依据 |
|----|------|------|
| 左 / 右 | 只让开**超出标称 padding 的部分**：`max(0, inset − 12)` | 副窗口报 22 → 让 10，与实测黑边吻合 |
| 上 | **阈值判定**：报告值 ≤ 48 才让开，> 48 一律忽略 | 34（副窗口）要让；88（透明图标条）不能让，否则卡片上下窜 |
| 下 | 一律忽略 | 表面内无实体 chrome（dock 在 y≥400 外） |

### 取景中心避让

`AMap.setPointToCenter(cx, cy)` 把相机目标点从表面中心挪到可见区未被自绘覆盖物占用的
位置（`CarUiGeometry.mapCenterX/mapCenterY`）：

- X：夹在「左列卡片右缘 + 24」与「右竖排左缘 − 24」之间。常态含几何中心 → 不偏移；
  副窗口态几何中心落进卡片列 → 取**剩余可用空间的 1/3 处**（贴边太近、居中则前方视野
  不足）
- 只避让**常驻**卡片（转向/路程卡列），不为弹出面板让位（瞬态浮层盖地图，挤中心会让
  箭头开合时突跳）
- Y：矮窗下中心上移，保证车头始终在按钮行之上 24px
- 全览/选路取景统一 `fitPathsToVisible`（bbox 米跨度 ÷ 可见区像素 → log2 换算 zoom，
  一步 CameraPosition + bbox 中心锚到可见区中心），不依赖引擎 fitter

### 布局重排与过渡

**锚定**：左（卡片/面板）、右（竖排）、下（复合卡/胶囊/竖排）、居中（全览胶囊）。

**不缩放，只重排**（AA 最小触摸目标 48dp）：
- 按钮恒 48dp；窄窗只收窄卡内按钮间距（12 → 下限 4）
- 复合卡宽上限 240、下限夹到内容区（容得下 4 钮）
- 矮窗压缩竖排间距（80 → 下限 56），`selfRailY0` 夹到内容区上缘
- 矮窗复合卡三档退化 FULL→MIN→MICRO（按钮恒在卡内），任何情况下路程卡不压转向卡
- 四边间距统一 `EDGE_MARGIN=8`

**平滑过渡**（三处都得动，缺一处 = 跳变）：
1. 叠加层：动画化 metrics 本身（`animateIntAsState` 280ms FastOutSlowIn），派生位置由
   同一份插值算出
2. 取景中心：`setPointToCenter` 瞬时生效 → `ValueAnimator` 逐帧插值（280ms）
3. 相机：过渡窗口内 `moveCamera` 也走 `animateCamera(280ms)`，否则下一帧直接打断动画

**resize 后必须重解相机**：跟随态重跑 `moveCamera(lastLat, lastLng, **lastHeading**,
lastSpeedKmh)`（传 0 会把视角甩回正北）；全览/选路态重新拟合取景。

**验证**：`CarUiGeometryTest`（纯 JVM）——裸表面点位、宿主三态内缩、90 种尺寸组合
扫描（不溢出/触摸目标不缩水/卡片不重叠）、取景中心避让、矮窗抬升。最小支持宽度
≈ 244dp。

## 相机行为

- **跟随帧率 = 250ms 外推帧**：引擎/系统位置流 ~1Hz，逐拍跳台阶 + 长动画打断重启 =
  顿挫根因。`NavigationScreen` ticker 250ms 一帧，帧间 dead reckoning（fix 坐标变化 =
  新锚点；外推距离 = 速度 × 锚龄沿航向前推，锚龄帽 1.5s；低速 <1m/s 或航向无效不外推）；
  相机动画 = 250ms（帧周期，长动画 = 减速曲线重启 = 速度脉动）
- **跟随态**：3D `CAR_TILT`=70°、车头向上（相机 bearing = 平滑后航向）；低速临近机动
  （≤500m 坡道 × ≤30km/h）上抬回 55°（`maneuverLiftTilt`）。调试覆盖
  `amapilot_car_tilt` / `amapilot_follow_anim_ms`
- **贴地箭头**：AMap flat Marker 的屏幕角 = `rotateAngle` **+** `cameraBearing`（加法，
  与官方文档相反）→ 代码 `rotateAngle = heading − 2×cameraBearing`；箭头带 1.59 压扁比。
  **勿按文档写减法**
- **pan 压平**：拖拽累计 > `PAN_FLATTEN_PX`=60px → 平滑切 2D 正上（tilt/bearing 归零，
  保留目标点与缩放）+ 点亮「返回导航视角」胶囊；每轮拖拽只触发一次
- **全览（⛶）**：2D 北向上 + 路线边界；箭头按实际航向旋转（跟随态恒指屏幕上方）
- **航向解算三级兜底 = 中值滤波 → 位移推算 → sticky 保持**：末级**勿用 `cam.bearing`**
  （全览/北向上态恒 0，停车会甩向北）；`loc.bearing` 仅 >5km/h 采信，`roadBearing`
  不受限；巡航合成位置 bearing 仅 >1.4m/s 同步
- 全览退出：「返回导航视角」胶囊 (405,368) / 地图空点 / ⌖ / 再按 ⛶
- **速度自适应缩放**：zoom 曲线单一来源 `AutoZoom`（速度基础项 + 机动距离拉近项，夹
  [13, 18.5/19.1]）；初始 14.5；按时间归一平滑逼近（手机 1Hz ≤0.2/tick，车机 250ms
  帧 = 0.05/帧）；手动缩放保持 15s；航向 ≤5°/帧 + **2s 中值滤波**；速度取
  NaviState/CruiseState.speedKmh（**禁用** `AMapNaviLocation.speed`——单位混杂）
- **到达 → 自动进巡航**（模拟器 EMULATOR 永不触发 `onArriveDestination`，真机待验）

## 车道规则

- 面板显示门槛：距 maneuver ≤ **2000m**（高速匝道复合车道与当前路面不符，对齐 Google）
- **掉头高亮**：优先专用掉头车道；无则回退最左左转车道（右掉头=最右右转），且仅高亮
  该条；环岛掉头（ENTRY_RING_UTURN）同左掉头。手机/车机共用 LaneDirections

## 实现要点

- 全部绘制在 VirtualDisplay 的 Presentation 内（Compose 叠加 MapView）
- **持久化地图**（MapHolder 模式）：VD / Presentation / 引擎为 companion 级共享单例，
  Surface 热换用 `VirtualDisplay.setSurface`；`stop()` = detach 不销毁，`destroy()` 仅
  进程级。避免每次会话重建引擎（转译模拟器 4~6s 会撞宿主 5s 回调时限）
- 回调在 binder/主线程：`onSurfaceAvailable/Destroyed` 内只做毫秒级投递，重活甩后台 +
  延迟，否则宿主 5s 超时 → 收回 Surface
- `setSurfaceCallback` 重注册会让宿主销毁重建 Surface → 只在"尚未拿到 Surface"时按
  10s 间隔重试，拿到后停手；Surface 销毁后恢复重试
- 数据源复用 NaviSession.state / CruiseSession.state / lanes / routeTraffic；
  **activeFlow**（Navi/Cruise 合流）修卡片迟到
- 手机端与车机端共用同一套语义（Distance/时间格式、图标体系、车道规则）
- 车机端发起导航必经 `CarRouteStarter`（先算路再 start，否则卡片 0m/0min）
