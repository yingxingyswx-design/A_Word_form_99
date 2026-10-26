# NOTICE

## 内置语料：Hitokoto

本 App 的「随机」分类内置了 **11186 条**语句，数据来自一言（Hitokoto）官方语句库：

- 站点：<https://hitokoto.cn/>
- 语料仓库：<https://github.com/hitokoto-osc/sentences-bundle>
- 授权：**AGPL-3.0**（见上游仓库 LICENSE）
- 打包形式：`app/src/main/assets/hitokoto_sentences.txt`（`句子 \t 作者 \t 出处 \t 原分类`）

上游说明摘录：

> 一言开源社区官方提供的语句库，系 `hitokoto.cn` 数据打包集合。语句接口默认使用此库。
> 本库遵循 AGPL 开源授权……除我们提供的超链接调用方式不受 AGPL 的开源传染影响，其余使用方式都需要遵循 AGPL 授权。
> 本库著作权并非完全由一言网持有。

### 需要遵守的部分

- 随 App 分发这份语料属于「打包使用」，因此 App 源码需按 AGPL-3.0 提供（本仓库公开可获取）。
- 语料中的句子著作权归各原作者所有；原作者要求下架的可联系上游 `i@loli.online`，本仓库同步移除。
- 若你不想承担 AGPL 义务，可以删除 `assets/hitokoto_sentences.txt`，改用设置里的在线模式（`https://v1.hitokoto.cn/`，接口调用不受传染）。

## 其他

- 应用名：每日一言 / DailyLine
- 仓库：<https://github.com/yingxingyswx-design/DailyLine_chinese>
