# 阶段 1 架构

> 本文描述**已实现**的结构。未实现的部分明确标注为计划。

---

## 1. 分层

```
┌────────────────────────────────────────────────────────────────┐
│ :app  (io.github.fate_grand_automata)                          │
│                                                                │
│  ScreenshotServiceHolder ──┐   ← 阶段 2 要加第三分支的位置        │
│  ScriptsModule @Binds ─────┘   ← GestureService 绑定位置         │
└────────────────────────────┼───────────────────────────────────┘
                             │ 适配层（阶段 2，未实现）
┌────────────────────────────▼───────────────────────────────────┐
│ :vdisplay  (本阶段全部代码)                                      │
│                                                                │
│  VirtualDisplayBackend  ← 接口（唯一的平台缝）                   │
│    ├─ ShellVirtualDisplayBackend   ← 本阶段实现                  │
│    └─ (计划) NativeVirtualDisplayBackend                        │
│                                                                │
│  DisplayConfig   16:9 锁定 + 密度                               │
│  DisplayIds      logicalId(Int) + surfaceFlingerId(ULong)       │
│  DisplayFrame    RGBA_8888 裸像素                                │
│  ShellRunner     进程抽象（让模块保持纯 JVM 可测）                 │
│  DisplayOutputParsers  dumpsys/cmd/screencap 输出解析            │
└────────────────────────────────────────────────────────────────┘
          ↓ 通过 /system/bin 与 dumpsys
┌────────────────────────────────────────────────────────────────┐
│ Android 平台                                                   │
│  settings(建屏) · dumpsys(解析 id/任务) · screencap -d(截图)      │
│  input -d(点击/滑动) · am start --display(启动目标)              │
└────────────────────────────────────────────────────────────────┘
```

**依赖方向**：`:app → :vdisplay`，且 `:vdisplay` **不依赖** `:libautomata` / `:scripts`。
FGA 自动化核心因此完全不知道虚拟显示的存在。

---

## 2. 为什么是 CLI 后端而不是隐藏 API

审查报告推荐方案 2（自写 native + 隐藏 API）。本阶段实际选择 CLI，理由：

| 维度 | CLI 后端 | 隐藏 API 后端（审查报告方案 2） |
|---|---|---|
| 写代码前能否验证 | **能**，M0 已用 root shell 逐项实测 | 不能，必须先编译+部署 |
| 新增代码量 | ~700 行 Kotlin | ~1500 Kotlin + ~700 C++ + 特权进程 |
| 需要 NDK / 反射 / proguard | 否 | 是 |
| 是否真后台 | **否**（见 §4） | 是 |

关键判据：你只有手机、无本地编译，每次试错 = 一次 Actions 往返（约 10 分钟）。
CLI 后端把「平台是否可用」这个最大不确定性提前到写代码之前解决。

两种后端实现**同一个接口**，可替换。这是 T2 要求的适配层。

---

## 3. 两个 id 不能混用（本阶段最重要的实现约束）

| 命令 | 需要 | 类型 |
|---|---|---|
| `screencap -d` | SurfaceFlinger display id | **uint64** |
| `input -d`, `cmd activity start --display` | DisplayManager logical id | Int |

实测数据（Android 16 真机）：

| 次 | logical | SurfaceFlinger |
|---|---|---|
| 1 | 22 | 11529215048376362670 |
| 2 | 23 | 11529215048972000193 |
| 3 | 24 | 11529215046450979600 |

**SF id 是 uint64，超出 Kotlin `Long` 范围**（`Long.MAX_VALUE` = 9223372036854775807，
实测值 11529215048376362670 = `0xA0000000898FA6AE`）。4 个实测值里有 2 个溢出。

这个 bug 是被 CI 捕获的：首版用 `Long`，`compileTestKotlin` 报
`Value out of range`。如果是运行时才触发，会表现为首次截图 `NumberFormatException`。

**id 配对策略**（`ShellVirtualDisplayBackend.awaitDisplayCreation`）：
1. 建屏前后做 SF 集合差分（不能用算术推导，SF id 每次重建都变）
2. 建屏前后做 logical id 集合差分
3. 用 `layerStack == logicalId` 交叉验证（3 次实测均成立）

**失败时的安全行为**：SF id 解析不出来 → `surfaceFlingerId = null` → `capture()` 抛异常。
**绝不回退到物理屏。**

---

## 4. 已知限制（不隐藏）

### 4.1 overlay display 不是真后台

`overlay_display_devices` 创建的是 `type OVERLAY` 的叠加视图（`displayName="叠加视图 #1"`），
**主屏仍能看见目标应用窗口**。

本阶段验收项里：
- 「VirtualDisplay 原型成功创建」→ **通过**
- 「主屏独立性」→ **不满足**，需要换隐藏 API + `OWN_CONTENT_ONLY` 后端

### 4.2 `canHostTasks=false` 不可作为判据

DisplayInfo 报 `canHostTasks false`，但 Activity 实际能 `RESUMED` 在该屏上（已实测）。

### 4.3 手势取消只能是尽力而为

`input` 没有 cancel 动词。`cancelGesture()` 发一个越界 tap 并记录日志，
**不能保证**子进程 mid-swipe 时能释放。这是 CLI 后端的固有缺陷。

### 4.4 屏幕创建是进程全局状态

`overlay_display_devices` 是全局 setting，不是 per-app。后端是单例，
`stop()` 会**精确恢复**原值（`null` → `delete`，非 null → 写回原字符串）。

---

## 5. 安全设计

| 机制 | 实现 |
|---|---|
| 不可能截错屏 | SF id 未解析时 `capture()` 抛异常，不回退 |
| 不可能点错屏 | 坐标必须在 `0..width-1 × 0..height-1` 内，否则抛异常 |
| 几何校验 | 帧尺寸≠config 尺寸时抛异常 |
| 命令注入 | `ShellRunner.run(argv: List<String>)` 只接受参数数组，不接受 shell 字符串 |
| 状态机 | `IDLE → STARTING → RUNNING → STOPPING → IDLE`，`start` 幂等，`stop` 幂等 |
| 自动回滚 | `start` 任何步骤失败 → 恢复 overlay setting + 回到 IDLE |
| 用户设置保护 | 记录并精确恢复原 `overlay_display_devices` |

---

## 6. 阶段 2 接入点（未实现）

按审查报告 T7，需要改 6 个文件、新增 2 个：

| 文件 | 改动 |
|---|---|
| `app/.../runner/VirtualDisplayScreenshotService.kt` | **新增**，`ScreenshotService` 实现，`DisplayFrame`(RGBA) → `Pattern` |
| `app/.../runner/PrivilegedGestureService.kt` | **新增**，`GestureService` 实现 → `backend.tap/swipe` |
| `app/.../runner/ScreenshotServiceHolder.kt` | 加虚拟屏分支 |
| `app/.../util/DisplayHelper.kt` | 可选 displayId |
| `app/.../util/CutoutManager.kt` | 虚拟屏尺寸分支 |
| `app/.../di/script/ScriptsModule.kt` | `GestureService` 绑定 |
| `app/.../di/service/ServiceModule.kt` | 提供 backend |
| `prefs/` | `use_virtual_display` 开关 |

**禁止改**：`libautomata/**`、`scripts/**`、`ImageMatcher`、`Transformer`、`Scale`。

### 16:9 让几何链路免费成立

1280×720 下（审查报告 §5.1 推导，本阶段**未实测**）：
- `screenToImage` = `null` → 不缩放
- `gameArea` = `Region(0,0,1280,720)`
- `scriptToScreen` = 0.5

**这是推导，不是实测。** 阶段 2 必须用 `ProbeActivity` 验证实际点击落点误差。

---

## 7. 与上游的差异

```
git diff 3852388..main --stat
```

| 类型 | 路径 |
|---|---|
| 新增 | `vdisplay/**`（全部） |
| 新增 | `.github/workflows/android.yml` |
| 新增 | `README.md`, `STAGE1_ARCHITECTURE.md`, `STAGE1_VERIFICATION.md` |
| 修改 | `settings.gradle.kts`（+`include(":vdisplay")`） |
| **未改** | `libautomata/**`, `scripts/**`, `prefs/**`, `app/**` |

`app/**` 与 `prefs/**` 在本阶段**完全零改动**。
