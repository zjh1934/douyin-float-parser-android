# 抖音悬浮解析 · Android 版

一个 Android 悬浮窗：点开 → 粘贴抖音链接 → 确定 → 悬浮球变色 → 再点开 → 显示解析出的下载地址 → 点复制图标进剪贴板。无提示文案、无主界面。

**APK 由 GitHub Actions 编译并自动发到 Releases**（沙盒里没有 Android SDK/Gradle，本机不出包）。

## 交互

| 步骤 | 表现 |
| --- | --- |
| 常驻 | 48dp 小圆球，置顶、可拖动 |
| 点一下球 | 展开输入框 + 箭头按钮（回车/actionGo 也可提交） |
| 提交后 | 收成球，琥珀色 + 转圈 |
| 解析成功 | **球变绿**；失败变红，再点开可重新输入 |
| 再点开球 | 展开显示地址（可滚动、可选中；图集多条换行） |
| 点复制图标 | 写入系统剪贴板，图标短暂变对勾 |
| 长按球 | 重置 / 退出 |

窗口尺寸紧贴可见内容（56 / 348×60 / 396×140 dp），透明空白区不会挡住下层应用。

## 技术

- Kotlin + 零第三方依赖：`HttpURLConnection` + `org.json` + `Handler`，CI 构建最稳
- `TYPE_APPLICATION_OVERLAY` 悬浮窗 + 前台服务（通知渠道），minSdk 26 / targetSdk 33
- 入口 `MainActivity` 只负责申请「显示在其他应用上层」权限，授权后自动起服务并退到后台
- 解析链路：分享文案取链接 → 短链 302（相对 `Location` 用 `URI.resolve`）→ aweme_id → iesdouyin 移动端分享页（首次拿 `ttwid` Cookie，二次请求拿数据）→ 花括号配对截 `window._ROUTER_DATA` → `playwm`→`play` / `ratio=1080p` 端点 → 跟一次 302 得最终 CDN 直链；图集走 `images[].url_list` 末项
- 思路参考 [gloryhry/douyinVd](https://github.com/gloryhry/douyinVd)、[SSSYoung/DouYinKit](https://github.com/SSSYoung/DouYinKit)、[521w/douyin-mcp](https://github.com/521w/douyin-mcp)，未复制其源码

## 沙盒模拟运行（已跑通）

```bash
node simulator/run.js
```

模拟器起一个「假抖音」，复现真实链路里最容易翻车的几处，并跑完整 UI 状态机：

1. 短链 302 给的是**相对 Location**
2. 分享页第一次只给 `ttwid` Cookie、不给数据
3. `_ROUTER_DATA` 的标题里带 `}` 和 `{`（非贪婪正则会被截断）
4. 播放端点再 302 到签名 CDN 直链
5. 点开 → 输入 → 提交 → 变色 → 展开 → 复制 → 剪贴板断言
6. 错误链路：非抖音链接、分享页风控 → 变红、可重试

最后把模拟出的正确行为逐条对照 Kotlin 源码，18 项全通过才允许 CI 打包。首次模拟 12/17，报出 5 处实现缺口；顺带把异常兜底一起加固，共改动 6 处：

| 发现的问题 | 修复 |
| --- | --- |
| `URL(loc)` 遇相对 Location 抛异常 | `URI(current).resolve(loc)` |
| 分享页只请求一次，风控时拿不到数据 | 带 `ttwid` Cookie 二次请求 |
| 非贪婪正则被文案里的 `}` 截断 | 花括号配对（字符串内括号不计数） |
| 主线程直接发网络请求 | `Thread` + `mainHandler.post` |
| `FLAG_NOT_FOCUSABLE` 导致输入框无法聚焦/弹键盘 | 展开输入时移除该 flag + `showSoftInput`，收起恢复 |
| 网络异常会打崩服务 | `try/catch` 兜底落 ERROR 态 |

## 打包

`.github/workflows/release.yml`：

1. `node simulator/run.js` 模拟自检
2. JDK 17 + Gradle 8.7 + Android SDK 34
3. `gradle assembleDebug` 出 `app-debug.apk`（自带 debug 签名，拿到即可安装）
4. `softprops/action-gh-release` 把 APK 发到 Releases

触发：`git tag v0.1.0 && git push origin v0.1.0`，或在 Actions 页手动 Run workflow 填 tag。

仓库需要 **Settings → Actions → General → Workflow permissions → Read and write permissions**（workflow 已声明 `contents: write`）。

## 使用

1. 从 Releases 下载 `DouyinFloatParser-v*.apk` 安装（未知来源需允许）
2. 打开 App → 点「去授权」打开「显示在其他应用上层」→ 返回后悬浮球自动出现
3. 复制抖音分享链接 → 点球 → 粘贴 → 点箭头 → 球变绿 → 再点开 → 点复制图标

## 限制

- 抖音页面结构与风控随时可能变，直解可能某天失效；解析出的 CDN 直链带签名会过期，拿到尽快用。
- 仅用于个人学习、解析自己有权访问的内容，尊重原作者版权与平台条款。
