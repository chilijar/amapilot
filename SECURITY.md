# 安全策略

## 支持版本

| 版本 | 支持状态 |
|---|---|
| 最新 Release（`main`） | ✅ |
| 历史 tag | ❌ 不接受安全修复 |

## 上报漏洞

- 使用 GitHub 的**私有漏洞上报**：仓库页 → Security → Report a vulnerability
- 请勿在公开 issue / PR / Discussions 中提交漏洞细节或 PoC
- 收到后预计 7 天内响应；修复流程走 Security Advisory + 私有分支

## 密钥与凭据泄露

如发现仓库任何位置出现 keystore、API key、密码等凭据（含历史提交），
同样走上述私有通道上报，请勿公开传播或利用。

## 范围说明

- 应用自身的安全漏洞（越权数据访问、注入、崩溃攻击面等）——受理
- 高德地图 SDK 内部实现的问题——不在本项目修复范围，请向高德反馈
- 依赖库（androidx / Kotlin 等）的已知 CVE——欢迎报告以便升级，但请先查
  [advisory database](https://github.com/advisories) 是否已有编号
