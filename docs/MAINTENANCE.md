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

## v1.3.0 授权位置管理

当前开发1.3.0／code16本地候选，沿用工程路径与原签名，公开正式版仍为1.1.1；v1.2.0五份交付及旧检查保留。新功能仅管理本工具已有SAF持久授权，不提供文件删除、重命名或目录清理的新路径，不新增权限、外部代码或运行依赖。

`GrantSelection`维护精确原始授权URI、实际read／write标记、选择集与批量结果；`FilePickerDialog`只在授权根提供左滑、菜单、详情、长按／选择、全选与移除确认，`SwipeGrantRow`处理单行横向操作。导航用document URI不能代替撤权用的原始persisted URI，不能根据人类路径、前缀、父子关系或读写想象扩大释放范围；重叠授权独立保留。

详情显示值与复制地址分开：`/内部存储/`仅为primary位置的阅读标签，`copyLocation`只对系统外部存储提供者的primary原URI生成`/storage/emulated/0/…`；其他提供者或无法解析的标识回退为精确原content URI，不猜测真实磁盘路径。最终原生测试只读验证映射值，不执行剪贴板写入。

确认弹窗列出完整目标与实际权限，后台执行前重查系统授权；变化停止剩余操作，逐项反馈成功、失败、已不存在与未处理，并重读列表核对。解除本工具权限只调用精确授权释放接口，不进入删除／改名文件接口；本工具的原输入、草稿、核对和成功／恢复记录不随撤权清空。另一授权仍提供访问时不能误报为文件未删或撤权失效。

Main以正在读取／生成／保存／分享识别／系统选择和待PDF／享做／清理恢复任务为门禁，撤权确认时重新检查，避免列表开启后任务状态变化绕过保护。任务期间继续允许原浏览／取消；后续文件访问继续实际核对权限，权限失效应要求重选或重新授权，不伪造仍可写、不清数据解决。

已确认撤权的后台工作有独立静态计数，列表关闭不等于任务取消；Main读取／生成／分享／安装入口在计数归零前继续受保护，完成后按确切结果更新。不能仅依赖对话框仍显示来保护已开始的撤权，也不能因为返回而清空输入／草稿或自动勾选核对。

源码审查发现两处需要守住的真实边界：计数增加后的刷新回调和异步任务提交必须进入异常回收范围，不能回调抛错便泄漏计数；列表刷新后旧行必须核对当前RootItem／Entry身份，不能将过期行URI直接交给已变化的选择集。已精准修复并源码复核；设备故障注入复现未执行，不能只因源码审查关闭就报告该层实测通过。

新测试覆盖原URI与导航URI分离、read／write精确范围、全选与刷新、快照变化、部分失败和只读／失效位置。最终build06离线主APK／原生组件／lint／JUnit共71任务成功，19秒；320项中318通过、0失败／错误、2项旧私人样本缺失跳过，新授权模型13项全部通过，lint0错误／25警告，原签名、对齐与元数据通过。旧23份单测及82份受保护源文件、图标不变。

目标API31首轮build04已安装，窗口／合成工作区检查通过，授权列表横／纵实际MotionEvent、详情及单项确认取消已执行；整轮因测试误读CompoundButton.performClick返回值失败。CompoundButton先toggle并通知OnCheckedChangeListener，再返回super是否有普通OnClickListener，false不能用于判定未切换。只修改复选框测试为checked与同一精确URI选择1→0→1→0断言，普通Button的返回值要求保留，不用产品增加空监听制造测试通过。

最终候选含复制地址修正，与首轮安装包不同；一次ADB断连在新安装前失败，用户重连解锁后，build06主包与测试组件均成功安装，实际安装哈希绑定最终候选。完整Startup／Window／Workflow／Grant runner通过：2个真实授权根的横／纵MotionEvent、Back、详情／精确URI、复制地址只读映射、长按／复选1→0→1→0、全选／清空、单项／批量确认取消、选择退出与固定取消。精确原URI及read／write flags、原输入在各阶段与finally不变；没有实际撤权、剪贴板或文档操作。首轮API误判与断连原记录保留。

本轮原UID、首次安装时间、四组既有偏好原键值哈希保持，USB临时亮屏2已恢复原0并读回；只卸载`cn.local.pdfbookmarks.test`并核实不存在，主包和数据保留。最终自身picker两张渲染图已目视核对，另有实际Main冷启动Status ok／COLD，当前进程无FATAL／crash缓冲为空，未清设备日志。菜单刷新、根列表大字号／方向、忙碌门禁、真实撤权／重授权／提供者故障及用户文档／正式笔记事务仍未实机执行，按[D61–D68](DEVICE_ACCEPTANCE.md#v130-已授权位置管理验收)保留分项范围，不扩写成全设备验收通过。

滑动容器只消费横向拖动，没有点击动作；可聚焦的内容、单行菜单和选择控件提供同等操作。仅对`SwipeGrantRow.onTouchEvent`的`ClickableViewAccessibility`误报作有说明的方法级标注，不添加空`performClick`掩盖问题，不改变手势，也不全局忽略lint。

## v1.2.0 草稿、分享和界面维护

当前开发目标为1.2.0／code15本地候选；正式下载仍为1.1.1。版本更新不自动授权推标签或公开发行，构建及真实设备结果分开记录，见[D52–D60](DEVICE_ACCEPTANCE.md#v120-草稿分享与交互验收)。本轮不清理宿主文件，不添加运行依赖，不修改签名、权限或旧事务默认值。

`ReviewDraft`保存不可变的核对快照，`DraftStore`只写应用内部`review-draft-v1.json`，由主界面安排后台串行IO与保存时机。使用AtomicFile并回读校验；损坏或写入失败有可报告原因，不把异常当“恢复成功”。恢复必须重新读取原文档，依次核对两个URI、SHA-256、重新解析的原树及实际页数；仅恢复已应用页码和界面状态，未预览页保持0。“已核对”、覆盖／删除选项、授权与写入任务不进入草稿；冷启动重新核对，旧pending事务先按原门禁处理。草稿恢复不能清除、覆盖或自动继续PDF／目录／清理恢复记录。

`TocDiagnostics`仅负责只读汇总，严格输入仍以`TocParser`为准；错误位置和提示不能变成自动修复、宽松导入或静默去重。`DirectoryDiff`与`CatalogDiff`只读比较，UI分别标明PDF内部书签和享做目录，保留与全部替换的结果不同；确认前展示不代替正式写入前重新核对文件身份、权限及页映射。

旧PDF的`PdfBookmarks.inspect`允许null／空书签标题和未解析目的地，`DirectoryDiff`必须兼容这个真实生产输入边界；仅显示层给未命名提示，匹配键分别编码null、空串与真实同名字串，未知页不得改成1。新增2项JUnit与2组合成旧PDF生产inspect链回归通过；不为修复摘要崩溃去放宽新JSON标题或写入规则。

`SharedImport`处理分享的明确PDF／JSON内容URI，Activity在用户确认后才改变输入，保留其他输入选择入口。不接受网络URL、file路径或含多个同类型文档的模糊组合；临时只读授权不等于持久授权，也不等于tree创建／改名授权。不得让分享Intent绕过核对或触发生成、替换、目录同步和删除。来源权限失效应提示重选，不清应用数据。

界面保持文件、核对、结果三个区域及底部主操作；概要、完整路径、差异和行详情按需展开。返回先处理当前输入或视图层次，真正写入阶段保持原恢复保护；读取取消仍使用旧回调令牌隔离。结果自动定位和文件区收起不能隐藏待恢复入口、保存重试或唯一清理入口，也不能改变任何状态判定。

方法参考Android官方[状态恢复](https://developer.android.com/topic/libraries/architecture/saving-states)与[接收分享](https://developer.android.com/training/sharing/receive)，按既有Gson与原生Views实现；没有新增外部代码或运行依赖。应分别验证纯模型、持久层、进程重启、临时授权和实际触摸，不能用序列化往返或编译通过代替设备端到端结果。

本轮离线构建含主APK、原生测试组件、lint与JUnit共71任务成功，307项测试中305通过、2项旧私人样本缺失跳过；新增41项全部通过，lint0错误／25警告。另存完成后继续应用新页码会重新建立核对草稿；同路径替换改变原PDF内容基准后，应重新读取，再开始下一轮核对，不能把旧SHA草稿套到新文件。

真实样本检查单列为宿主集成：127页／47条（12根／35子）目录诊断通过，完整、整体偏移和草稿分段恢复三输出均经独立pypdf核对全部页对象、尺寸／旋转、内容／文本与元数据，原输入哈希不变。临时Android资源／日志stub仅用于宿主引擎执行，不冒充Activity、AtomicFile或文件提供者设备结果；旧70条私人样本仍缺失，2项跳过保留原状态。

目标API31本轮原生设备回归另有实际证据：保留数据覆盖升级、Activity启动／重建、系统栏6次、外观3组合、横竖方向2／2、两文件选择及空路径取消通过；合成核对草稿、行详情／返回和8项DraftStore／AtomicFile检查通过，原方向及外观／核对偏好恢复。首测发现Dialog.dismiss已发生但OnDismissListener尚未清引用，`ReviewWorkspace.handleBack`／`hasModalUi`只判非null会把关闭状态判错；修复为读取isShowing，保留原测试断言，再次原生复测通过。此设备测试未选取、读取或写入用户文档，不等于Main真实书籍进程退出、外部分享、分屏／系统字体／触控笔或笔记事务已验。

最终已安装APK与候选摘要一致，原UID／首次安装时间和四组既有偏好所有原键值哈希保持；临时亮屏值回读恢复为原值，仅卸载本次原生测试组件并核实不存在，主包及应用数据保留。当前主界面截图与无FATAL日志已核对；额外启动命令只送到已运行实例，不额外算一次冷启动。测试过程及一次失败／修复／重测证据均留在工作记录，不能删除失败证据来获得“全通过”外观。

## v1.1.1 启动与系统栏维护

当前正式版为 1.1.1／code14，原签名五附件已公开核验，标签固定221c18dc64053b2f0654f85e734965962384bac8。MRO-W00／API31 实际旧版日志已确认 `PhoneWindow.getInsetsController` 直接访问尚未创建的 DecorView，会在 `MainActivity.onCreate` 中使应用退出；不能因为 SDK 版本门禁正确，就假定窗口已完成初始化。

系统栏更新需安排在内容视图建立之后。先获取 DecorView，再在其视图队列中读取控制器；控制器尚不可用时不能解引用。外观或配置变化复用同一路径，保持 API26–29 的旧系统栏处理与任务状态。不得用广泛捕获全部启动异常、清应用数据或删旧配置来掩盖生命周期错误。修复未改 PDF、目录、备份及更新事务。

启动验收须覆盖首次打开、保留数据覆盖升级、返回前台及外观变化。真实设备启动结果、宿主窗口生命周期回归和构建／单测结果分别记录，不能以编译通过或读取旧日志代替新版启动成功，见[D49–D51](DEVICE_ACCEPTANCE.md#v111-启动修复验收)和[VALIDATION](../VALIDATION.md)。

本轮已获用户同意并在目标API31设备原签名覆盖安装1.1.1，原应用身份／首次安装时间保持，主界面及JSON文件选择页已显示，当前进程持续存活且未再记录致命异常。此观察不覆盖重复冷启动、旧授权／恢复资料访问、主题三模式或窗口重建；临时测试组件安装被设备拒绝，未运行Instrumentation，核实测试包不存在，未更改安全设置。后续按实际范围补验，不回填全部设备矩阵为通过。

## v1.1.0 核对与界面维护

以下能力自 1.1.0／code13 引入，并在 1.1.1 正式版保留；1.1.0 的已确认启动问题见上一节。搜索、折叠和行选择使用原条目索引，生成／同步必须读取完整的 `PageAdjustments.rows()`，不能误用过滤后的可见列表。页码试算基于导入后的实际目标页，每次从同一基准计算；试算未明确应用、参数修改或校验失败时不改已应用结果。应用及恢复映射须使旧核对和生成结果失效，来源 JSON 不改写。

外观设置使用独立的 `ui_appearance`，左右／上下分栏比例使用 `review_ui`；旧 Activity 任务键、`app_updates`、目录模式和 JSON 删除默认值保持。主题、字号、间距和窗口变化直接更新当前视图，不调用 Activity 重建来丢弃运行中任务。v1.1.1及以前没有持久核对草稿，进程死亡只依原事务记录恢复；本轮v1.2.0候选新增的独立草稿规则见上一节。新增弹窗必须取当前主题context，并同步文字、控件、错误、选中与禁用颜色，不能只改变页面背景。

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

v1.2.0候选补充：Git worktree的`.git`可能是记录宿主绝对路径的指针文件，不只是目录。`package-release.ps1`及`verify-release.ps1`均明确排除／拒绝源包中的`.git`文件，合成指针纳入打包回归；不应把工作树控制路径公开进源码附件，也不为打包删除真实`.git`。

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

已发布且逐字节核验可恢复的发行／源码副本可在本次明确清理授权内删除；默认保留当前Git、唯一输入／诊断、私钥与依赖，恢复索引不能代替这些原件。本地文件默认保留，工作完成不自动清理；只有用户明确请求清理或肯定答复本次具体清理询问后才执行。获准清理时先写读回交接，并展示完整绝对路径清单及重建依据；仅逐项删除已确认无依赖的可重建临时物，再移除已空目录。保留源码、历史/最终交付、原输入、诊断证据、私钥与未来构建依赖。平板已安装程序不依赖电脑的fixture目录；应用内按用户选项处理本次关联旧件的功能不受宿主保留规则影响。
