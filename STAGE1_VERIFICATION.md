## 2. 云端构建（已验证）

| 项 | 结果 |
|---|---|
| 仓库 | `mskxiao/FGA-VirtualDisplay`（public） |
| 上游基线 | `Fate-Grand-Automata/FGA` @ `3852388b685d1ce35be8aa17b26e8e212af910c7` |
| 工作流 | `.github/workflows/android.yml` |
| JDK | 21（temurin），满足上游 toolchain 约束 |
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
| 1 | 前 3 次 run 全部 `cancelled` | 工作流用了 `cancel-in-progress: true`，而 Git Data API 每个文件一次 push，后一次杀掉前一次 | 改为按 commit 排队，仅 PR 取消 |
| 2 | `:vdisplay:compileTestKotlin FAILED` | JUnit 6 是 Java 17 字节码，与 JVM 11 的 test classpath 不兼容（上游 `:scripts` 已有同样处理，我洩了） | test 编译提到 17，主库保持 11 |
| 3 | `DisplayOutputParsersTest.kt:30:36 Value out of range` | **SurfaceFlinger id 是 uint64，超出 Long 范围** | 改用 `ULong`（见 §1 P-2） |

第 3 项是本阶段最有价值的发现：它不仅是一个编译错误，而是一个**设计级陷阱**。若用 `Long` 或在运行时才解析，会在首次截图时 `NumberFormatException`，而日志会指向截图而不是 id 解析。

**注意**：编译成功 ≠ 功能测试通过。以上只证明「源码树在 CI 可编译出 APK、解析器单元测试通过」。

### 2.4 单元测试覆盖（真实运行的 13 个用例）

`DisplayOutputParsersTest`，输入全部是**真机逐字捕获**的输出：

- SF id 解析（含 uint64 溢出回归、非溢出主屏 id）
- logical display 解析（type / uniqueId / layerStack 不变量）
- `--ids-only` 解析
- screencap raw 头（16 字节 / 12 字节 / 太短 / 非 RGBA）
- 实测 payload 尺寸 3686416 字节
- activities 按 display 分组
