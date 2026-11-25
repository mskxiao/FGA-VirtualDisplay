# 阶段 2A：App 进程内验证记录

> 只记录实测。未验证项明确列出，不将编译成功当作功能通过。
> 设备：Redmi K70（23113RKC6C）/ Android 16 / API 36 / OS3.0.307.0.WNKCNXM / KernelSU。
> 日期：2026-11-25

---

## 1. T1：App 进程与 Root Shell 的差异（已验证部分）

这一节是本阶段最有价值的部分：M0 的成功不能直接搬到 App 进程，差异在哪里。

### D-1 `su -c` 的二进制管道无损：**已验证**

这是最重要的前置结论。后端靠 `screencap` 向 stdout 吐 3.6MB 原始像素，如果 `su` 转发
stdout 时做任何行处理或编码转换，整个截图链路就废了。

```
直接：  screencap -d <sfid> > a.bin          -> 3686416 bytes
经 su： su -c "screencap -d <sfid>" > b.bin  -> 3686416 bytes
连续三次 su 抓帧：
  s1: 3686416 bytes  md5=4d9b4955…
  s2: 3686416 bytes  md5=8873bf71…
  s3: 3686416 bytes  md5=8873bf71…
  s2 vs s3: IDENTICAL (byte-for-byte)
```

**结论**：`su -c` 传递 stdout 是逐字节无损的；首帧差异是渲染未稳定，不是传输损坏。
预期尺寸 `1280*720*4+16 = 3686416` 完全匹配。

### D-2 每条命令都需 root：已验证需要，未在 App 进程内验证

| 命令 | 所需权限 | App 持有 |
|---|---|---|
| `settings put global` | `WRITE_SECURE_SETTINGS`（signature\|privileged） | **否** |
| `screencap -d`（非默认屏） | 读其他 display 缓冲 | **否** |
| `input -d` | `INJECT_EVENTS`（signature\|privileged） | **否** |
| `dumpsys SurfaceFlinger` | dump 权限 | **否**（输出被限制） |
| `am start --display` | 跳屏启动 | **否** |

M0 能直接跑，是因为那些命令**已经在 root shell 里**，不需要任何前缀。因此阶段 2A 增加了
`RootShellRunner`，把每条命令都包成 `su -c`。这是 M0 与 App 进程之间**最大的行为差异**。

### D-3 参数拼接的注入风险：已识别并处理

`su -c` 接收单个字符串，argv 必须拼接成命令行。如果用字符串拼接然后交给 shell，
包名、路径就可能被解释为 shell 语法。`RootShellRunner.quote()` 对每个参数做 POSIX
单引号转义（`'` → `'\''`），并有单元测试覆盖。

### D-4 未验证（需 App 进程内实测）

- `su` 在 App 进程内的授权弹窗是否会阻塞，以及超时影响
- `ProcessBuilder` 在当前内核/ROM 下调用 `su` 的启动开销
- App 进程上下文里 `dumpsys activity activities` 的输出完整性（是否被截断）
- `am start --display` 从 App 进程发起时的行为

---

## 2. T2/T3/T4：实现内容

见 `STAGE1_ARCHITECTURE.md` 与提交记录。要点：

| 文件 | 作用 |
|---|---|
| `probe/AndroidShellRunner.kt` | App 内进程执行；两路流并发抽取防死锁；超时销毁 |
| `probe/RootShellRunner.kt` | 所有命令包 `su -c`；POSIX 单引号转义 |
| `probe/ColorBlockDetector.kt` | 纯像素运算识别色块（不依赖 OpenCV） |
| `probe/ProbeActivity.kt` | 目标 Activity：画红方块，点击变色，记录 tap |
| `probe/ProbeHarnessActivity.kt` | 测试 UI：单次 / 连续 3 次 / 强制释放 |
| `probe/ProbeRunner.kt` | 17 步闭环，每步显式断言 |
| `probe/ProbeSuite.kt` | 多次运行 + 累积检测 + 报告落盘 |
| `probe/EmergencyRelease.kt` | 不经过 runner 的独立释放通道 |

### 判定设计（为何不能只看日志）

点击成功需要**两个独立证人**同时成立：

1. 目标 Activity 自己的 `insideTapCount >= 1` 且 `lastTapInsideBlock == true`
   （证明事件真的到达了**这个 display**，且落在方块内）
2. 点击点像素在前后两帧中真实变化（`|ΔR|+|ΔG|+|ΔB| >= 60`）

任一单独成立都可能巧合，两者同时成立不能。

---

## 3. 云端构建（已验证）

| 项 | 结果 |
|---|---|
| commit | `1f82077` |
| run | [#45605175429](https://github.com/mskxiao/FGA-VirtualDisplay/actions/runs/45605175429) |
| `:vdisplay:test` | **success**（13 用例） |
| `:app:testCiUnitTest` | **success**（11 用例：引号 4 + 检测器 7） |
| `:app:assembleCi` | **success** |
| 产物 | `FGA-VirtualDisplay-1f82077-run45.apk`，39.2 MB |

### 构建循环中发现并修复的编译错误（均为真实缺陷）

| # | CI 报错 | 根因 |
|---|---|---|
| 1 | `EmergencyRelease.kt:28:42 Return type mismatch: expected 'Unit', actual 'Boolean'` | `return@step lines.add(...)` 返回了 Boolean |
| 2 | `ProbeActivity.kt:86:39 Unresolved reference 'BLOCK_FRACTION'` | `BlockView` 是 Activity 的内部类，不是 Observer 的 |
| 3 | `ProbeHarnessActivity.kt:77:13 Unresolved reference 'enabled'` | `Button(this).apply { enabled = false }` 中 `this` 解析为 Activity |
| 4 | 诊断步骤在构建失败时被跳过 | `if: steps.build.outcome == 'failure'` 不可靠 |

第 4 项值得一提：它使首次失败**毫无原因**，只有一个 exit code。修成无条件执行后，后续三次失败
都精准给出了文件:行号:列号。

**编译成功 ≠ 功能通过。** 上表仅证明可编译出 APK、纯 JVM 逻辑测试通过。

---

## 4. 真机测试（待执行）

**尚未安装 APK，因此以下全部未验证：**

- [ ] APK 在手机上安装成功
- [ ] ProbeHarnessActivity 能启动
- [ ] App 进程内获得 root（`su -c id`）
- [ ] 虚拟屏创建（App 内）
- [ ] ProbeActivity 运行于虚拟屏
- [ ] 截图内容正确
- [ ] 红色方块识别
- [ ] 定向点击生效
- [ ] 变色确认（两个证人同时成立）
- [ ] 资源释放（DisplayManager + SurfaceFlinger 双向验证）
- [ ] 连续 3 次无累积
- [ ] 中途失败能释放（**未测试**，需人为制造失败）
- [ ] uint64 id 在真实虚拟屏上不溢出（**注**：实测 4 个 SF id 中 2 个超过 Long.MAX_VALUE，见阶段 1 P-2）
- [ ] 不误投到物理屏

安装命令（需手机端操作或授权）：

```
adb install -r FGA-VirtualDisplay-1f82077-run45.apk
# 或直接点击下载的 APK
```

包名：`io.github.fate_grand_automata.debug`（`.debug` 后缀，与正式版 3081 共存）
