# PDF 写入引擎验证

本目录验证生产 `app/src/main/java/cn/local/pdfbookmarks/PdfBookmarks.java` 与同一份官方 PdfBox-Android `2.0.27.0` AAR，不替换 PDF 解析器或写入器。测试用 `android.util.Log`、`Context`、`AssetManager` 仅连接日志与从 AAR 解出的真实资源；`stubs/` 位于生产 sourceSets 之外，不打进 APK。不验证 Android 文件选择器、PdfRenderer、安装流程或平板运行，也不把宿主结果称为实机通过。

引擎默认拒绝写入已有书签的 PDF，界面据此跳过；只有明确 `replaceExisting=true` 才替换完整树。原 PDF 始终只读，输出使用 CREATE_NEW，新副本必须通过书签文字/层级/物理页和页数重读、完整原字节前缀 SHA-256、原件未变化检查。拒绝加密文件、签名、空签名字段及非零 SigFlags。外链/失效旧目的地只在预览表示为页 0，仍计入已有书签并触发默认 skip。

## 重建和运行

在完整本地工作区，从项目根执行：

```powershell
& ./testengine/run.ps1 `
  -InputPdf '<只读原PDF的实际路径>' `
  -InputJson '<只读目录JSON的实际路径>'
```

这两个实际文件只读，既不复制到 fixture，也不打包进源码。其他设备可省略两项参数，只跑合成 PDF；或同时指定另一组输入，但当前真实书断言针对 434 页、70 条目录。

脚本默认复用工作区 `tooling/jdk/` 的 JDK 17 和 Codex 主运行时含 pypdf 的 Python。其他环境指定 `-JavaHome <JDK目录> -Python <python.exe>`；需要 Python 3、pypdf。依赖从 Maven Central 下载至 `testengine/deps/` 并验证固定 SHA-256；运行只显式纳入二进制 JAR，不把 sources.jar 编进 PDF 引擎。生产仍使用 Android 的平台类及自己的 Gson/PDFBox Gradle 依赖。

每次执行在 `work/engine-<UTC时间>/` 新建隔离目录，不覆盖以前的测试结果。`generate_fixtures.py` 生成 11 份合成输入；`EngineTests.java` 执行 24 项合成场景及 3 项实际书籍场景，共 27 项。合成场景包括 Unicode/Emoji/三级目录/末页、完整替换、skip、原件与现有输出保护、无效层级/页码/标题、外链/失效旧目标、加密/签名/SigFlags、空页和循环目录。签名 fixture 只包含用于拒绝测试的标记，并非密码学有效签名。

`verify_outputs.py` 用独立 pypdf 逐项比较新目录，并检查全部页面对象编号、页面框、旋转、解码内容流、提取文字、元数据、源 SHA-256 和输出原字节前缀 SHA-256。这是实文件检查，不是模拟写入。

## 可保留的验证记录

- `verification/pdf-engine-host.log`：最近一次 27 项宿主结果。
- `verification/pdf-engine-independent.json`、`pdf-engine-pypdf.log`：pypdf 独立结果、输入/输出完整路径及散列。
- `verification/pdf-render-compare.json`：Poppler 72 DPI 渲染实际书籍第 12、429 页的像素对比。
- `verification/pdf-two-runs-compare.json`：两轮增量输出的完整散列，以及仅 trailer `/ID` 数组不同的逐字节比较结果。

实际原件 153,354,101 字节，434 页；源 SHA-256 为 `3e737790f88c8d72d8338cd336adf481d243a98011710717ac27ab9ba92b243a`。两轮生成副本均为 153,366,757 字节、70 条目录。直接比较两轮新增的 12,656 字节，确认仅 trailer `/ID` 数组不同；原字节前缀、页面和目录相同。最终副本和散列以独立 JSON 报告为准。第 12、429 页原/最新输出像素一致；Poppler 对两份文件均报相同的原有 graphics-state 提示，渲染成功。

## 依赖、来源和后续清理边界

官方引擎发布：[PdfBox-Android v2.0.27.0](https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0)，Apache-2.0，API 19+。AAR 的 BouncyCastle 1.72 传递依赖无需另加 Gradle 配置。测试依赖 URL 和固定 SHA-256 全部保留在 `run.ps1`；PDFBox/BC 的摘要还核对了 Maven Central 发布的 SHA-256，Gson 核对发布的 SHA-1 后固定本地 SHA-256。

本轮一次性调查还在 `deps/` 留下 PdfBox-Android/Gson 的 sources.jar 以及 Apache PDFBox 2.0.27 JAR；它们不进入宿主运行或 APK。调查时如需重建，可从 Maven Central 同坐标获取 `-sources.jar`，Apache JAR 坐标为 `org.apache.pdfbox:pdfbox:2.0.27`。

后续保留引擎生产源码、`EngineTests.java`、三个平台适配源文件、两个 Python 脚本、`run.ps1`、本 README 及 verification 报告/日志。`testengine/build/`、`testengine/deps/` 和 `work/` 的合成输入、增量测试输出、渲染 PNG、临时目录均可由保留脚本、原输入及已记录 Poppler 参数重建；原输入不在这些目录中。必须先保存并读回项目工作交接、核对最终 APK/源文件/工具链仍可用、确认无进程使用，再由根任务按本轮实际路径清理。此处只列候选，不声称清理已执行。
