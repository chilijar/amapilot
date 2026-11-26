# AI 开发指南

## 项目上下文

应用名称：**Amapilot**（包名 `com.amapilot`）——基于高德地图 SDK 的 Android 导航应用：地图/搜索/定位、自研 TBT 导航、巡航模式、Android Auto 自绘车机屏、分享位置接收、收藏与地点历史、沿途搜索、设置页、高德隐私合规首启弹窗。

## 技术栈

- Kotlin · MVVM + Jetpack Compose（Material 3）
- minSdk 26 / compile & targetSdk 36
- 高德 Navi/3DMap 11.3.300 + Search 9.8.1 + Location 11.3.300（官网一体包，`scripts/fetch-amap-sdk.sh` 拉取并 md5 pin；SDK 二进制不入库）
- androidx.car.app 1.7.0（AA Host API 8）

## 项目结构

```
app/src/main/java/com/amapilot/
├── AmapilotApp.kt          # Application：进程级 key 注入（覆盖全部入口）
├── map/
│   ├── MapScreen.kt        # 地图主页：搜索栏/路线卡/多路线/定位FAB/巡航卡片/收藏抽屉
│   │                        #   + 地点卡（底图 POI 点击/长按选点 → 周边分类/关键词搜周边/〔路线〕探索链）
│   ├── MapHolder.kt        # 全局共享单例 MapView（三屏复用；离屏绝不 onDestroy）
│   ├── MapMode.kt          # 浏览/巡航/导航模式（定位图标样式）
│   ├── HeadingSensor.kt    # 旋转矢量传感器航向
│   ├── PoiSearcher.kt      # 搜索封装（Inputtips/PoiSearch/regeoPoint；NEARBY_CATEGORIES 周边分类）
│   ├── SearchScreen.kt     # 搜索页（联想/历史/结果）
│   ├── SearchViewModel.kt  # 搜索页 ViewModel（防抖联想）
│   ├── LocationSources.kt  # 系统 GPS 定位源（绕 AMap 定位鉴权）
│   └── LocationManager.kt  # （stub）
├── navigation/
│   ├── NaviSession.kt      # 导航数据层（StateFlow，手机+车机共用；路线/路况/车道/已走边界）
│   ├── NaviText.kt         # 方向卡共用文案 + naviRoundMeters 距离梯级（显示/播报单一来源）
│   ├── TbtScreen.kt        # 自研 TBT 屏（转向卡/上拉面板/车速环）
│   ├── RoutePlanner.kt     # 多路线驾车规划
│   ├── AutoZoom.kt         # 速度+机动距离自适应缩放曲线（手机/车机单一来源）
│   ├── GuidanceController.kt # 机动/语音播报调度
│   ├── VoiceSpeaker.kt     # 系统 TTS 播报
│   ├── NavigationLauncher.kt # AmapNaviPage 入口（备用）
│   ├── CruiseSession.kt    # 巡航数据层（GPS 统计+引擎电子眼；幂等 start）
│   ├── CruiseScreen.kt     # 巡航屏（3D 视角/电子眼卡/车速环）
│   ├── CruiseMode.kt       # 自动进入巡航判定
│   ├── LaneDirections.kt   # 车道方向/高亮解析
│   ├── ManeuverIcons.kt    # 机动/车道图标
│   └── NavigationIcons.kt
├── data/
│   ├── FavoriteRepository.kt / FavoriteValidator.kt  # 收藏（家/公司/普通，JSON 持久化）
│   ├── HistoryStore.kt     # 搜索历史
│   └── PlaceHistoryStore.kt # 地点历史（车机首页/手机历史区同源）
├── settings/
│   ├── SettingsScreen.kt   # 导航偏好/语音播报/昼夜模式/配置备份（Prefs 持久化）
│   ├── KeySetupScreen.kt   # 首启 key 引导页
│   ├── AmapKey.kt          # 内置/覆盖 key 判据
│   ├── AmapPrivacy.kt      # 高德隐私合规断言（maps/search/location 三模块）
│   └── ConfigBackup.kt     # 配置加密导出/导入（.amapilot）
├── auto/                   # Android Auto（自绘地图 + NavigationTemplate，见 docs/CAR_UI_DESIGN.md）
│   ├── NavigationCarAppService.kt / NavigationSession.kt  # CarAppService 入口/会话
│   ├── NavigationScreen.kt # 三态统一裸 NavigationTemplate + 250ms ticker + Surface 重试
│   ├── CarMapDisplay.kt    # 核心：CarMapControl 单例 + 持久 VD + CarMapPresentation
│   ├── CarMapUi.kt         # Compose 叠加层（转向卡/路程卡/按钮行/车道/右轨）
│   ├── CarUiGeometry.kt    # 全部几何常量（绘制与命中测试单一来源）
│   ├── RouteFollowGeom.kt  # 沿路线折线外推几何
│   ├── PassedRouteRatchet.kt # 已走边界棘轮
│   ├── CarRouteStarter.kt  # 车机端发起导航（先算路再 start）
│   ├── CarRouteSelectScreen.kt # 选路 pushed 屏
│   ├── CarSettingsScreen.kt # 设置半屏
│   ├── CarSearchScreen.kt  # 宿主 SearchTemplate 搜索屏
│   ├── CarSearchRelay.kt   # 手机输入 → 车机搜索中继
│   └── HostDispatchHealer.kt # 宿主双 bind 自愈
├── share/
│   ├── ShareReceiveActivity.kt / AmapShareResolver.kt / CoordConverter.kt
└── ui/
    ├── MainActivity.kt     # 隐私→权限→key→地图/搜索/TBT 页面切换
    ├── components/         # LaneRow、SpeedRing（手机+车机共用）、PrivacyPolicyDialog、GmComponents
    └── theme/              # Material3 主题（GmIcons/Motion/Shape/Type/Color）
```

## 高德 SDK 集成陷阱（结论速查）

- **无可嵌入导航 View**：`AMapNaviView` 不存在；入口 = `AmapNaviPage.showRouteActivity` +
  `AmapNaviParams(start, ways, end, DRIVER, AmapPageType.NAVI)` +
  `setNeedCalculateRouteWhenPresent(false)`（跳过 SDK 路线预览直达 TBT）。
  `getCustomNaviView/BottomView` 钩子调用链存在但容器从不显示——自绘 UI 只能走
  MapView + AMapNavi 数据回调（TbtScreen 方案）。
- **模拟器定位被海外权益拦截**（fake 基站海外 MCC → `INSUFFICIENT_ABROAD_PRIVILEGES`）：
  用 `setLocationSource(systemGpsSource(...))` 直读系统 GPS；TBT/巡航用 NaviSession 位置源。
- **manifest 必需**：`APSService`（`com.amap.api.location.APSService`）、`WAKE_LOCK`、
  `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`，缺任一则定位静默失败。
- **MapView 生命周期 = 全局单例 MapHolder**：引擎 `onDestroy()` 后重建极慢（模拟器
  40~90s 灰底，真机切屏同样有空白窗口）。三屏经 `MapHolder.acquire()` 复用同一实例，
  `reset()` 在 AndroidView factory 中清覆盖物/监听。**离屏绝不 onDestroy**，仅
  MainActivity 销毁时 `MapHolder.destroy()`。勿改回每屏自建 + onDispose 销毁。
- **edge-to-edge**：顶部组件必须 `statusBarsPadding()`（状态栏触摸区吞点击），底部卡片
  `navigationBarsPadding()`。
- **限速无 API**：限速取自 `updateCameraInfo`（测速摄像头数据），车速环超速判定基于此。
- **底图字号无 API** → 车机 MapView 几何缩放 `MAP_VIEW_SCALE=0.6`（按 1/SCALE 尺寸布局 +
  pivot(0,0) scaleX/Y），地名缩到接近卡片字号。全部补偿链：线宽 `glw()`、徽标/箭头位图
  ×1/SCALE、`setPointToCenter`/bounds 留白 ÷SCALE、`hitRouteLine` 的 toScreenLocation
  ×SCALE；`MapHolder.reset` 归 1 供手机屏复用。GL 表面超 2048 时 `mapViewScale` 按
  `max(0.6, W/2048, H/2048)` 抬高（真车 1920 宽会超 buffer 上限、右侧裁切）。
- **VD 密度对齐宿主 dpi**：VD 密度跟随 `SurfaceContainer.getDpi()`（异常兜底 160），
  引擎地名/Compose dp·sp 与宿主 chrome 同基准。`px==dp` 仅密度 160 成立，三条换算边界：
  ①Compose 叠加层 metrics 经 `CarScreenMetrics.scaledBy(160/D)` 归 dp；②`handleClick`
  事件 px ×(160/D) 转 dp 再比几何（`hitRouteLine` 例外 = 引擎投影即表面 px 原值）；
  ③相机/取景/投影链路恒表面 px。线宽/徽标/箭头 = dp 语义（×`densityFactor` ÷SCALE）。
- **MWCT（MapWithContentTemplate）引擎覆盖物/相机不可靠**：该模板态新增覆盖物不进渲染层、
  `newLatLngBoundsRect` 取景跳国家级视野 → 选路图形改 Compose Canvas 自绘
  （`SelectGraphicsLayer` 逐帧重投影）；取景统一走手动可见区拟合 `fitPathsToVisible`
  （bbox 米跨度 ÷ 可见区像素 → log2 换算 zoom；锚点后置 `setPointToCenter` + 同步
  lastCenter 账本）。勿依赖引擎 fitter。选路模板 = MWCT（自绘 Surface + 宿主侧栏
  ListTemplate 旋钮行）；预导航 refit 取 `selPaths` 缓存（引擎 routePaths 空会清缓存）。
- **宿主 SearchTemplate 键盘**：`setShowKeyboardByDefault(false)` 不自动弹；点搜索栏/
  麦克风唤起 = 宿主焦点固有行为（app 层不可抑制）。
- **选路屏单击定稿**：commitPreNav **不**自动重 push 选路屏（否则="必须按两下"）；
  `CarRouteSelectScreen` 不挂 header BACK 钮（否则旋钮初始焦点落"返回"）；路线行 =
  逐行 `setOnClickListener`（radio 语义下点已选中行无变化 = 又一"按两下"来源）；
  取消 = 宿主返回通道（无「取消」行）。
- **多路线**：`calculateDriveRoute(..., DRIVING_MULTIPLE_ROUTES_DEFAULT)` 返回 naviPaths。
  **导航中换路线 = `recalculate` 从当前位重算（唯一可行通道，勿再尝试精确选路）**：
  `selectRouteId` 返回 true 但引导流不换（归属过滤丢 info = UI 冻结）；`selectMainPathID`
  无论新旧集合都只发一拍数据后引导流停死。recalculate 起点重锚车位、流持续活跃；
  `NaviSession.selectRoute` 已改走此通道，pathId 降级为换路意图。结果几乎相同
  （len<5% 且 time<10%）或选项 <2 时自动轮换策略再算一次。**导航前** `selectRouteId`
  正常生效（预导航流程走 start 的 initialPathId）。naviPaths 的 allLength 行驶中不刷新
  （原点锚定）。
- **`calculateDriveRoute` 参数顺序陷阱**：3 参重载 `(to, wayPoints, strategy)`（无起点，
  隐式当前位置；把 from 传进去会算出往返环）；4 参实际语义 `(from, to, wayPoints,
  strategy)`——途经点是第 3 参，按列表顺序生效。勿按官方文档 `(from, ways, to)` 写。
- **车道条高亮 = 引擎逐车道推荐为主 + 距离判定兜底**：`AMapLaneInfo.isRecommended` 是
  引擎对"该路口走哪条道"的原话（路口作用域、不受机动远近干扰）→ 推荐车道整箭头高亮；
  机动点路口且推荐含机动方向时仅高亮机动分支；矛盾抑制（车道组无人能执行机动方向，
  如窄路借道形态）→ 照常显示但 `laneBarMisleadsManeuver` 全灰（用户定稿：借道由用户
  自行判断）。引擎零推荐才兜底：`curStepRetainDistance − curLinkRetainDistance` ≤ 分档
  容差（转向 70m / 掉头 140m）= 车道条路口即机动点。**旧 link 计数判据（linksAhead≥2
  =途中）已证不可靠**，仅 linkRemain 缺失时最后兜底。
- **宿主 chrome 契约（car-app 1.7.0）**：`RoutingInfo`+`TravelEstimate` 让宿主渲染复合
  行程卡（名称+时间大字+距离·ETA+自带 ✕，旋钮可焦）。**`hostTripCard = true` 定稿启用**
  （真车形态曾失控，回退启用后以宿主卡为准）；自绘整卡四钮仅在 `hostTripCard=false`
  兜底。✕ = `NavigationManagerCallback.onStopNavigation`，app 须先 `navigationStarted()`。
  机动卡/车道条自绘；结构化 lanes 必须同时 `setLanesImage` 否则 `IllegalStateException`。
- **右竖排（mapActionStrip）三态统一 = [zoom+][zoom−][相机三态 toggle]**（旋钮可焦）。
  相机 toggle 纵列最下 = 跟随/北向上/全览循环，FOLLOW 拖偏态再点 = 回正（唯一"居中类"
  控件）。**车速环 = 右下·竖排左侧**：`speedBadgeX` = 竖排左缘−8−56、同底锚，副窗口随
  竖排左滑不重叠；`speedBadgeFits` 极窄守卫。
- **导航态顶栏 = 按功能独立钮**：[⑂ 选路][📍 途经点][⚑ 改目的地][⚙ 设置]（旋钮焦点环
  直达，宿主 4 动作硬上限）；静止自动隐藏；取消 = 宿主卡 ✕。透明图标隐形槽已证伪
  （宿主焦点系统跳过透明动作）；自绘像素进宿主焦点环 = 进程边界不可能。
- **设置/选路 = OsmAnd 式半屏**（MWCT + 内容 ListTemplate，地图保持可见）：设置 =
  [路线配置·当前 ›][音频播报·当前 ›] 二级（四 toggle / 播报模式三档 radio + 内容三
  checkbox，即存即关、与手机同源 Prefs）；选路 = pushed 屏逐行单击即提交即关 + pop。
- **ActionStrip / mapActionStrip 硬上限 4 动作**（超限宿主 `validateOrThrow` 抛异常
  杀进程）。"≥1 动作"仅 NavigationTemplate 强制（空/省略 → 模板失败 → Surface 不派发）；
  **MWCT 可整体省略 ActionStrip**（勿挂无功能占位钮）。
- **搜索屏 = 顶置搜索栏首页 + 二级半屏**：首页 = 历史**地点**行（不记搜索关键词——
  用户目标始终是地点）+「我的地点 ›」「附近 ›」入口行；输入/手机中继切结果态
  （导航中置顶途经点开关行）；行点击 = 记地点历史 + 发起导航/加途经点后 pop。
- **`AMapTrafficStatus.linkIndex` 是 link 索引不是坐标索引**：路况分段染色必须经
  link→坐标偏移表换算（`NaviSession.routeLinkStarts`，steps→links 展平+比例缩放），
  否则色带整体错位。
- **已走灰线边界 = GPS 窗口化投影为主、引擎 remain 兜底**：`projectOnRoute` 把车位投影
  到路线弧长（连续、与箭头同源），搜索限上一拍边界 ±250m 弧长窗口（全局最近点在掉头段
  有歧义，会吸到对向段）;垂距 >60m = 偏离，退回引擎 traveled-based
  （`grayTraveledBoundary`，首帧锁存基准）。**引擎 t=0 `routeRemainMeters` 比几何弧长
  系统性短 ~300m，勿直接当弧长**（灰线开局超前箭头）。统一棘轮（只进不退+速度帽）。
- **覆盖物 zIndex**：polyline 带显式 z（车机路线/路况/灰线 10~12）时默认 marker 会被压到
  线下——箭头必须显式 999、终点 pin 30、途经点 31。
- **设置卡 SHA1 取 `signingCertificateHistory` 首项**：`apkContentsSigners` 首项在 Play
  签名包上是 Source Stamp 附加证书（错值 → 三元组无匹配 → **1008 INVALID_USER_SCODE**，
  底图照常 = 强误导）。控制台绑定一律以 apksigner/keytool 对安装包验签值为准。
- **车机跟随相机平滑**（`NavigationScreen` ticker, 250ms）：①外推速度 = min(NaviInfo 状态
  速度, fix 间位移速度 EMA)；锚龄帽 1.5s。②沿路线折线外推（`RouteFollowGeom`，偏线 >60m
  回退 bearing 直线）。③显示位恒速吸收（残差按 `4+车速×1.0` m/s；**系数必须 ≥1.0**，
  否则 >29km/h 失锁顿挫；>60m 直接吸附）。相机动画时长 = 帧周期（FOLLOW_ANIM_MS=250，
  长动画 = 减速曲线重启 = 速度脉动）；箭头 `TranslateAnimation+LinearInterpolator`。
  航向中值窗 2s + 32°/s 步进帽。稳态短路（目标未变不重发）。俯仰 70°，低速临近机动
  （≤500m 坡道 × ≤30km/h）上抬 55°（`maneuverLiftTilt`）。调试：
  `amapilot_follow_anim_ms` / `amapilot_car_tilt`（10s 缓存）。
- **v11 行为差异（全部必守）**：①引擎 GPS 不随 startNavi 隐式启动 → 导航/巡航都必须显式
  `startGPS()`，`stop()` 对称 `stopGPS()`；②搜索+定位模块隐私静态独立于 maps，须
  分别 `updatePrivacy*`（`AmapPrivacy.assert`）；③带 location+距离排序的关键字搜索退化
  为周边搜 → 主搜索用 city+cityLimit 恢复全城。
- **分享路径竞态**：`NaviSession.start` 可能先于算路回调（paths=[]），v11 引擎不会在
  迟到算路后自启 → isActive 悬空 = 幽灵导航（0/0）。自愈：`naviRunning` 标记 + 算路
  成功回调补 `startNavi` + 悬空态失败 `stop()`。key 注入收敛到 `AmapilotApp.onCreate`；
  算路起点 `CarRouteStarter.currentLocation` 需网络/融合兜底（室内 GPS lastKnown 为 null）。

## 开发规范

- Kotlin 编码规范 + Android 最佳实践；函数/类写 KDoc。
- 命名：类 PascalCase、函数 camelCase、常量 UPPER_SNAKE_CASE、资源 snake_case。
- 提交：Conventional Commits（`type(scope): description`，英语）。**用户未明确要求时
  不创建/推送 tag**（release 由 `v*` tag 驱动）。

## 关键配置

### API Key 与签名

- debug/release 共用 `keystores/release.keystore`（不入库；SHA1 `FD:D6:AF:…:68:D9`，
  凭据见私有运维记录）。新克隆者自备 keystore 并为自己的 SHA1 申请 key：
  `keytool -genkeypair -v -keystore keystores/release.keystore -alias amapilot -keyalg RSA -keysize 2048 -validity 10000`
- CI：签名走 secrets（`RELEASE_KEYSTORE_BASE64/PASSWORD`、`RELEASE_KEY_ALIAS`、
  `RELEASE_KEY_PASSWORD`），未配置产出 unsigned APK。
- **key 架构 = Release 零内置 key**：release.yml 不注入 `AMAP_API_KEY`；全体用户自持
  配额（合规分析见 `docs/OPEN_SOURCE_COMPLIANCE.md`）。运行时覆盖支持
  （`Prefs amap_api_key_override`，`MapsInitializer/AMapNavi.setApiKey` 早注入）+ 首启
  `KeySetupScreen` 引导（换机捷径 = 导入 `.amapilot` 备份）；`AmapKey.bundled/isConfigured`
  为判据；车机入口无引导页（手机配置后 SharedPreferences 全局生效）。
- **配置备份** = SAF 导出/导入 `.amapilot`（`ConfigBackup`：AES-256-GCM 内置密钥、无
  口令 = 混淆级防护；文件自描述 + 白名单校验；刻意不用 Keystore——卸载即灭失、换机不可解）。

### 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES"/>
```

## 常用命令

```bash
./gradlew assembleDebug   # 构建 Debug
./gradlew test            # 单元测试
./gradlew lint            # 代码检查
./gradlew clean           # 清理
```

## 已知问题

1. **AA launcher 不显示侧载应用**：AA 17.x `CAR.VALIDATOR` 只放行 Play 安装/Google 签名/
   白名单；侧载恒 `Package DENIED`。**唯一稳定解 = Play 内部测试轨道**
   （`docs/PLAY_UPLOAD_GUIDE.md`）。launcher **枚举**与会话**启动**是两条独立路径：
   validator 只门控枚举（侧载无 Play 凭证恒拒），DHU 直连/head unit server/车机自动恢复
   不走枚举 = 侧载调试一直可用。伪装 MEDIA 不可行（validator 交叉验证 desc/category）。
2. **v11 SDK 交换**：Maven 版 `navi-3dmap` 停在 10.0.800（仅 4KB 对齐，16KB 页设备
   dlopen 失败）→ 官网一体包 11.3.300（`Align=0x4000`），`scripts/fetch-amap-sdk.sh`
   + `.github/actions/setup-sdk` 拉取。**SDK 二进制勿 `git add -f`**。残留观察：path 级
   remain 虚高 ~500 倍仅模拟器 EMULATOR 复现，真机正常。
3. 百度坐标需转 GCJ-02（share 文本解析已实现自动转换）。
4. 部分车机兼容性问题。
5. 车速环超速红环依赖测速摄像头限速数据，模拟器测试路线无测速点不触发。
6. 模拟器冷启动定位初始化早于授权会缓存"无权限"，需授权后 force-stop 重启一次。
7. 键盘展开时 TBT 底部面板点击被遮挡（模拟器明显，真机影响小）。
8. 巡航引擎定位在模拟器被海外权益拦截 → 速度/统计改由系统 GPS 驱动（真机电子眼回调
   有效）。真机巡航 v11 仲裁（`CruiseSession`）：引擎流 3s 内活跃则系统 GPS 让位；
   真机速度单源系统 GPS（EMULATOR 用 NaviInfo）；network 粗定位 15s 内被精定位忽略。
9. 模拟器默认位置在海外，首个 GPS fix 大跳变，距离统计已加 >5km 过滤。
10. 自绘箭头：SDK 定位图标不消费注入 bearing → 透明化 SDK 图标 + Marker.rotateAngle
    + 自控相机。`rotateAngle = heading − 2×cameraBearing`（与官方文档相反，勿按文档写减法）。
11. **模拟器 EMULATOR 无法到达**：终点前最后机动停滞循环，`onArriveDestination` 永不
    触发（卡 25~30m）。到达/巡航回归走真机实跑，或 `amapilot_force_gps_navi 1` +
    `adb emu geo fix` 沿路线注入（勿瞬移，300m/10s 跳变滤波；重锚 ≤250m 逐跳桥接）。

## 构建环境（本机）

> 换机器搭建模拟器 + DHU 全链路照 `docs/SIM_ENV_SETUP.md`（keystore/key 迁移、AVD、
> AA 17.9 priv-app、特权白名单、faketime、连接链、故障速查）。AA17 特权白名单副本
> 已入库：`scripts/privapp-permissions-androidauto-aa17.xml`。

- JDK 17：本机为 conda 环境 `~/anaconda3/envs/amapilot-dev/lib/jvm`（构建前
  `export JAVA_HOME=$HOME/anaconda3/envs/amapilot-dev/lib/jvm`；换机先 `ls` 确认）。
- Android SDK：`~/android-sdk`（`local.properties` 的 `sdk.dir`）。`local.properties`
  （gitignored）需含 `sdk.dir` 与 `AMAP_API_KEY`。
- Gradle 8.13：services.gradle.org 直连不可用 → 腾讯镜像 zip 手动种入 wrapper 缓存
  （`~/.gradle/wrapper/dists/gradle-8.13-bin/…`：zip + 解包 + `touch …-bin.zip.ok`）；
  未改 git 内 wrapper properties，CI 不受影响。
- 无 `unzip`/免密 sudo → `~/.local/bin/unzip` 是 python3 zipfile 垫片；非交互 shell
  需 `export PATH=$HOME/.local/bin:$PATH`。
- 联网走 clash 代理（`~/wsl_clash_proxy.sh` 导出 `host_ip:7890`）；git over SSH 需
  `GIT_SSH_COMMAND="ssh -o ProxyCommand='nc -X connect -x ${host_ip}:7890 %h %p'"`；
  Gradle JVM 不读 http_proxy → 用户级 `~/.gradle/gradle.properties` 写
  `systemProp.http(s).proxyHost/Port`（host_ip 重启会变）；依赖走
  `~/.gradle/init.d/mirrors.gradle` 阿里云镜像。
- 本机 WSL2（Ubuntu 24.04，8 vCPU/7G，KVM 可用，用户已在 kvm 组）；日志/截图统一用 `~/tmp`。
- 截图验证：主模型无多模态 → 委托 `vision` subagent（`~/.config/opencode/agents/vision.md`）；
  兜底 = 纯 Python PNG 探针 `scripts/dhu-shot-inspect.py`（zlib 手解：ASCII 粗览/
  --region 放大/--clusters 亮字簇）；uiautomator XML 仅 Android 侧可用。
- DHU 宿主依赖：`libc++1-18 libc++abi1-18 libunwind-18`。
- pip：系统 python 无 venv/ensurepip，用 tuna pip wheel 自举。

### 模拟器

```bash
# x86_64 AVD，Google ARM 转译跑 arm64 应用
sg kvm -c "$HOME/android-sdk/emulator/emulator -avd amapilot_test \
  -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim"
```

- adb：`export PATH=$HOME/android-sdk/platform-tools:$PATH`
- 模拟器 `/tmp` 与宿主机无关；宿主机 `/tmp` 重启即清空
- 冷启动可能 SystemUI ANR：`uiautomator dump` 后点 "Wait"
- 注入 GPS：`adb emu geo fix <lng> <lat>`

### ABI 说明（重要 workaround，勿移除）

x86_64 模拟器上 androidx 自带 `lib/x86_64/libandroidx.graphics.path.so`，PM 会选 x86_64
ABI 而不提取高德 ARM-only 库 → 地图渲染 `UnsatisfiedLinkError`。解决（已内置）：
- `app/build.gradle.kts`：`ndk { abiFilters += "arm64-v8a" }` + `packaging.jniLibs.useLegacyPackaging = true`
- `AndroidManifest.xml`：`android:extractNativeLibs="true"`

### UI 验证方式

- 首选 `vision` subagent（只读）：委托它 `read` 截图文件，输出转录/布局/颜色/状态。
- 兜底：`scripts/dhu-shot-inspect.py`；Android 侧 `uiautomator dump`。

## 应用图标（已定稿）

方案 B「双路汇聚 Pin」（AA 双道路 + 目的地 Pin）：

- `drawable/ic_launcher_foreground.xml`（108 viewport）：双路 stroke `M39,82 L52,59`、
  `M69,82 L56,59`（宽 7、round、白）；Pin 头圆 (54,41) r12 + 尾三角 (45,47)(63,47)(54,59)
  + 圆孔 r4.5。**nonZero 环绕规则**：头圆与尾三角同为顺时针（重叠区并集填充），圆孔逆时针
  （镂空透渐变）。**勿改回 evenOdd**（重叠区被 XOR 镂空 = Pin 头悬空白块）。
- `drawable/ic_launcher_background.xml`：45° 渐变 `#4EA0FF → #173DA6`；
  adaptive icon（`mipmap-anydpi-v26/`，minSdk 26 无需 legacy PNG）。

## Android Auto DHU 调试（本机已跑通）

模拟器 + DHU 2.0 直连调试车机 UI（绕过 AA launcher 侧载限制）。一键脚本
`scripts/dhu-connect.sh`，时偏移 preload 源码 `scripts/dhu-faketime.c`。从零搭建见
`docs/SIM_ENV_SETUP.md`。

### 环境事实

- **DHU 2.0**（`extras;google;auto`，2022-03）是官方最新版，无更新。
- 模拟器 google_apis 镜像自带 AA **stub 只是 Play 跳转壳**（无 head unit server）。
- 完整版 **AA 17.9**（APKMirror `.apkm`）以 **priv-app** 装入 `/product/priv-app/AndroidAuto/`
  （`lib/arm64-v8a/` 必须完整 ABI 名，`lib/arm64` 不被 PM 识别 = 救砖手册见
  `docs/SIM_ENV_SETUP.md` §4.7）；**必须补特权权限白名单**（否则 system_server boot loop）。
- **AAOS 镜像不可行**（android-33 automotive 无 ARM 转译，高德 arm64-only 跑不了）。

### 每次连接链（脚本自动做）

1. `cmd role add-role-holder android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION`（模拟器重启即丢）
2. `pm enable` FirstActivityImpl / VnLaunchPadActivity（manifest 默认禁用）
3. 补运行时权限 + 通知监听器（`cmd notification allow_listener`，注意 `$` 转义）
4. force-stop 后重拉 `DeveloperHeadUnitNetworkService`（**失败会话后服务楔死**）；
   **netstat 验证 5277 起来再继续**
5. `adb forward tcp:5277`（重启即丢）
6. AA 设置页置前台（Android 14 BAL 豁免：首连要求 AA 有可见窗口）
7. 启 DHU（见下）

### DHU 两个致命坑（缺一即静默退出）

- **stdin EOF 秒退**：Console 线程读到 EOF 就 `requestQuit()` → `sleep 100000 | ./desktop-head-unit -a`。
- **GAL 证书过期**：手机端开发证书 2023-03-22 到期 → `LD_PRELOAD` `build/dhu-faketime.so`
  把进程时间平移回 2022（绝对值回退、增量不变）。
- **agent 环境后台进程组会被回收**：fifo writer 与 DHU 都要 `setsid` 包装。

### 负载与断连

全链路（模拟器 + 双地图引擎 + AA + DHU）负载高会断连：Ping timeout 风暴 → DHU 退出；
system_server 被压死（`DeadSystemException`）；gearhead 被杀（X connection broken）。
判据：`adb shell cat /proc/loadavg` 持续 < 10 才动手，< 15 才重建。1 vCPU 机器跑不动
（见 `docs/SIM_ENV_SETUP.md`），需 ≥4 vCPU。断连后重建整链（role/pm 状态保留，adb forward
与 server 需重做）。

### 车机模板约束（androidx.car.app 1.7.0 宿主实证）

- **AA18 release 包必须带 `createHostValidator().addAllowedHosts(R.array.hosts_allowlist_sample)`**：
  AA18 删除了 `TEMPLATE_RENDERER` 权限声明（空白名单唯一放行闸门），缺白名单 →
  `Unrecognized host` 拒绑 → 车机屏 "unexpected error"（debug 包走 ALLOW_ALL 故长期未暴露）。
- **冷启动 app→host dispatch 永久死 = 宿主双 bind**：宿主连发两次 `onAppCreate`，第二次
  `resetHosts()` 清掉已注册的 IAppHost → 搜索/设置/选路等模板屏全哑（地图看似正常）。
  自愈：`auto/HostDispatchHealer.kt`（+8s/+20s 探针 `getHost("app")` 为 null 即回写）。
- `PlaceListMapTemplate` 非 browsable 行必须带 `DistanceSpan`（`setBrowsable(true)`；
  Place marker metadata 不豁免，否则崩溃）。
- 地图类模板必须声明 `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES"/>`。
- 车辆昼夜 = 宿主 Configuration uiMode night（`CarContext.isDarkMode()` +
  `onCarConfigurationChanged`）；底图解析 = 设置覆盖 > 车辆态 > 手机 uiMode；DHU 控制台
  `day`/`night`/`daynight` 可作回归。

### 表面手势与模板派发

- **裸 NavigationTemplate 派发 `SurfaceCallback.onClick` 与 `onScroll`**（坐标与车机
  窗口 1:1）→ 自绘按钮真实可点、地图可拖。**MapWithContentTemplate 不派发**（曾致
  "昨天能拖今天不能"）。**三态统一裸 NavigationTemplate**，交互走自绘 + handleClick/
  handleScroll；宿主只留顶栏搜索钮（空 ActionStrip 抛异常 → 模板失败 → 不派发）。
- 表面 y>400（VD 高度）不落表面；宿主图标区优先于表面派发。
- `CarIcon.PAN` 宿主无响应（不触发 PanModeListener），勿放。
- 高负载时宿主回收 Surface（VD 消失、点击全哑）→ 重建整链。
- `onVisibleAreaChanged` 三态跳变且 ≠ 实体 chrome：扣除标称 padding(12) 后**只吃超出
  部分**；顶部按阈值（≤48 让开，>48 = 透明图标条忽略）；底部一律忽略；副窗口态
  （报告 ~22,34-464,376）还必须把取景中心挪进可见区（`setPointToCenter` 逐帧插值）+
  右竖排左滑。一刀切两方向都被用户否决过。
- ActionStrip 单动作右对齐 (756,42)；多动作 528/604/680/756 等距。DHU tap 偶发 miss，
  重试点位确认。
- 假阴性教训：下结论前 ①给动作入口层加日志 ②注意屏态污染（push 新屏后同坐标 tap
  静默）③重复 tap 排除 miss ④AA17 夜间主题调暗图标（亮度检测失灵，用功能探测）。

### 旋钮/焦点

- **DHU 必须 `-i hybrid`（或 rotary）启动**才开 dpad 通道（默认 touch 模式命令静默
  丢弃 = "零反应"误判根因）。`dpad rotate` = 域内焦点环；`dpad up/down` = 推杆跨域
  （dock ⇄ app 模板域）；`dpad click` = 激活。
- 旋钮可达集合 = 顶栏功能钮 + 右竖排三钮 + 宿主列表行（搜索/选路屏）；
  >4 选项走宿主列表容器（无上限可滚动）。
- 按键事件不达自绘窗口（Presentation.dispatchKeyEvent 无探针）；**交互设计必须给
  宿主控件留等价入口**。
- 宿主顶栏空闲自动隐藏，首次触摸仅唤醒（自动化 tap 双发；旋钮链连发 ≤1s）。

### 真机 + DHU

- Play 版 AA（实测 18.0）无需 root：AA app ⋮ →「Start head unit server」→
  `adb forward tcp:5277` + DHU **不需要 faketime**（证书有效）。手机先导航再连 DHU =
  真机续导验证。真机链路 AA launcher 能看到侧载 app（与模拟器 AA17 validator 不同）。
- **DHU 2.0 链路引擎覆盖物整层不渲染**（DHU 是 2022 调试工具）：底图/引擎内部路况/
  Compose 叠加层正常，app 添加的 polyline 不显示 → **勿为 DHU 把导航路线迁移 Compose
  自绘**；实车 AA18 路线渲染正常。该链路验证覆盖物类视觉以日志/手机端对照。
- 真机位置注入不可行（AMap 融合定位被 Wi-Fi/基站拽回；关 Wi-Fi 断无线 adb）→
  到达/巡航回归走模拟器 `emu geo fix` 或真机实跑。
- 真机导航中 `selectRouteId` 恒 false；UI 不跟随拒绝（见 NaviSession.selectRoute）。

### DHU 控制台

- 命令表：`dpad up/down/left/right/ur/ul/dr/dl/click/back/soft left/soft right/0-9/*/#/+`、
  `dpad rotate left/right`、`dpad flick left/right`；`keycode home/back/call/endcall/
  search/media*/navigation/tel`；`focus audio/nav/video`；`speed/accel/compass/gyro/
  location/odometer`；`day/night/daynight`；`restrict none/all`；`mic begin/play/
  repeat/reject`；`screenshot <file>`；`tap x y`；`touch`；`sleep`；`quit/exit/help/licenses`。
- **`screenshot <file>`** 直接输出 800x480 PNG（配合 PNG 探针可自主验证车机 UI）。
- **stdout 块缓冲**：重定向到文件看不到回显 → `script -qfc "..." /dev/null < fifo > log`
  伪终端包装（行缓冲 + `[E]:` 可见）。
- 投影输入楔死（所有 tap 静默但 TLS/VD 活着）→ 重建整链；规则：back 只在已知 pushed
  屏点一次，点后等 ≥8s。
- AA17 车机屏 y400-480 = 宿主底部 dock（非应用区）；应用 Surface = y0-400（VD 800x400）。
- 地图右下缩放胶囊/定位钮/指南针/比例尺 = **高德 MapView 自带控件**（非 AA）：`uiSettings`
  里 `isZoomControlsEnabled/isMyLocationButtonEnabled/isCompassEnabled/isScaleControlsEnabled
  = false` 关闭；高德 logo 保留（合规）。

### 排查工具链

- 线路抓包：python TCP 代理夹在 adb forward 与 DHU 之间（5277→5278）hex 落盘。
- DHU gdb：二进制带完整符号。`catch syscall exit_group` + `SdlApp::galError`/`SDL_PushEvent`
  断点定位退出路径。
- ANR 区分：宿主 `CarApp.H.Tem` 报 `ANR_API: BIND` = app 冷启动没赶上 5s 时限（先启动
  MainActivity 预热进程再点车机图标）。

## 车机搜索与导航发起

- **搜索 = 宿主 SearchTemplate**（`CarSearchScreen`）：宿主渲染搜索栏+车机键盘+
  结果列表；查询走 SearchCallback 防抖的 AMap PoiSearch；手机 `CarSearchRelay` 退化
  为辅助通道（结果同步进搜索屏）。**宿主契约三约束**：headerAction 仅标准动作
  （`Action.BACK`）；`setLoading(true)` 与 `setItemList` 互斥；`onGetTemplate` 不得外抛
  （违者崩溃 + 宿主错误屏）。
- **车机端发起导航必须经 `CarRouteStarter`**（先算路再 start，否则无路线数据 = 卡片
  0m/0min）。起点取巡航/导航会话位置，退回系统 GPS 最近定位。重导航先 `stop()` 再算路
  （导航中引擎吞掉算路回调 → 协程挂死），15s 超时兜底。
- **预导航选路**：算路期间 pill「算路中…」；多路线 push 选路屏，行点击 `commitPreNav`
  以该路线 start（`selectRouteId` 仅导航前生效，先 start 再切会被拒绝）。单路线直接 start。
- **选路栈语义**：push 后预导航栈 = Navigation→Search→RouteSelect（back = 回地点选择页）；
  导航中 = Navigation→RouteSelect（back = 回地图）。提交 = `exitRouteSelect` + pop；
  back 退出 = 屏 lifecycle onDestroy 收选路图形态。
- **宿主 ✕ / 终止导航 = `onStopNavigation`**：app 先 `navigationStarted()`，否则库内闸门
  不放行；停止时对称 `navigationEnded()`（ticker 收集 `NaviSession.isActive` 同步）。
- **手机辅助态 = 会话级**（`CarMapControl.carConnected`：onCreateScreen 置位 / ON_STOP 复位 /
  ON_START 恢复 / ON_DESTROY 双保险）。宿主 push 搜索屏会回收地图 Surface；**车机断开 ≠
  会话销毁**（只派 ON_PAUSE/ON_STOP 并保留会话等重连，挂 destroy 链 = 真车断开后卡死）。
- **idle 模板必须是 NavigationTemplate**（宿主按首个模板判定是否派发 Surface；idle 用
  ListTemplate = 概率性不派发）。
- **宿主派发空 Surface（宽高 0/ null）后不再重派**：`onSurfaceAvailable` null 时**不可**
  置 `surfaceReceived=true`（会停掉 10s 重注册）→ 保持 false 逼宿主销毁重建（防僵尸黑屏）。
- **Presentation 离屏只 onPause 不 onDestroy**（共享 MapView 单例，dismiss 时 destroy
  会让下一个 Presentation 拿死引擎 = 黑屏）；新 Presentation 须同步 rendering 暂停态。

## 车机地图自绘（VirtualDisplay 方案）

车机屏用高德自绘替代宿主 Google 地图（`auto/CarMapDisplay.kt` + `auto/CarMapUi.kt`，
设计规范 `docs/CAR_UI_DESIGN.md`）：

```
宿主 SurfaceContainer.surface
  └─ createVirtualDisplay(该 Surface)          // 合成目标 = 车机屏
       └─ CarMapPresentation (Presentation)     // 外部显示官方通道，无需权限
            ├─ MapView (GLSurfaceView)          // 高德夜间样式
            └─ ComposeView 叠加层                // 转向卡 + 信息卡 + 操作行
```

- **持久化地图**：VD/Presentation/引擎为 companion 级单例，Surface 热换用
  `VirtualDisplay.setSurface`（毫秒级）；`stop()` = detach 不销毁，`destroy()` 仅进程级。
- **回调在 binder/主线程**：`onSurfaceAvailable/Destroyed` 内只做毫秒级投递，重活甩后台+
  延迟（8s），否则宿主 5s 回调超时 → 收回 Surface。
- **`setSurfaceCallback` 重注册会让宿主销毁重建 Surface** → 只在"尚未拿到 Surface"时按
  10s 间隔重试，拿到后停手。
- **模板态门槛**：只有 NavigationTemplate 才派发 Surface → 巡航必须已激活（GPS 有定位）。
- 转译模拟器引擎初始化 4~6s 可能超 5s 时限（首会话触发一轮 ANR 后已载入，真机 <1s）。
- **放弃的路线**：TextureView + SurfaceTexture + GL blit（attach/detach 被框架拒绝）；
  Activity + `setLaunchDisplayId`（需 `INTERNAL_SYSTEM_WINDOW` 签名权限）。

## 参考文档

- `docs/CAR_UI_DESIGN.md` — 车机 UI 规格（布局/坐标/路线渲染/相机/车道）
- `docs/SIM_ENV_SETUP.md` — 模拟器 + DHU 环境搭建与迁移清单
- `docs/PLAY_UPLOAD_GUIDE.md` — Play 内部测试轨道上传手册
- `docs/OPEN_SOURCE_COMPLIANCE.md` — 开源合规盘点（GPL × 高德协议）

外部：[高德 Android SDK](https://lbs.amap.com/api/android-sdk/overview) ·
[Android Auto](https://developer.android.com/training/cars/apps)
