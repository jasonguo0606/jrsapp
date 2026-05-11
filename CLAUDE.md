# JRS 篮球直播 Android App

## 项目概述

个人学习项目。解析 [m.jrskk.com](https://m.jrskk.com) 的静态 HTML，以原生 Android 方式展示 NBA/CBA 比赛列表，并通过 WebView 播放直播。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3（暗色主题） |
| 架构 | MVVM（ViewModel + StateFlow） |
| 网络 | OkHttp 4.12 |
| HTML 解析 | Jsoup 1.18 + 正则（双重策略） |
| 图片加载 | Coil 2.7 |
| 视频播放 | WebView（内嵌直播页） |
| 构建 | Gradle 8.7 + AGP 8.4 + Kotlin 2.0 |
| 镜像 | 阿里云 Maven + 腾讯云 Gradle |

---

## 项目结构

```
app/src/main/java/com/jrsapp/
├── MainActivity.kt               # 入口，启用 EdgeToEdge
├── navigation/
│   └── AppNavGraph.kt            # 路由：列表页 ↔ 播放页（状态驱动，无 NavController）
├── data/
│   ├── model/
│   │   └── Match.kt              # 数据模型：Match + StreamLink
│   ├── parser/
│   │   └── MatchParser.kt        # HTML 解析器（见下方核心解析说明）
│   └── repository/
│       └── MatchRepository.kt    # 网络层，OkHttp + SSL 信任全部证书
└── ui/
    ├── screen/
    │   ├── MatchListScreen.kt    # 比赛列表页（Compose）
    │   ├── MatchListViewModel.kt # 状态管理 + NBA/CBA 过滤逻辑
    │   └── PlayerScreen.kt       # WebView 播放页 + 线路切换
    └── theme/
        └── Theme.kt              # 暗色主题配色（深蓝 + 橙色）
```

---

## 核心解析逻辑

### 真实 HTML 结构（m.jrskk.com 静态 HTML）

```html
<!-- 每场比赛的容器，带 data-lid 属性（JS 动态添加，不可靠） -->
<元素 data-lid="4481077,1,4481077" data-stype="zqlq">
  <li class="lab_events">
    <span class="name">CBA</span>          <!-- 联赛名 -->
  </li>
  <li class="lab_time">05-09 19:35</li>   <!-- 比赛时间 -->
  <li class="lab_team_home">
    <strong class="name">山东</strong>     <!-- 主队名 -->
    <span class="avatar"><img src="...">  <!-- 主队队徽 -->
  </li>
  <li class="lab_vs">...</li>             <!-- 比分 -->
  <li class="lab_team_away">
    <strong class="name">上海</strong>    <!-- 客队名 -->
    <span class="avatar"><img src="..."> <!-- 客队队徽 -->
  </li>
  <li class="lab_channel">
    <!-- 每场有 3 条线路，过滤掉 javascript:void(0) -->
    <a href="http://m.sportsteam586.com/play/steam814469.html">直播①</a>
    <a href="http://m.jw1104.com/play/steam814469.html">直播②</a>
    <a href="http://m.sportsteam53.com/play/steam814469.html">直播③</a>
  </li>
</元素>
```

### 解析器选择器

```kotlin
// 容器定位
doc.select("[data-lid]:has(.lab_channel)")

// 字段提取
league   = ".lab_events span.name"
time     = ".lab_time"
homeTeam = ".lab_team_home strong.name"
awayTeam = ".lab_team_away strong.name"
homeLogo = ".lab_team_home .avatar img"
awayLogo = ".lab_team_away .avatar img"
streams  = ".lab_channel a[href*=steam]" // 过滤 href.startsWith("http")
```

### 踩坑记录

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| HTML 乱码 | 手动设置 `Accept-Encoding: gzip` 导致 OkHttp 不自动解压 | 移除该 header，OkHttp 自动处理 |
| `li.match-item` 选择 0 个 | `class="match-item"` 由 JS 动态添加，静态 HTML 无此 class | 改用 `[data-lid]` |
| `data-steam` 正则匹配 0 个 | `data-steam` 属性同样由 JS 动态添加 | 改用 Jsoup 选择器 |
| WebFetch 结构与实际不符 | WebFetch 执行了 JS，看到的是渲染后 DOM | 以 OkHttp 实际收到的 HTML 为准 |
| Jsoup 嵌套 `<a>` DOM 重构 | `<a class="match-link">` 内嵌 `<a>` 导致 Jsoup 重排 DOM | 旧版问题，新版 HTML 结构已无此嵌套 |
| SSL 握手失败 | 网站证书不规范 | `X509TrustManager` 信任全部（仅限学习用） |
| Gradle 依赖下载失败 | `dl.google.com` 国内访问受限 | 阿里云 Maven 镜像 + 腾讯云 Gradle 镜像 |

---

## 联赛筛选

`MatchListViewModel` 在内存中过滤，切换 Tab 不重新请求网络：

```
全部篮球  →  isNba || isCba || contains("篮球")
NBA      →  contains("NBA", ignoreCase)
CBA      →  contains("CBA", ignoreCase)
```

---

## 待开发功能（后续方向）

- [ ] **横屏播放**：PlayerScreen 进入播放后自动横屏，退出恢复竖屏
- [ ] **ExoPlayer 接入**：解析播放页内的 m3u8/flv 流地址，替换 WebView 播放器
- [ ] **比赛详情页**：比分实时刷新（定时轮询）、赛事信息
- [ ] **收藏/订阅**：本地持久化关注的球队，比赛开始前推送通知
- [ ] **其他联赛**：NBA / 足球 / 其他运动分类支持
- [ ] **下拉刷新**：列表页加 PullRefresh
- [ ] **错误重试优化**：网络异常时的 loading/error/empty 三态更细化
- [ ] **混淆配置**：release 包加 ProGuard 规则

---

## 开发环境

- Android Studio Ladybug+
- compileSdk 35 / minSdk 26
- Gradle 8.7 / AGP 8.4.0 / Kotlin 2.0.0
- 国内网络需使用镜像（已配置，见 `settings.gradle.kts`）

## 仓库

https://github.com/jasonguo0606/jrsapp
