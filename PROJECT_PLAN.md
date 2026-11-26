# Android 导航应用开发计划

> **本文档为项目初期规划，仅作历史参考。** 当前实际架构见 `AGENTS.md`，功能与版本见 `CHANGELOG.md`。
> 分发策略已调整：AA 车机端需经 Play 内部测试轨道（见 `docs/PLAY_UPLOAD_GUIDE.md`），GitHub Releases 仅作手机端侧载渠道。

## 一、项目概述

开发一个Android导航应用，支持：
- 高德/百度地图集成
- 实时导航功能
- 巡航模式（无起终点的轻量级导航）
- Android Auto适配（Side Load安装）
- 接收第三方应用分享的位置信息

**分发方式**：仅GitHub开源，不上架Google Play，通过Side Load安装

---

## 二、技术选型

| 维度 | 高德地图（推荐） | 百度地图 |
|------|------------------|----------|
| 导航SDK | 专业导航SDK，功能完整 | 导航SDK，功能完整 |
| 巡航功能 | 支持智能巡航 | 支持智能巡航 |
| 文档质量 | 完善 | 一般 |
| 坐标系 | GCJ-02 | BD09LL |
| 费用 | 非商业免费，商用需授权 | 非商业免费，商用需授权 |

**结论**：推荐使用高德地图

---

## 三、开发步骤

### 第一阶段：基础地图集成（2-3周）

1. 申请高德地图API Key
   - 注册高德开放平台账号
   - 创建应用，获取Android SDK密钥
   - 需要提供SHA1和包名

2. 创建Android项目
   - 使用Android Studio
   - 配置Gradle依赖
   - 最低支持API 26（Android 8.0）

3. 集成地图SDK
   ```gradle
   dependencies {
       implementation 'com.amap.api:3dmap-location-search:latest.integration'
   }
   ```

4. 实现基础功能
   - 地图显示
   - 定位功能
   - POI搜索
   - 搜索历史（本地存储）

5. 隐私合规与权限引导
   - 高德隐私协议弹窗（首次启动，同意后调用 `MapsInitializer.updatePrivacyAgree`，否则地图不加载）
   - 位置权限被拒后的引导页（说明用途 + 跳转系统设置）

### 第二阶段：导航功能（3-4周）

1. 集成导航SDK
   ```gradle
   dependencies {
       implementation 'com.amap.api:navi:latest.integration'
   }
   ```

2. 路线规划
   - 驾车路线规划
   - 步行路线规划
   - 途经点规划

3. 实时导航
   - 集成导航View
   - TBT（Turn-by-Turn）导航
   - 语音播报

4. 巡航模式
   - 无起终点导航
   - 电子眼播报
   - 拥堵信息提示
   - 保活与电池优化引导（引导用户加入电池白名单，防止后台被杀）

5. 收藏与常用地点
   - 收藏地点管理（增删改）
   - "回家/去公司"一键导航
   - 数据层设计需支持同步到车机端

6. 沿途搜索
   - 加油站/充电桩/洗手间 along-route 搜索
   - 搜索结果可设为途经点

7. 设置页
   - 导航偏好（躲避拥堵/避免收费/高速优先）
   - 语音播报开关
   - 昼夜模式切换（跟随系统/手动）

### 第三阶段：Android Auto适配（3-5周）

1. 集成Car App Library
   ```gradle
   dependencies {
       implementation 'androidx.car.app:app:1.4.0'
   }
   ```

2. 实现CarAppService
   - 声明导航类别
   - 实现Session
   - 处理导航模板

3. Side Load安装适配
   - 配置Manifest
   - 支持开发者模式安装

4. 车机体验适配
   - 昼夜模式（白天/黑夜模板自动切换）
   - 收藏地点车机端展示（PlaceListTemplate）
   - 车机端语音搜索适配

### 第四阶段：分享功能（1-2周）

1. 实现Intent Filter
2. 解析各平台分享格式
3. 坐标转换处理

### 第五阶段：测试发布（1-2周）

1. 功能测试
2. APK签名
3. GitHub Release发布

---

## 四、Android Auto Side Load安装

> ⚠️ **已被实验证伪**：AA 17.x 启动器拒绝所有侧载的 Template 类应用（含开启"未知来源"），仅 Play 安装可用。本节仅存档。

### 4.1 开发者模式开启

1. 打开Android Auto应用
2. 点击右上角设置
3. 连续点击"版本"10次
4. 启用"开发者选项" → "未知来源应用"

### 4.2 Manifest配置

```xml
<service
    android:name=".auto.NavigationCarAppService"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService"/>
        <category android:name="androidx.car.app.category.NAVIGATION"/>
    </intent-filter>
</service>
```

### 4.3 用户安装流程

1. 从GitHub Releases下载APK
2. 允许"未知来源"安装
3. 安装并授予权限
4. 开启Android Auto开发者模式
5. 连接车机使用

---

## 五、分享功能接口

### 5.1 支持的分享格式

| 来源 | Scheme | URI格式 |
|------|--------|---------|
| 标准格式 | `geo:` | `geo:lat,lng?q=地名` |
| 高德地图 | `androidamap://` | `androidamap://poi?lat=x&lon=y&poiname=名` |
| 百度地图 | `baidumap://` | `baidumap://map/location?location=lat,lng` |

### 5.2 Manifest配置

```xml
<activity android:name=".ShareReceiveActivity" android:exported="true">
    <!-- 标准geo格式 -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="geo"/>
    </intent-filter>
    
    <!-- 高德地图 -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="androidamap"/>
    </intent-filter>
    
    <!-- 百度地图 -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="baidumap"/>
    </intent-filter>
    
    <!-- 分享文本 -->
    <intent-filter>
        <action android:name="android.intent.action.SEND"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:mimeType="text/plain"/>
    </intent-filter>
</activity>
```

### 5.3 坐标转换

百度坐标(BD09) → GCJ-02 → WGS-84

高德使用GCJ-02，无需转换

---

## 六、开源项目配置

### 6.1 许可证

推荐：GNU General Public License v3.0 (GPLv3)

### 6.2 项目结构

```
├── README.md              # 项目说明
├── LICENSE                # GPLv3许可证
├── CONTRIBUTING.md        # 贡献指南
├── CHANGELOG.md           # 版本日志
├── INSTALL_GUIDE.md       # 安装指南
├── docs/
│   ├── android-auto.md    # Android Auto指南
│   └── api-keys.md        # API申请说明
└── .github/
    ├── ISSUE_TEMPLATE/
    └── releases/
```

### 6.3 API Key管理

```properties
# local.properties（不提交到Git）
AMAP_API_KEY=your_key_here
```

---

## 七、时间估算

| 阶段 | 时间 | 依赖 |
|------|------|------|
| 基础地图 | 2-3周 | 无 |
| 导航功能 | 5-6周 | 基础地图 |
| Android Auto | 3-5周 | 导航功能 |
| 分享功能 | 1-2周 | 基础地图 |
| 测试发布 | 1-2周 | 所有功能 |
| **总计** | **12-18周** | - |

---

## 八、关键文件清单

- [ ] `AndroidManifest.xml` - 权限和Intent Filter配置
- [ ] `build.gradle` - 依赖配置
- [ ] `NavigationCarAppService.kt` - Android Auto服务
- [ ] `ShareReceiveActivity.kt` - 分享接收处理
- [ ] `CoordUtil.kt` - 坐标转换工具
- [ ] `PrivacyPolicyDialog.kt` - 高德隐私合规弹窗
- [ ] `FavoriteRepository.kt` - 收藏与常用地点存储
- [ ] `SettingsScreen.kt` - 设置页
- [ ] `README.md` - 项目文档
- [ ] `INSTALL_GUIDE.md` - 安装指南

---

## 九、注意事项

1. **API Key安全**：不要提交到Git，使用`local.properties`
2. **坐标系**：注意GCJ-02与BD09的转换
3. **权限**：运行时动态申请位置权限
4. **Android Auto**：需要开启开发者模式才能侧载
5. **版本管理**：使用语义化版本号（v1.0.0）

---

## 十、GitHub参考项目

### 10.1 最相关项目

| 项目 | 特点 | 参考价值 | 链接 |
|------|------|----------|------|
| **navipilot** | 多源导航集成，支持高德SDK | ⭐⭐⭐⭐⭐ | [github.com/jixiexiaoge/navipilot](https://github.com/jixiexiaoge/navipilot) |
| **AMap Companion** | 高德车机版广播数据悬浮窗 | ⭐⭐⭐⭐ | [github.com/zuo-qirun/amap-companion](https://github.com/zuo-qirun/amap-companion) |
| **JustLikeDidiNavi** | 仿滴滴司机端导航 | ⭐⭐⭐⭐ | [github.com/leijiaming/JustLikeDidiNavi](https://github.com/leijiaming/JustLikeDidiNavi) |

### 10.2 高德导航功能参考

| 项目 | 功能 | 参考价值 | 链接 |
|------|------|----------|------|
| **android-navi-quick-start** | 高德官方导航示例 | ⭐⭐⭐⭐ | [github.com/amap-demo/android-navi-quick-start](https://github.com/amap-demo/android-navi-quick-start) |
| **android-multiroute-display** | 多路径规划显示 | ⭐⭐⭐ | [github.com/amap-demo/android-navi-multiroute-display](https://github.com/amap-demo/android-navi-multiroute-display) |
| **BJNaviDemo** | 自定义导航（外地车牌避让） | ⭐⭐⭐ | [github.com/walkingsky/BJNaviDemo](https://github.com/walkingsky/BJNaviDemo) |

### 10.3 综合地图应用

| 项目 | 特点 | 参考价值 | 链接 |
|------|------|----------|------|
| **FMap（宇行地图）** | 高仿高德地图，功能完整 | ⭐⭐⭐⭐ | [github.com/jiyouliang/FMap](https://github.com/jiyouliang/FMap) |
| **TnboysMap** | 定位+搜索+导航完整App | ⭐⭐⭐ | [github.com/jaychunkid/TnboysMap](https://github.com/jaychunkid/TnboysMap) |
| **swan（鸿鹄导航）** | 完整导航+离线地图 | ⭐⭐⭐ | [github.com/entropyfeng/swan](https://github.com/entropyfeng/swan) |

### 10.4 重点推荐：navipilot

该项目与本项目需求最接近：

**支持的导航源**：
- 高德车机版（广播接收）
- 高德手机SDK（AMapNaviView）
- Google Navigation SDK
- 腾讯导航SDK

**核心功能**：
- 多源导航集成
- 实时路况
- TBT导航
- 语音播报
- 投射模式

**参考价值**：
- 如何集成高德导航SDK
- 如何处理导航数据
- 多源导航架构设计
- Android Auto适配思路

---

## 十一、暂缓功能（Backlog）

以下功能已识别但暂不实施，视后续需求和用户反馈排期：

1. **离线地图**：高德离线导航包下载与管理，工作量大
2. **轨迹记录**：行驶轨迹记录与回放
3. **HUD模式**：挡风玻璃投射显示
