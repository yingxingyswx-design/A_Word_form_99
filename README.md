# 每日一言 / DailyLine

应用名随系统语言变化：简体中文（`values-zh-rCN`）下显示 **每日一言**，其他语言回退到 `DailyLine`。其余界面文案目前仍是中文硬编码。

一个 Java Android 主屏幕哲学句子小组件。项目包含 100+ 条来自外国哲学作品的条目，并区分 `原文` 与 `译写 / 意译`。意译条目不会被伪装成逐字引文；部分中文原文条目保留常见英文译文，便于核对作品出处。

## 功能

- 主屏幕 App Widget，`resizeMode="horizontal|vertical"`，支持横向和纵向自由调整。
- 显示句子、作者、作品出处及条目类型。
- 点按小组件换一句；设置页可关闭译写 / 意译条目，并让全部小组件刷新。
- 添加一言只需填写句子：作者与出处均为选填，留空时小组件与管理页会自动省略这一段。
- 一言管理支持给条目配图（含 GIF）：图片显示在小组件右侧、文字在左侧。
- GIF 会预拆成逐帧 PNG（间隔固定 200 ms、最多 12 帧、单帧最长边 160 px，拆帧时会跳过连续重复帧），小组件用 `AdapterViewFlipper` + `RemoteViewsService` 在桌面本地翻页，不需要应用进程反复刷新；首帧同时铺在静态 `ImageView` 上，遇到不支持集合控件的桌面也还能显示。管理页编辑时可看原始动画。
- 自定义布局（开关 + 编辑器）：文字框与最多 4 个图片框都可拖动，拖右下角可改大小；图片支持删除、旋转 90°、水平/垂直镜像；小组件尺寸可在编辑器里用「宽 +/− / 高 +/−」调整，画布按同样宽高比预览。文字与图片都是实体，框体相交时这次位移会被丢弃。坐标以 0–1 分数保存，小组件按实际尺寸用 padding 还原；可开启「文字以框形式」（圆角描边）。桌面端收不到拖拽事件，所以拖拽只发生在应用内画布上。
- 配图经系统文件选择器（SAF）选取后立刻复制到应用私有目录 `files/quote_images/`，不占相册授权、也不依赖可撤销的 `content://` 权限；单张上限 16 MB。
- 设置页（第三个 Tab）：XML 导入语料、GitHub Release 更新检查与下载安装、说明书。更新检查优先走 `api.github.com`，被 DNS 拦掉时退回 `github.com/…/releases.atom`。
- XML 导入格式：根节点随意，条目用 `<quote>` 或 `<item>`，属性 `text/author/work/category` 或同名子标签均可，只有 `text` 必填，重复条目跳过。
- Android 6.0+（minSdk 23），Java 实现。

## 构建

在安装 Android Studio、Android SDK 35 和 JDK 17+ 的机器上打开本目录，等待 Gradle 同步，然后运行 `:app:assembleDebug`。APK 将位于 `app/build/outputs/apk/debug/app-debug.apk`。

本次生成环境只有 Java，没有 Gradle/Android SDK，因此本次没有声称已经生成 APK；项目文件已完整写入，尚未经过本机编译验证。

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
