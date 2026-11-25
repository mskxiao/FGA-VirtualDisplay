# 阶段 1 验证记录

> 本文件只记录**实测**结果。每条结论都注明是「已验证」还是「未验证」，以及证据来源。
> 时间基准：2026-11-25。设备：Redmi K70（23113RKC6C）/ Android 16 / API 36 / OS3.0.307.0.WNKCNXM / KernelSU。

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

探针方式：在 root shell 内直接调用系统 CLI，观察副作用。**探针执行前记录基线，执行后完全回退。**

回退结果已确认：`overlay_display_devices = null`，`cmd display get-displays --ids-only` 仅返回 `0`，临时文件已删除。

### P-1 创建虚拟显示：通过

```
settings put global overlay_display_devices '1280x720/240'
→ cmd display get-displays --ids-only : 0, 22
→ DisplayInfo{displayId 22, type OVERLAY, real 1280 x 720, density 240, state ON}
```

**结论**：`overlay_display_devices` 可在 Android 16 上创建 1280×720@240 的虚拟显示。

### P-2 双 Display ID：已确认，且**不可混用**

| 命令 | 需要的 ID 类型 | 实测 |
|---|---|---|
| `screencap -d <id>` | **SurfaceFlinger** display id | `-d 22` → `Failed to take take screenshot. Display Id '22' is not valid.`<br>`-d 11529215048376362670` → 成功 |
| `input -d <id> tap/swipe` | **DisplayManager logical** id | `-d 22` → 成功 |
| `cmd activity start --display <id>` | **DisplayManager logical** id | `--display 22` → 成功 |

**SF id 与 logical id 之间没有算术关系**，且 SF id 每次重建都变化：

| 次 | logical id | SF id |
|---|---|---|
| 1 | 22 | 11529215048376362670 |
| 2 | 23 | 11529215048972000193 |
| 3 | 24 | 11529215046450979600 |

**唯一稳定不变量**：`layerStack == logicalId`（三次实测均成立），且 `uniqueId = "overlay:N"`（N 随重建递增，**不可**用作稳定键）。

**结论**：后端必须在建屏前后做 **SF display 集合差分**，不能靠算术推导、也不能只匹配 `overlay:1`。

### P-3 虚拟显示截图：通过

```
screencap -d 11529215048376362670 -p out.png   → 1280 x 720, 281686 bytes, 内容确认为系统设置界面
screencap -d <sfid>  (raw)                      → 3686416 bytes = 1280*720*4 + 16
```

头部（LE int32）：`w=1280, h=720, format=1 (RGBA_8888), extra=1`，其后 `w*h*4` 字节像素。
**与 FGA `RootScreenshotService.screenshotIntoBuffer()` 的解析格式完全一致**（`app/.../root/RootScreenshotService.kt:28-55`）。

**结论**：可直接复用 FGA 的 raw 解析思路，无需 PNG 编解码。

### P-4 定向输入：通过（点击 + 滑动）

```
点击：input -d 22 tap 640 300   → 前后截图 MD5 不同；OCR 显示从「设备名称列表」变为「HyperOS / Redmi K70」详情页
滑动：input -d 22 swipe 640 500 640 200 300 → 前后截图不同；OCR 显示滚动到「附加功能/小米换机」区域
```

**结论**：点击与滑动均真实作用于虚拟显示。

### P-5 输入不泄漏到物理屏：通过

点击虚拟屏期间及之后，主屏 `Display0:[...]` 窗口列表**完全未变**（`com.miui.home`、`com.ai.assistance.operit`、`com.android.systemui` 等仍在原位），主屏 `screencap` 正常。

**结论**：`input -d` 未把事件落到 display 0。

### P-6 目标应用运行于虚拟屏：通过

```
cmd activity start --display 22 -n com.bilibili.fatego/.EmptyClass
→ pidof com.bilibili.fatego : 26099
→ dumpsys activity: Display #22  内 topResumedActivity = com.bilibili.fatego/.UnityPlayerNativeActivity (state=RESUMED)
→ screencap -d <sfid> : 628647 bytes，OCR 可读出从者详情页文本（迪尔茂德·奥迪那 / 职阶:剑士 …）
```

启动器 Activity 为 `.EmptyClass`（非 `.MainActivity`，后者不存在）。

**结论**：FGO 能在虚拟显示内启动并渲染出可识别画面。

### P-7 `canHostTasks=false` 但不影响承载任务

```
DisplayInfo{displayId 22, ..., type OVERLAY, ..., canHostTasks false}
```

但 P-6 中 Activity 确实在 display 22 上 `RESUMED`。

**结论**：**不得**用 `canHostTasks` 作为「能否承载任务」的判据。

### P-8 overlay display 的形态限制：**不满足真正后台**

```
DisplayInfo{type OVERLAY, displayName="叠加视图 #1"}
```

overlay display 是主屏上的浮动叠加窗口，**主屏仍可见**，不满足审查报告中 MaaFwApp `OWN_CONTENT_ONLY` 那种「目标 App 搬到虚拟屏、主屏空出来」的形态。

**这是本阶段最重要的已知限制**，对应验收项「VirtualDisplay 原型成功创建」通过、但「主屏独立性」不满足。

### P-9 已知未验证项

- Windows/分割窗口模式下 overlay display 行为
- 长时间（≥2h）连续运行的稳定性、内存与帧率
- `input -d` 是否有事件速率限制（未做高频压测）
- 多虚拟屏同时存在时的 ID 分配规律

---

## 2. 云端构建（已验证）

| 项 | 结果 |
|---|---|
| 仓库 | `mskxiao/FGA-VirtualDisplay`（public） |
| 基线 commit | `4bca42b`（= 上游 `3852388` + 本仓库 README/CI） |
| 工作流 | `.github/workflows/android.yml` |
| JDK | 21（temurin），满足上游 toolchain 约束 |
| 构建任务 | `:app:assembleCi` |
| 结果 | **success** |
| 产物 | `FGA-VirtualDisplay-<sha>-run<N>.apk`，39.2 MB |
| Secret | **不需要任何 Secret**（`ci` buildType 用仓库内已公开的 `fgadebug.keystore`） |

**注意**：编译成功 ≠ 功能测试通过。上表只证明「源码树在 CI 可编译出 APK」。

---

## 3. 待验证（未做）

- [ ] 本章节以下的集成代码（`:vdisplay` 模块）编译是否通过
- [ ] APK 安装到真机
- [ ] FGA 截图接口接入虚拟屏
- [ ] FGA `ImageMatcher` 处理虚拟屏帧
- [ ] FGA 点击/滑动适配虚拟屏
- [ ] 资源释放（反复启停 20 次）
- [ ] 主屏独立性（P-8 已知不满足，需换后端实现）
