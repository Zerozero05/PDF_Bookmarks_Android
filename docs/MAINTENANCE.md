# 更新与维护

适用当前PDF＋JSON版。首次交接或续接先读根WORK_CHECKPOINT.md及最新工作交接，再读[现行需求](REQUIREMENTS.md)与VALIDATION.md；不能用旧无障碍工程替代本工程。

## 文档与更新日志

每次修改仓库都同步维护 [README 更新日志](../README.md#更新日志) 与 [完整记录](../CHANGELOG.md)，覆盖功能、修复、兼容性、来源及独立文档更新。README 保留全部已记录更新，采用“版本／更新｜日期｜主要变更”表格，最新在前；日期统一 `YYYY-MM-DD`，每条以“1、新增／修复／调整／文档：具体变化”的短句编号，不写空泛的“优化体验”。

每次摘要末尾只放一个对应完整章节的“本次完整记录”链接，表后保留“查看全部更新记录”。详细变更、兼容性、升级方式、验证结果与局限放在 CHANGELOG 或已有专题说明中，避免多处重复整段内容；每次更新同时精简重复介绍并核对阅读顺序、链接、默认值与数据保护边界，不能为缩短篇幅删除必要操作或旧历史。

纯文档更新用日期单列，不增加 VERSION／versionCode，不重打包、不移动旧标签或替换发行附件。功能版本的开发、试用、正式发布状态如实区分，准备材料与提交不能代替本次发布确认。

更新日志表格、编号短句与最新在前的展示方法参考 [LinkSwift README](https://github.com/hmjz100/LinkSwift/blob/dev/README.md#更新日志)；本仓库补充逐次详情与完整历史入口，沿用既有 CHANGELOG。仅参考文档组织方式，未引入其程序代码或依赖。

## 代码与兼容性

MainActivity负责选文件、任务编排和用户确认；ReviewWorkspace负责目录搜索／折叠、抽查与页码调整入口；DirectoryViewModel保持条目身份和显示过滤，PageAdjustments保持完整页码工作副本；ZoomImageView和ResizablePreviewLayout负责缩放及分栏；UiAppearance负责独立显示偏好；TaskOutcome只记录分项结果；TocParser负责两种JSON和映射；PdfBookmarks负责增量写入/结果校验；PathDocuments/StagedDocuments负责真实SAF身份与授权；StagedReplacement负责可恢复改名；BackupCleanup只负责用户确认后的旧备份门禁。精确修改对应模块，不重写未改变的引擎。

保持cn.local.pdfbookmarks、既有SharedPreferences键、files/pdf-backups目录及两种输入格式兼容。新增字段缺失必须保守处理：旧版没有lastReplacement关联时不能猜删备份。升级不清除应用数据，不自动执行pending任务，不自动删除内部备份。v0.1.8仅在用户确认完整清单后允许统一清理本次关联内部备份；清理后不能恢复。pendingCleanup保存授权快照，未完成时阻止新写入；可先保存原快照到files/cleanup-records后结束清理并保留所有剩余旧件，不能丢弃记录来获得干净状态。未来如需迁移，先保存原值，失败保留原数据，并补真实兼容回归。

## v1.1.0 核对与界面维护

当前候选为 1.1.0／code13，尚未发布；公开正式版仍为 v1.0.0。搜索、折叠和行选择使用原条目索引，生成／同步必须读取完整的 `PageAdjustments.rows()`，不能误用过滤后的可见列表。页码试算基于导入后的实际目标页，每次从同一基准计算；试算未明确应用、参数修改或校验失败时不改已应用结果。应用及恢复映射须使旧核对和生成结果失效，来源 JSON 不改写。

外观设置使用独立的 `ui_appearance`，左右／上下分栏比例使用 `review_ui`；旧 Activity 任务键、`app_updates`、目录模式和 JSON 删除默认值保持。主题、字号、间距和窗口变化直接更新当前视图，不调用 Activity 重建来丢弃运行中任务；进程死亡恢复仍按原持久事务门禁处理，搜索／页码工作副本不宣称持久草稿。新增弹窗必须取当前主题 context，并同步文字、控件、错误、选中与禁用颜色，不能只改变页面背景。

底部主操作的文字和动作须一致；已生成待保存结果走保存入口，不重复生成。任务阶段由真实处理点触发，无法量化时保持不定进度；结果卡片分别报告生成、保存、享做同步、JSON 和清理状态，不把 PDF 成功覆盖其他部分失败，保留实际保存位置与可复制原因。结果文案不改变事务检查、授权、恢复或删除的判定。

方法参考 Android 官方[自适应布局](https://developer.android.com/develop/ui/views/layout/canonical-layouts)、[拖动与缩放手势](https://developer.android.com/develop/ui/views/touch-and-input/gestures/scale)及[按文件名分离 SharedPreferences](https://developer.android.com/training/data-storage/shared-preferences)。本项目用现有原生 Views 实现，仅借鉴布局／API 方法，未复制外部代码、未迁移到 Compose、未添加运行依赖。新图标为经用户同意的内置 imagegen 生成资源 `drawable-nodpi/ic_book_v11.png`，旧 `ic_book.xml` 保留；`mipmap-anydpi/ic_launcher.xml` 按 Android 官方[自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)方法组合前景／背景，由系统启动器遮罩显示，不改写生成 PNG。

新增测试应覆盖搜索／折叠下完整目录写入、映射不累加和失败不应用、窗口及显示切换保留任务，以及部分成功结果。UI 手势、键盘／触控笔、软键盘、深浅弹窗、覆盖升级和文件提供者仍须按[D33–D48](DEVICE_ACCEPTANCE.md#v110-核对与界面验收)真机核对；构建、JVM 状态测试和静态颜色计算分别证明，不能替代触摸截图或设备结果。v1.1.0 本轮没有真机连接，相关项如实待测，实际电脑检查由[VALIDATION](../VALIDATION.md)记录。

## 构建、版本及发布

固定JDK17、SDK35/build-tools35.0.0、Gradle8.9及Wrapper SHA校验，依赖版本见app/build.gradle。VERSION是显示版本的唯一来源，Gradle versionName和界面帮助自动读取；每次覆盖升级还需把app/build.gradle的versionCode增加1。先完成本机回归，再提交远端。

标准开发检查：

```powershell
.\scripts\build.ps1 -ToolingRoot '<已有工具目录>'
```

工具目录结构为jdk/（其下JDK目录）、sdk/、android-user-home/和gradle-home/。换电脑可自行安装这些工具并显式指定，不把它们放进源码或公开仓库；Gradle Wrapper锁定分发包哈希。真实JSON回归额外传-InputJson与-ActualPageCount，实际总页数由PDF读取；无真实输入时相应测试可跳过，不能写成通过。

正式准备本地交付时使用一次性的新目录：

```powershell
.\scripts\release.ps1 -ToolingRoot '<已有工具目录>' -OutputDirectory '<工程外的新版本交付目录>'
```

该入口依次运行assembleDebug/lintDebug/testDebugUnitTest，成功才打包，再调用verify-release.ps1逐项核对源码ZIP/必需依赖/许可/交付SHA。单独package-release.ps1适用于已完成检查的构建，之后可单独运行verify-release.ps1 -OutputDirectory '<交付目录>'复核。它在写交付前核对VERSION、versionCode、构建元数据、APK实际包名/版本、签名指纹及zipalign；任何不符停止。已存在的交付目录一律拒绝，不能覆盖同版本旧包；失败可能保留未完成目录，先核对而不是自动强制清理。

命名为PDF_Bookmarks_v{VERSION}_android.apk、PDF_Bookmarks_v{VERSION}_android_source.zip及SHA256SUMS.txt/独立.sha256。源包排除PDF、EXE、工作/诊断资料、构建物和私钥，但保留示例JSON、所有可编辑源文件及许可证。已交付v0.1.0–v0.1.3使用旧名，留作历史，不重命名或替换。

发布回归：

```powershell
.\scripts\tests\package-release.tests.ps1 -TestDirectory '<全新的测试证据目录>' -ToolingRoot '<已有工具目录>'
```

覆盖历史目录/源码目录拒绝、旧元数据、实际APK错版本、未签名APK、错升级签名、正常打包/排除项/SHA和重复打包。测试只操作合成fixture与只读APK副本，结果存results.json，临时内容不自动删除。业务回归由build.ps1运行。

## 升级签名必须保留

SIGNING_CERTIFICATE.sha256只保存公开证书指纹；私钥不随源码或ZIP分发。现用签名沿用已有本地debug.keystore，指纹405036e63efe1bd2f106e887801f6deec0b1e496e564dc9b4d7e2a56d1017fca。工具目录中的android-user-home/debug.keystore是持续更新依赖，不能当缓存删除；由用户保留安全离线副本。换电脑先恢复同一私钥，不能临时生成新密钥再以旧应用名发布。发布门禁会拒绝不同证书。

用户已确认验收并授权将现有v0.1.9晋级GitHub正式版；本次沿用既有个人升级签名，不重签、不重建APK。GitHub正式版状态不改变证书类型；现有debug证书不是应用商店生产签名。Android更新涉及应用签名，丢失自己管理的私钥会失去原应用的更新能力。未来若上架应用商店或迁移签名，须另行制定已有用户的数据与签名兼容方案，不能靠卸载清数据解决。[Android签名说明](https://developer.android.com/studio/publish/app-signing)

GitHub Actions使用runner临时测试签名，只证明源码构建/回归，不作为本机应用的覆盖更新包。不得把私钥明文提交到工作流、仓库或公开日志；远端签名发布需另行明确授权与凭据配置。

## 自动更新维护

v1.0.0/code12正式版加入独立更新功能，使用当前五附件中的APK和SHA256SUMS，不新增更新元数据。以后同时递增VERSION与versionCode，沿用原签名和包名，发布完整且一致的五附件并设为Latest正式版。发布前仍必须取得对具体候选的明确确认；开发、草稿PR或CI不自动公开发行。

自动/手动检查、系统安装授权、下载缓存与签名核验详见[AUTO_UPDATE](AUTO_UPDATE.md)。INTERNET和REQUEST_INSTALL_PACKAGES仅为更新所需；PDF仍可离线处理。Android安装交给系统，不采用PC的EXE自替换/配置回滚，也不回收其他任务缓存。新增设置使用app_updates，旧任务配置不迁移。原签名密钥及SIGNING_CERTIFICATE.sha256保持；实际安装流程须按设备矩阵验证。

## 设备验收、GitHub与清理

按[DEVICE_ACCEPTANCE](DEVICE_ACCEPTANCE.md)填写真实结果，安装/授权/文件提供者/享做刷新必须与电脑测试分开。发生错误先保留应用数据、导出原件备份，记录选定路径、版本、操作和具体提示；不要反复盲点替换或删除。

公开仓库与发行约定见[GITHUB_PLAN](GITHUB_PLAN.md)，v0.1.9已获用户总体设备验收及正式版授权。首次发布的5附件和标签保留；v1.0.0已在用户明确确认后合并PR #3并正式发行，标签固定为候选ce80688f7d1d2f411f5ce7368bb64b04f8231a0a，五附件已公开下载核验；不覆盖既有附件或移动旧标签。最新说明入口是[USER_GUIDE](USER_GUIDE.md)；旧源码ZIP中的发布时说明作为历史保留。提交只包含审核过的源文件与维护配置；verification/和work/等已忽略，不上传用户书籍、日志、私钥或本机路径配置。CI失败先处理再推进发行。

已采用A1/A2/A3：现有Android CI在提交和PR上构建、lint及单元测试，成功或失败均尝试保留14天诊断；CI附件使用runner临时签名，不能作为覆盖升级发行包。发布先核验本地APK及同提交CI，再上传草稿并核对名称、大小、SHA-256与资产集合，公开后读回并实际下载核验。Dependabot按月检查Gradle和GitHub Actions，分别分组，普通更新PR上限分别为2和1；更新须人工审查兼容性、许可与回归，不自动合并或发布。配置提交不代表机器人已成功完成首次检查，以实际运行记录为准。

本地文件默认保留，工作完成不自动清理；只有用户明确请求清理或肯定答复本次具体清理询问后才执行。获准清理时先写读回交接，并展示完整绝对路径清单及重建依据；仅逐项删除已确认无依赖的可重建临时物，再移除已空目录。保留源码、历史/最终交付、原输入、诊断证据、私钥与未来构建依赖。平板已安装程序不依赖电脑的fixture目录；应用内按用户选项处理本次关联旧件的功能不受宿主保留规则影响。
