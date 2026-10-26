# 每日一言 / DailyLine

应用名随系统语言变化：简体中文（`values-zh-rCN`）下显示 **每日一言**，其他语言回退到 `DailyLine`。其余界面文案目前仍是中文硬编码。

一个 Java Android 主屏幕哲学句子小组件。项目包含 100+ 条来自外国哲学作品的条目，并区分 `原文` 与 `译写 / 意译`。意译条目不会被伪装成逐字引文；部分中文原文条目保留常见英文译文，便于核对作品出处。

版本：分类禁用与 12 分类并入管理那一版已归档为 **3.10**，玻璃材质为 **3.11**，回桌面换句为 **3.12**；3.13 把自定义布局升级为可重叠的自由排版；3.14 让毛玻璃真的模糊壁纸、液态玻璃有清楚的玻璃边；本版 **4.0.0（PreRelease）** 新增「自定义背景」（相册选图 / GIF 首帧，可选高斯模糊与强度）和玻璃高级设置，并把图片选择器换成系统相册选择器。

### 玻璃为什么「没生效」，3.14 怎么修的

小组件拿不到身后的像素：`RemoteViews` 没有 `RenderEffect` / `RuntimeShader`，launcher 也不会把壁纸交出来。3.13 把玻璃简化成「透明底 + 一层染色」之后，两种材质的差别只剩 alpha——桌面上看就是一块白纱，正是「没生效」。

3.14 按 FolkPatch 的做法把「背景」自己造出来：

- **毛玻璃**：`WallpaperManager` 读真实壁纸 → 按小组件比例中心裁剪 → 缩到 760px → 两遍盒式模糊（≈高斯）→ 逐像素透镜（SDF 折射 / 色散 / 提饱和 / 只提暗部的自适应薄雾 / rim line / 定向高光）→ 输出一张真正模糊过的玻璃位图。
- **液态玻璃**：半透明透镜——读到壁纸时只做轻模糊（保留壁纸结构），再叠长距离边缘折射、色散和高光，透明度约 68%，桌面真实壁纸仍从下面透上来参与叠色；读不到壁纸时退回纯透明光学层（不模糊）。
- Android 14 起只有「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`) 拿得到壁纸像素，所以「个性」页多了一行状态和一个「去授权读取壁纸」按钮；授权后预览与桌面立刻重画。不授权时毛玻璃退回壁纸取色（不模糊），预览里会直接写明。
- 预览用的就是桌面上那张位图：底下铺真实壁纸，上面叠同一个 `GlassRenderer` 的输出，所见即桌面。
- **实时玻璃页**（「个性」→「打开实时玻璃页」）：换成 GPU 每帧算的版本——`RuntimeShader`（AGSL）+ `RenderEffect.createChainEffect(着色器, 模糊)`，着色器里用圆角矩形 SDF 做折射、RGB 色散、提饱和/对比、混 tint、叠边缘高光；液态与毛玻璃只是模糊半径不同。做法与参数直接取自 FolkPatch 用的 `FletchMcKee/liquid`（Apache-2.0，见 NOTICE.md）。


### 自定义布局：重叠与任意角度旋转（3.13）

- 文字框与最多 4 个图片框不再互斥：可以任意重叠，图片按添加顺序盖在文字上面
- 选中框后拖**右上角圆点**旋转，任意角度、无吸附；拖右下角圆点改大小；「旋转」按钮每次微调 +1°，长按归零
- 文字框和图片都能旋转，图片仍支持删除、水平 / 垂直镜像
- 文字框底色改为透明，只保留 1dp 描边，和图片 / 玻璃叠在一起时透出下层
- 旋转角度以浮点数保存（场景数据版本 2）；旧布局里的 0/90/180/270 会自动迁移，不会横竖颠倒

### 回桌面自动换一句（3.12）

Android 没有「进入桌面」的广播，所以用一个每秒一次的轻量 shell 脚本盯住焦点窗口，切回桌面时发一次换句广播。两条后端：

| 后端 | 前置 | 脚本位置 |
| --- | --- | --- |
| Root（优先） | Magisk/APatch 授予本应用 root | 应用私有目录 |
| Shizuku | Shizuku 正在运行并已授权（进 App 会自动弹授权框） | /data/local/tmp |

- 进 App 时自动向 root 管理器和 Shizuku 各要一次权限，状态行显示 Root：已授权 · Shizuku：未授权 · 监听中（root）
- 不占应用进程、不起前台服务、不弹通知；BootReceiver 开机后自动把脚本拉回来
- Shizuku 集成需要 
ikka.shizuku.ShizukuProvider 且**不能**加 moe.shizuku.manager.permission.API_V23（shizuku_server 以 shell 身份来取 binder，加了会 Permission Denial）

## 功能

- 主屏幕 App Widget，`resizeMode="horizontal|vertical"`，支持横向和纵向自由调整。
- 显示句子、作者、作品出处及条目类型。
- 点按小组件换一句；设置页可关闭译写 / 意译条目，并让全部小组件刷新。
- 添加一言只需填写句子：作者与出处均为选填，留空时小组件与管理页会自动省略这一段。
- 一言管理支持给条目配图（含 GIF）：图片显示在小组件右侧、文字在左侧。
- GIF 会预拆成逐帧 PNG（间隔固定 200 ms、最多 12 帧、单帧最长边 160 px，拆帧时会跳过连续重复帧），小组件用 `AdapterViewFlipper` + `RemoteViewsService` 在桌面本地翻页，不需要应用进程反复刷新；首帧同时铺在静态 `ImageView` 上，遇到不支持集合控件的桌面也还能显示。管理页编辑时可看原始动画。
- 自定义布局（开关 + 编辑器）：文字框与最多 4 个图片框都可拖动，可以任意重叠；文字和图片都能拖右上角圆点按任意角度旋转，拖右下角改大小；图片支持删除、水平/垂直镜像；文字框可开启为透明描边框；小组件尺寸可在编辑器里用「宽 +/− / 高 +/−」调整，画布按同样宽高比预览。坐标以 0–1 分数保存，小组件按实际尺寸用 padding 还原。桌面端收不到拖拽事件，所以拖拽只发生在应用内画布上。
- 配图经系统文件选择器（SAF）选取后立刻复制到应用私有目录 `files/quote_images/`，不占相册授权、也不依赖可撤销的 `content://` 权限；单张上限 16 MB。
- 自定义背景（「个性」Tab，与玻璃材质互斥）：用系统相册选择器挑一张图片（GIF 取首帧）当小组件背景，先选图才能开启；可选高斯模糊，模糊程度 0–100% 实时预览。选完立刻复制进应用私有目录，不依赖 `content://` 授权。
- 玻璃高级设置（「个性」→「玻璃高级设置」）：对齐方式（不对齐 / 手动 X·Y 偏移 / 无障碍自动读取小组件位置）、以及实验性的 Vector/LSPosed 桌面注入开关。- 玻璃材质（「个性」Tab，可切换）：
  - **毛玻璃**（默认）：真实壁纸 → 中心裁剪 → 缩样 → 盒式模糊；再逐像素走透镜管线（SDF 求边缘与法线 → 沿法线向内采样做折射 → 边缘 RGB 色散 → 提饱和 → 只提暗部的自适应薄雾 → rim line / 定向高光 / 背光侧内阴影），SDF 覆盖率当 alpha。灯下看起来是一块透镜，而不是一层糊。
  - **液态玻璃**：半透明透镜，壁纸只轻模糊、结构还看得见，边缘有更长距离的折射与色散，透明度约 68%——桌面壁纸仍从玻璃下透出来一起叠色。
  - 「雾面程度 / 玻璃浓度 / 高光 / 圆角 / 浅深色调」都能在页面里实时预览；预览底图就是 App 读到的真实壁纸，玻璃位图和桌面上用的是同一份渲染结果。毛玻璃要读壁纸，所以同一张卡片里有「壁纸读取」状态和授权入口。
- 「个性」Tab = 玻璃材质 + 桌面布局 + 字号 + 主题色；「设置」Tab 仍是 XML 导入、GitHub Release 更新检查与下载安装、说明书。更新检查优先走 `api.github.com`，被 DNS 拦掉时退回 `github.com/…/releases.atom`。
- XML 导入格式：根节点随意，条目用 `<quote>` 或 `<item>`，属性 `text/author/work/category` 或同名子标签均可，只有 `text` 必填，重复条目跳过。
- Android 6.0+（minSdk 24），Java 实现。

## 玻璃是怎么算出来的

参考社区里把 iOS 26 Liquid Glass 在 Android 上复刻出来的实现（[react-native-liquid-glassmorphism](https://github.com/himanshu-lal4/react-native-liquid-glassmorphism)、[作者拆解](https://github.com/himanshu-lal4/react-native-liquid-glassmorphism/blob/218a47281e1f01e91a024fef83d62f48c3f5f9fc/growth/blog-android-agsl-deep-dive.md)）：它不是模糊面板，而是一块**透镜**——用圆角矩形的 SDF 取到边距离与法线，在边缘把采样坐标沿法线向内推，背景因此被「折」进来；再叠色散、vibrancy、自适应薄雾、干净的 rim line 与定向高光。那边用 `RuntimeShader`（AGSL，API 33+）在 GPU 上跑，这里因为结果要塞进 `RemoteViews`，用同一套数学在 CPU 上逐像素算成位图（工作分辨率上限 860px，单次渲染几十毫秒，在后台线程做）。

| 限制 | 现实 | 这里的取舍 |
| --- | --- | --- |
| 小组件能不能对桌面做实时 backdrop blur | 不能。`RemoteViews` 没有 RenderEffect / RuntimeShader，launcher 也不会把身后的像素交出来 | 毛玻璃用 `MANAGE_EXTERNAL_STORAGE` 读到壁纸后自己算；液态玻璃则让壁纸直接透出 |
| 毛玻璃的壁纸和桌面上的壁纸对不齐 | 小组件无法知道自己摆在壁纸哪一格 | 按中心裁剪；透镜 + 模糊会掩盖大部分差异，但放大看边界仍可能有色差 |
| 小组件能不能做「实时」玻璃 | 不能：渲染一次是一张位图，`RemoteViews` 也没有 RenderEffect / RuntimeShader | 每次刷新按当前壁纸重算；要逐帧实时只能进 App 的「实时玻璃页」 |
| App 内能不能做实时玻璃 | 能：`RenderEffect` + `RuntimeShader`（API 33+）每帧跑在 GPU 上 | 「实时玻璃页」把背景画进自己这一层，着色器直接采样，边缘折射跟着背景动 |

隐私：`MANAGE_EXTERNAL_STORAGE` 只被 `WallpaperSource` 用来读一张壁纸位图（60 秒缓存，壁纸换了才会重读），不遍历任何文件；不授权时毛玻璃退回壁纸取色，其他功能不受影响。


## 构建

在安装 Android Studio、Android SDK 35 和 JDK 17+ 的机器上打开本目录，等待 Gradle 同步，然后运行 `:app:assembleDebug`（或 `:app:assembleRelease`）。APK 位于 `app/build/outputs/apk/debug/app-debug.apk` / `app/build/outputs/apk/release/app-release.apk`。Release 目前沿用 debug 签名，方便直接覆盖安装自测。

## 在桌面添加小组件

应用内「将小组件添加到桌面」走 `AppWidgetManager.requestPinAppWidget()`，并用 `getAppWidgetIds()` 的数量变化校验是否真的添加成功（该 API 在失败时同样返回 `true`）。失败后按品牌兜底：

| 系统 | 实测行为 | 处理 |
| --- | --- | --- |
| AOSP / 三星 / ColorOS | 弹确认框，确认后添加 | 官方接口 |
| HyperOS / MIUI | 不弹框直接加；缺「桌面快捷方式」权限时静默失败 | 引导开启权限（直达权限编辑器） |
| HarmonyOS / EMUI | 弹确认框，但永不回调 PendingIntent | 数量校验 + 新建一页提示 |
| OriginOS / iQOO | 官方接口无效（需接入原子组件 SDK 并上架） | 直接给手动添加指引 |

`WidgetPinHelper.java` 负责这套兼容逻辑；ColorOS 的 `ToggleBar_widget` 面板入口仅作为兜底，且必须先 `resolveActivity` 确认存在。

## 资料与准确性

`QuoteLibrary.java` 中 `原文` 是短句或常见英文译文，仍建议发布前按所选中文版本逐条校对页码；`译写 / 意译` 明确是思想转述。像“你必须成为你希望在世界中看到的改变”这类广泛流传但出处存在争议的句子已标为意译/争议性来源，避免当作可靠逐字引文。
