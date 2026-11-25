# 阶段 1 验证记录

> 本文件只记录**实测**结果。每条结论都注明是「已验证」还是「未验证」，以及证据来源。
> 时间：2026-11-25。设备：Redmi K70（23113RKC6C）/ Android 16 / API 36 / OS3.0.307.0.WNKCNXM / KernelSU。

---

## 0. 环境核实（已验证）

| 项 | 实测值 | 命令 |
|---|---|---|
| Android 版本 | 16 | `getprop ro.build.version.release` |
| SDK | 36 | `getprop ro.build.version.sdk` |
| 机型 | 23113RKC6C（Redmi K70） | `getprop ro.product.model` |
| Root | `uid=0`, `u:r:ksu:s0`（KernelSU） | `su -c id` |
| 已装 FGA | `io.github.fate_grand_automata` 3081, targetSdk 36 | `dumpsys package` |
| 已装 FGO | `com.bilibili.fatego` 2.129.0 | `dumpsys package` |
| 基线显示 | 仅 displayId 0（1440×3200） | `cmd display get-displays --ids-only` |

---

## 1. M0 平台原语探针（全部已验证）

在 root shell 内直接调用系统 CLI，观察副作用。**探针前记录基线，探针后完全回退。**

回退已确认：`overlay_display_devices = null`，`cmd display get-displays --ids-only` 仅返回 `0`，临时文件已删。

### P-1 创建虚拟显示：通过

```
settings put global overlay_display_devices '1280x720/240'
→ cmd display get-displays --ids-only : 0, 22
→ DisplayInfo{displayId 22, type OVERLAY, real 1280 x 720, density 240, state ON}
```

### P-2 双 Display ID：已确认，且**不可混用**

| 命令 | 需要的 ID | 实测 |
|---|---|---|
| `screencap -d <id>` | **SurfaceFlinger** display id | `-d 22` → `Failed to take take screenshot. Display Id '22' is not valid.`<br>`-d 11529215048376362670` → 成功 |
| `input -d <id> tap/swipe` | **DisplayManager logical** id | `-d 22` → 成功 |
| `cmd activity start --display <id>` | **DisplayManager logical** id | `--display 22` → 成功 |

**SF id 与 logical id 无算术关系**，且 SF id 每次重建都变：

| 次 | logical id | SF id |
|---|---|---|
| 1 | 22 | 11529215048376362670 |
| 2 | 23 | 11529215048972000193 |
| 3 | 24 | 11529215046450979600 |

**SF id 是 uint64，超出 Kotlin `Long` 范围**：

```
11529215048376362670 = 0xA0000000898FA6AE
Long.MAX_VALUE       =  9223372036854775807
作为有符号 int64     = -6917529025333188946
```

四个实测值中两个溢出。主屏的 `4630946738963277955` 不溢出 —— 意味着**只测主屏会漏掉这个 bug**。代码用 `ULong` 承载。

**唯一稳定不变量**：`layerStack == logicalId`（三次实测均成立）；`uniqueId` 是 `overlay:N`，N 随重建递增，**不可**用作稳定键。

**结论**：后端必须在建屏前后做 **SF display 集合差分**，不能靠算术推导、也不能只匹配 `overlay:1`。

### P-3 虚拟显示截图：通过

```
screencap -d 11529215048376362670 -p out.png   → 1280 x 720, 281686 bytes, 内容确认为系统设置界面
screencap -d <sfid>  (raw)                      → 3686416 bytes = 1280*720*4 + 16
```

头部（LE int32）：`w=1280, h=720, format=1 (RGBA_8888), extra=1`，其后 `w*h*4` 字节像素。
**与 FGA `RootScreenshotService.screenshotIntoBuffer()` 的解析格式完全一致**（`app/.../root/RootScreenshotService.kt:28-55`）。

### P-4 定向输入：通过（点击 + 滑动）

```
点击：input -d 22 tap 640 300   → 前后截图 MD5 不同；OCR 从「设备名称列表」变为「HyperOS / Redmi K70」详情页
滑动：input -d 22 swipe 640 500 640 200 300 → 前后截图不同；OCR 滚动到「附加功能/小米换机」区域
```

### P-5 输入不泄漏到物理屏：通过

点击虚拟屏期间及之后，主屏 `Display0:[...]` 窗口列表**完全未变**，主屏 `screencap` 正常。

### P-6 目标应用运行于虚拟屏：通过

```
cmd activity start --display 22 -n com.bilibili.fatego/.EmptyClass
→ pidof : 26099
→ dumpsys activity: Display #22 内 topResumedActivity = .UnityPlayerNativeActivity (RESUMED)
→ screencap -d <sfid> : 628647 bytes，OCR 读出从者详情页文本
```

启动器 Activity 为 `.EmptyClass`（`.MainActivity` 不存在）。

### P-7 `canHostTasks=false` 但不影响承载任务

`DisplayInfo{..., type OVERLAY, ..., canHostTasks false}`，但 P-6 中 Activity 确实 `RESUMED`。
**不得**用 `canHostTasks` 作为承载判据。

### P-8 overlay display 的形态限制：**不满足真正后台**

`DisplayInfo{type OVERLAY, displayName="叠加视图 #1"}` —— 主屏上的浮动叠加窗口，**主屏仍可见**。

对应验收：「VirtualDisplay 原型成功创建」**通过**；「主屏独立性」**不满足**。

### P-9 已知未验证项

- 长时间（≥2h）连续运行稳定性、内存、帧率
- `input -d` 的事件速率限制（未压测）
- 多虚拟屏同时存在时的 ID 分配规律
- 分屏/小窗模式下 overlay display 行为

---

## 2. 云端构建（已验证）

| 项 | 结果 |
|---|---|
| 仓库 | `mskxiao/FGA-VirtualDisplay`（public） |
| 上游基线 | `Fate-Grand-Automata/FGA` @ `3852388b685d1ce35be8aa17b26e8e212af910c7` |
| 工作流 | `.github/workflows/android.yml` |
| JDK | 21（temurin） |
| Secret | **不需要任何 Secret**（`ci` buildType 用仓库内已公开的 `fgadebug.keystore`） |

### 2.1 基线编译（证明上游可构建）

| commit | 结果 | 产物 |
|---|---|---|
| `4bca42b` | **success** | `FGA-VirtualDisplay-4bca42b-run3.apk` 39.2 MB |

### 2.2 含 `:vdisplay` 的构建（本阶段交付）

| commit | :vdisplay:test | assembleCi | 产物 |
|---|---|---|---|
| `29a9906` | **success** | **success** | `FGA-VirtualDisplay-29a9906-run23.apk` 39.17 MB |

构建链接：`https://github.com/mskxiao/FGA-VirtualDisplay/actions/runs/45601371205`

### 2.3 构建过程中发现并修复的真实缺陷

| # | 失败现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 前 3 次 run 全部 `cancelled` | 用了 `cancel-in-progress: true`，而 Git Data API 每个文件一次 push，后一次杀掉前一次 | 改为按 commit 排队，仅 PR 取消 |
| 2 | `:vdisplay:compileTestKotlin FAILED` | JUnit 6 是 Java 17 字节码，与 JVM 11 的 test classpath 不兼容（上游 `:scripts` 已有同样处理） | test 编译提到 17，主库保持 11 |
| 3 | `DisplayOutputParsersTest.kt:30:36 Value out of range` | **SF id 是 uint64，超出 Long 范围** | 改用 `ULong`（见 P-2） |

第 3 项是本阶段最有价值的发现：不只是编译错误，而是**设计级陷阱** —— 用 `Long` 或在运行时才解析，会在首次截图时 `NumberFormatException`，而日志会指向截图而非 id 解析。

**注意**：编译成功 ≠ 功能测试通过。以上只证明「源码树在 CI 可编译出 APK、解析器单元测试通过」。

### 2.4 单元测试（真实运行的 13 个用例）

`DisplayOutputParsersTest`，输入全部是真机逐字捕获的输出：

- SF id 解析（含 uint64 溢出回归、非溢出主屏 id）
- logical display 解析（type / uniqueId / layerStack 不变量）
- `--ids-only` 解析
- screencap raw 头（16 字节 / 12 字节 / 太短 / 非 RGBA）
- 实测 payload 尺寸 3686416 字节
- activities 按 display 分组

---

## 3. 待验证（未做）

- [ ] APK 安装到真机
- [ ] `ShellVirtualDisplayBackend` 在 App 进程中真实运行（本阶段只在 root shell 里验证过原语，**没有**在 App 进程里跑过）
- [ ] FGA `ScreenshotService` 接入虚拟屏
- [ ] FGA `ImageMatcher` 处理虚拟屏帧
- [ ] FGA `GestureService` 适配虚拟屏
- [ ] 16:9 几何推导（`screenToImage == null`、`gameArea == Region(0,0,1280,720)`）—— 这是**推导，不是实测**
- [ ] 资源释放（反复启停 20 次）
- [ ] 主屏独立性（P-8 已知不满足，需换后端实现）
