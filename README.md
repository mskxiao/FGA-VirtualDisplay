# FGA-VirtualDisplay

[Fate-Grand-Automata (FGA)](https://github.com/Fate-Grand-Automata/FGA) 自动化核心 + 独立 VirtualDisplay 后端的集成原型。

**阶段 1 目标**：验证 FGA 的识别、任务、脚本逻辑能否在一个独立虚拟显示环境中完成截图、识别与定向操作。

本阶段**不**验证虚拟屏技术本身是否可行（已由项目负责人在 Android 16 真机验证），也**不**做完整 FGO 战斗流程。

---

## 上游

| 项 | 值 |
|---|---|
| 仓库 | `Fate-Grand-Automata/FGA` |
| 分支 | `master` |
| 基线 commit | `3852388b685d1ce35be8aa17b26e8e212af910c7` |
| License | MIT |

本仓库包含 FGA 未修改的源码副本。本项目的改动均在新分支与独立模块中进行，便于用 `git diff` 对上游追溯。

**License 说明**：本项目不包含任何 MaaFGO / MaaFwApp（AGPL-3.0）代码，仅参考其公开可查的 Android API 用法与设计取舍。

---

## 构建

全部通过 GitHub Actions 云端构建，**不需要本地电脑编译**。

- 工作流：`.github/workflows/android.yml`
- 触发：push / 手动 `workflow_dispatch`
- 产物：`app-ci.apk`（debug 签名，包名后缀 `.debug`，可与正式 FGA 共存）
- 无需任何 GitHub Secrets

---

## 文档

- `STAGE1_ARCHITECTURE.md` — 阶段 1 架构与接入点
- `STAGE1_VERIFICATION.md` — 实测记录（区分已验证 / 未验证）

> 说明：上游的 `docs/`(84MB) 与 `wiki/`(13MB) 未导入，不参与编译。
