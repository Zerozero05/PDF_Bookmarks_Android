# Android 验证记录

<a id="formal-v142-2026-10-27"></a>
## v1.4.2 正式发行 — 2026-10-27

用户已明确确认本次正式发布和发布后清理。Release424770647已公开为Latest正式版，标题仅v1.4.2，draft=false、prerelease=false；标签固定批准候选`c4c41facdb0e79a04399732aa6a004c32f3884da`，PR #7合并`1869e610a8d4680ce9fd2a5084049b5754c7ba16`，合并tree与候选一致。候选push40399945121、PR40400199952、main40401004716及tag40401564961的CI均success。沿用已验证的原签名APK，不重建、不重签；五附件在草稿与公开阶段均实际下载核验；无凭据公开GET的长度、SHA及服务端摘要全部匹配批准资产，独立sha256与总清单一致。公开tag源码与发行源码ZIP的170／170路径和字节逐项一致，旧v1.4.0发行正文、5资产ID／名称／大小／摘要／时间均保持。

APK为11,021,829字节，SHA-256 `daf552b8178e3360ee6723f5cd086bcfc2caf42d8c2af8a248792091811147b5`；源码ZIP为1,628,106字节，SHA-256 `930cd6bdf38be4cd203a3439fe96f829c6d64b8a1b37eb3942ba0c37fff115b6`，共170项文件，其中11份完整许可。标签对象为`db686dfdc988acc7ca71bc1bdb514155d98c5620`，peeled提交为上述批准候选；标签和源码ZIP保留候选时说明，当前正式用法以main为准。本次只同步正式状态，不移动标签或替换资产。发布不把下述未测范围变成通过。

| 验证对象 | 实际结果与边界 |
| --- | --- |
| 本机最终构建 | 394项中391通过、3旧私有fixture缺失跳过，0失败／错误；lint0错误／23警告。原输出、事务故障保护、父身份、输入提交及分享边界测试保持 |
| 正式CI | [候选push](https://github.com/Zerozero05/PDF_Bookmarks_Android/actions/runs/40399945121)、[PR](https://github.com/Zerozero05/PDF_Bookmarks_Android/actions/runs/40400199952)、[main](https://github.com/Zerozero05/PDF_Bookmarks_Android/actions/runs/40401004716)及[tag](https://github.com/Zerozero05/PDF_Bookmarks_Android/actions/runs/40401564961)全部success；CI临时签名测试APK不作为覆盖升级附件 |
| 独立跨UID与保存链 | 336项动态分享断言、来源消失恢复提示、VerifiedPdf、Main两例合成生成／取消另存／续存／源与副本精确清理通过；原合成PDF保持。两输出另以pypdf内存独立核对5页、3节点、层级1／2／1、目标PDF页1／2／3及SHA |
| 真正进程重启 | force-stop、不同PID、来源读权撤销后仍恢复持久输入及v1草稿3行，核对不自动勾选 |
| 真实资料只读 | 目标238页PDF／39条JSON，两模式各3轮prepare及差异通过；已有授权复用、失效源恢复提示和不请求私有父目录通过。原文件／输入哈希、4组偏好、授权、草稿和输入区在只读检查前后保持 |
| 资料保护读回 | 指定笔记资源目录根层93份原文件名称与SHA一致，不宣称递归覆盖全部笔迹附件；全阶段仅输入选择组偏好因用户实际QQ导入改变，其余3组保持，已接受JSON副本及身份／哈希绑定仍一致，未删用户JSON |
| 性能范围 | 同一本PDF及JSON，本轮优化前候选与最终候选的只读预检中位：合并11270→3532ms（-68.66%），替换11852→3644ms（-69.25%）；列举14→4，每轮仍10流／68221304B。非旧v1.4.1 APK比较，不代表实际生成／替换全程同幅提升；50MiB合成受控模型另见完整日志 |

目标为HUAWEI MatePad Pro 12.2／MRO-W00／HarmonyOS4.2.0／API31；保留原包名、升级签名与应用数据，已装主APK与本次发行APK的SHA一致。本轮没有对用户笔记执行PDF替换或享做目录写入，也没有新建备份或删除用户资料；200MiB、其他提供者、云同步、掉电及更多设备故障未验。无凭据公开附件、标签源码及旧发行版保持的独立核验均通过，旧正式版的历史对象及有限验收保持；完整功能与方法来源见[更新记录](CHANGELOG.md#v1-4-2)。

<a id="formal-v140-2026-10-24"></a>
## v1.4.0 正式发行 — 2026-10-24

用户已明确确认本次正式发布和发布后清理。Release422299598为Latest正式v1.4.0，标题仅版本号；标签固定b1055a3c8a8b8906c61a0957477edd1b0d507a4d，PR #6合并d5aae93fe7c4359c7b93ba98185456f46f4232b4，合并tree与批准候选完全相同。候选push40022181653、PR40022189273及main40022461724检查均success。五原签名附件已实际公开下载，名称、大小和SHA-256逐项匹配批准；源码ZIP145项与公开标签的全部路径／字节相同，11份许可完整。APK保持10,975,471字节，SHA-256 `68312df2ba849ecbb274195fccf9179623ba232914598ba84bf3d34d6efdb994`，不重建或重签。源码ZIP SHA-256 `537811de66aed854c351b721c77b5c53a967448860374cf19c910c52312d28c9`。原v1.3.0正文、标签及五附件保持；后续main仅维护正式说明，不移动本标签或替换资产。

复用本机329通过／2原JSON样本缺失跳过、lint0错误25既有警告及目标API31最终native05记录；Main私有缓存5页／3条目录的预检、生成、取消与继续保存已验证，原数据／授权／偏好保持，test移除、USB恢复0。CI不提供私有fixture，三个真实样本案例按原规则跳过，不与本机恢复混合页样本后的两跳过混淆。真实SAF／享做正向、目录续办冷重启、本轮根手势等仍未完整验收，公开发行不将这些边界改为通过。

<a id="candidate-v140-2026-10-24"></a>
## v1.4.0 流程与界面本地试用 — 2026-10-24

本轮在原v1.3.0主分支基础上实施用户已确认的12项深度流程与界面优化，VERSION1.4.0／code17，未公开发布。原包名、升级签名、min26／target35、权限、依赖、两种JSON、草稿及事务格式保持；PDF引擎和原安全替换／目录同步／清理／恢复核心不因UI调整替换。保留所有旧入口和断言，不新增OCR、完整编辑、批量或云端，不下载新依赖、复制第三方代码、推送标签或清理本机文件。

| 检查 | 当前真实结果与边界 |
| --- | --- |
| 首轮构建 | build-01离线71项任务成功；新增原生核对组件随后接入，最终集成以后面的build-03为准，各轮日志保留 |
| 最终集中构建 | build-03离线71项任务成功，21秒，23执行／48已是最新；主APK、原生测试APK、JUnit与lint集中完成 |
| 最终JUnit | 恢复已保留的344页混合原生fixture后，build06全量JUnit共331项：329通过、0失败、0错误、2项原70条JSON相关样本仍缺而跳过；20任务／7秒，原源码与断言不改 |
| 最终lint | 0错误／25警告，本轮新增告警全部处理；build-02剩余两处新增SetTextI18n已资源化，既有规则与警告保持，无全局忽略或降低门禁 |
| 后续原生编译 | build-04／05各51任务、14／13秒成功；最终build07原生组件与lint共51任务／14秒成功，7执行／44已是最新，lint0错误／25警告；主APK字节保持，测试APK SHA-256为`d79388297f5ccce0ae3d8e0eb23df1dade9fb1cf1b91e821c475b3b8caec04ca`；最新JUnit以build06为准 |
| 核对区静态检查 | 差异空白检查、资源XML解析及新字符串引用核对通过；只证明静态一致，不代表Android交互通过 |
| 127页／47条派生样本 | 候选四输出独立pypdf核验通过，全部页面对象／五类页面框／旋转／解码内容／文字、元数据／XMP、完整源字节前缀及47条标题／层级／顺序／实际目标一致，固定派生输入不变 |
| 原始输入边界 | 127页／47条那组原始PDF和JSON仍未恢复，本轮只用其派生PDF和已保留完整树证据；另行恢复的344页混合快照不冒充这组原输入或缺失70条私人JSON |
| 344页混合快照恢复 | 历史manifest核验原`catalog.json.temp`／pageids／doc／PDF的SHA全部一致，pypdf实读344页；原temp原字节仅改为独立fixture所需`catalog.json`文件名，未重建JSON，也未使用内容不同的现存catalog；原混合页案例实际PASS |
| 性能说明 | 同一派生输入热身后少量宿主计时仅记录波动；PDF引擎未改，不证明UI提速、触控帧率、Android渲染或内存改善 |
| 本轮原生／设备 | MRO-W00／API31最终instrumentation-05-raw完整Bundle及runner为PASS，含Window／Workflow／Review／Workflow140和Main缓存合成file://四阶段；前两轮FAIL、第三轮PASS与第四轮输出证据不足分别保留。真实可见授权根0，根操作SKIP，固定取消及原持久URI／read-write flags／输入保持通过 |
| 目视与状态保留 | 常规／大字深色两张合成核对截图已目视，不是PDF渲染；最终Main截图无溢出。Main真实COLD启动458ms及收尾486ms／Status ok仅记观察，不证明提速；当前PID10771崩溃缓冲无FATAL。收尾后主包SHA、UID10229／首次安装2026-10-22 02:00:56／code17及四组原偏好保持，临时test独立核实不存在，USB已恢复原0并读回，未清理宿主文件 |
| 主APK身份 | 10,975,471字节，SHA-256为`68312df2ba849ecbb274195fccf9179623ba232914598ba84bf3d34d6efdb994`；apksigner v2通过，原证书SHA-256为`405036e63efe1bd2f106e887801f6deec0b1e496e564dc9b4d7e2a56d1017fca` |
| 元数据与权限 | 实际包`cn.local.pdfbookmarks`／1.4.0／code17／min26／target35，原两权限未变，原升级身份保持 |
| 源码与交付附件 | 完成打包／独立核验后由外部交接／`verify-release.json`记录结果，不提前宣称最终交付核验通过，也不在包内文档嵌入源码ZIP自身哈希；公开下载仍为v1.3.0 |

build-03当时三项因fixture未提供而跳过，不能笼统判断三个样本都已丢失。后续在保留的2026-10-08混合格式快照中找到原生`catalog.json.temp`、pageids、doc及PDF，全部SHA与当时target-copy-check manifest一致，pypdf实际读出344页。现存`catalog.json`与原temp的usn／子树不同，辅助准备脚本首轮误要求两者相同，已在写入前停止，无副作用，失败记录保留；随后明确取manifest验证的原temp原字节，只为适配原测试固定文件名复制为独立fixture的`catalog.json`，pageids／doc亦保持原字节，没有重建或修改原JSON。证据记录于本轮外部`recovered-mixed-fixture.json`、`build06-summary.json`及`test-results-build06`。

`EnjoyCatalogTest.actualMixedNativeFixturePreservesAllOriginalNodes`现已在build06实际PASS，完整331项中329通过、0失败／错误、2跳过。仍缺原70条JSON的两项为`EnjoyCatalogTest.exactFixtureMatchesVerifiedMergeAndIsIdempotent`与`TocParserTest.realUserJasminumKeepsAllSeventyRowsWithoutPageOffset`，保留原假设和断言；344页混合快照恢复不代表这两项私有输入或127页／47条原始PDF＋JSON已恢复，也不替代设备真实笔记事务。

本轮新增覆盖包括：生成／待保存／待恢复和分项结果、只读预检、有效生成物继续保存、绑定实际保存版本的目录续办、三个独立策略与情境显示、单一底部主动作、实际窗口／大字、短提示与就近帮助、真实来源索引差异定位、浏览页与目标页提示、固定分段列头／48dp删除、分组清理确认及实际保存位置。方法仅参考Android官方[自适应布局](https://developer.android.com/develop/ui/views/layout/canonical-layouts)与[无障碍应用原则](https://developer.android.com/guide/topics/ui/accessibility/apps)，未迁移框架或新增依赖。

最新实现边界：保存成功、目录失败时，当前会话快捷续办只重试目录，不重新生成／替换PDF；离开复用原ReviewDraft v1保存已调整目标页与核对进度，绑定最终PDF URI／hash。重开须重读核验JSON、最终PDF及原JSON目录树后才恢复并重新确认，危险替换模式不自动恢复，原持久journal优先。目录已成功、仅清理失败时保持目录成功；关联旧草稿到清理完成或明确保留剩余并结束清理后才结束。JSON删除失败可确认“保留JSON，结束此可选步骤”，取消不变，确认只结束该可选步骤并标记保留，不改文件／授权。实际保存名在后台查询一次并缓存，正常界面刷新不重复查询；纯诊断报告也提供直接点开的入口。最终集成编译及结果／差异等JVM检查通过；Main缓存合成file://生成／取消／继续保存已设备运行通过，目录续办仅确认范围与合成草稿capture通过，真实SAF／目录续办与冷重启恢复仍待测，不把实现说明当成全部设备验收。

本轮前两次失败证据保存在`instrumentation-01.log`和`instrumentation-02.log`：两轮Main启动／重建及6次系统栏检查完成，Window、Workflow和Review分项均PASS；Window包含3组主题／字号／间距、真实横竖2／2、PDF／JSON选择器及空路径取消，Workflow包含原合成草稿与8项DraftStore／AtomicFile检查。完整runner两轮仍为FAIL，Grant两轮NOT_RUN。首轮Workflow140在“Empty import state retained an obsolete detail action”失败，是关闭弹窗后引用等待异步回调清除的测试时序；次轮已越过该点，在“Keep JSON did not retain the source/previously saved output and clear only its deletion option”失败，是正按钮handler尚未执行即断言。保留原严格断言，只将后续检查排到真实主队列回调之后；生产主APK哈希不变，原失败日志不改写为通过。

次轮Workflow140到达JSON步骤前，合成输入缺项提示／诊断报告、两种另存取消待继续、情境显示／目录模式保持、待恢复定位、实际保存名／URI缓存展示及单一打开入口、保存选项续办、目录续办范围确认与取消、目录成功而清理失败的分项状态已越过对应断言。次轮JSON正向结束断言未通过，保留该失败；第三轮`instrumentation-03.log`完整runner为PASS，随后全部通过JSON保留结束确认／取消、生成失败返回、绑定最终PDF身份并保留调整页码的草稿capture，以及更多外观／更新／帮助入口。该capture仅为合成状态快照，不是同一任务草稿落盘或冷重启恢复；第三轮没有实际文件事务或更新请求。第四轮默认未加-r，只得到PASS stream，严格收集器拒绝计作完整证据；原日志保留，无新源码修改或新断言失败，第五轮`device/instrumentation-05-raw.log`用-r补齐完整Bundle并全部PASS。Main在自己缓存中以合成file://输入实际执行四阶段：①改JSON哈希拒绝且未生成ready／备份；②非SAF原路径替换预检拒绝；③真实生成5页／3条多级书签，拦截ACTION_CREATE_DOCUMENT取消后ready保留、SAVE AWAITING；④实际onActivityResult→exportTo→finishSave核验精确字节／完整树，SAVE SUCCESS／JSON RETAINED，独立测试草稿清除。原输入与原草稿SHA、原备份清单大小／修改时间、原偏好保持。按严格路径复制的五份合成文件在`device/main-pipeline05/verification.json`独立核验通过：pypdf5页／3条完整树，ready.pdf与saved.pdf字节相同。这不是系统SAF提供者或享做正向验收，真实替换／目录同步／清理／JSON删除及同一任务冷重启恢复仍未执行。

第三轮及最终Grant记录真实可见授权根为0，且没有精确persisted tree grant；不据此推断持久单文档授权列表为空。固定取消、原持久URI及read/write flags和Main输入不变通过；没有真实可见根，左滑、选择／详情／移除确认及选择返回等均SKIP。没有为了测试新增或撤销授权，不将旧v1.3.0两个真实根的手势结果移作本轮证明。

独立`ReviewWorkspaceSmoke`已在前两轮设备运行PASS：必要清筛选／展开祖先、重复标题的真实索引、越界／忙碌、加载／失败／全屏浏览提示、字体／窗口后进度与缩放、同方向拖动比例、分段列头／滚动／48dp删除及试算不自动应用。次轮在真实设备用UiAutomation取得常规和大字深色两张截图，已目视核对；白底预览明确标注“合成页面／非实际PDF渲染”，不能代替真实PdfRenderer或用户书籍。参数化窗口检查也不代替物理分屏、小窗、系统字号、触控笔与真实文档交互，具体范围见[D69–D80](docs/DEVICE_ACCEPTANCE.md#v140-流程与界面验收)。

Main真实冷启动先后COLD／458ms和收尾COLD／486ms／Status ok，只记录当前环境的实际观察，不作UI提速结论。最终Main截图已目视无溢出，当前PID10771崩溃缓冲无FATAL。设备收尾由`device/teardown-plan.json`先列精确测试包路径／数据目录，`teardown.log`卸载返回Success，再由`teardown-result.json`独立核实仅剩主包、测试包不存在；主包SHA、UID10229、首次安装2026-10-22 02:00:56及code17保持。四组既有偏好在三轮记录及最终`preferences-after-teardown.json`与测试前一致，USB临时亮屏已恢复原0并读回。只移除本轮临时测试组件，主程序未卸载或清数据，没有撤真实授权、改写用户文档或清理宿主文件；合成缓存与外部证据保留。

宿主对照使用旧验证留下的`complete-47.pdf`派生输入（127页，12根／35子），来源SHA-256为`79b3d808783e3264441b590a392532597d8171b16a10c82aae8ab9ca74fbbf16`。沿用已缓存PDFBox Android、Gson、BouncyCastle及既有JDK／pypdf／Poppler，依赖校验后实际调用生产inspect／write；四输出为一次热身及三次全树替换，47条目标统一+1，未授权替换仍拒绝。pypdf逐页与全树检查和来源哈希有效，不把该宿主结果写为SAF、Activity生命周期、真实笔记事务或原始PDF／JSON验收通过。

本轮README新增第20条摘要及对应完整日志，19条旧摘要和旧CHANGELOG全文保留；最终XML数量与主APK字节／SHA已读回，源码及交付附件仍须在打包后独立核验并写入外部交接记录。以下各版开发、发布及设备记录保留当时事实，其中“本轮／当前”均按对应版本理解。

<a id="formal-v130-2026-10-22"></a>
## v1.3.0 正式发行 — 2026-10-22

用户已明确确认正式发布和发布后清理。Release420586916为Latest正式版，标题仅v1.3.0；标签固定d85f96fc5f6cdf4105f12e190283613ddc6ce844，PR #5合并59189eae6594507e119aef723667b1b01c56ffef，候选push／PR及main检查均success。主分支旧换行修正通过保留历史的合并处理，合并tree与批准候选完全相同，程序和五附件不重建或重签。五个公开附件已实际下载逐项核对大小与SHA，源码ZIP142项匹配标签，11份许可完整；APK SHA256为`879e7b77f59110d413a1893f7cb1dfd842f06681ee4581832704889272500cfb`。本次包含未单独发行的v1.2.0增量；以下编号保留开发阶段的真实过程，其中当时的“未发布／下载仍为v1.1.1”以本确认记录为准。具体功能与限制保持，公开发行不代表未测设备场景自动通过。

复用下方最终build06的318通过／2缺失样本跳过、lint0错误25警告和完整本轮设备原日志；未重跑或把历史验证补写成新验收。最终包保留数据安装、精确授权与输入保持、USB恢复原0、仅移除临时test均已核实。真实撤权／重授权／提供者故障／剪贴板按钮和本轮真实文档事务仍按D61–D68明确未测。源码ZIP SHA256 `5e780143278c1f03d97aa5d14418702c461ae045580cfb09fd6bda6d010896f1`。正式说明仅修改main文档，不改变发布标签或附件。

<a id="candidate-v130-2026-10-22"></a>
## v1.3.0 已授权位置管理候选 — 2026-10-22

在已验证v1.2.0提交3310da88上增量实现用户要求的授权根左滑、红色移除、选择/全选与批量管理。VERSION1.3.0/code16，仅本地候选；沿用包名、原升级证书、min26/target35、两权限及全部依赖。原v1.2.0五附件和证据保留，不重放旧发布/清理。

- 交互：根列表左滑显详情/红色移除授权，右滑/返回收起，长按或选择进入复选，支持全选/清空/已选数及批量确认。始终有可聚焦的行内菜单和按钮，不依赖手势；子目录继续原单文件选择，不提供物理文件删除或批量处理。根隐藏原本禁用的上级按钮，详情可查看完整路径/权限及复制，列表可刷新。加载/空列表仍可固定取消。
- 权限边界：Entry导航URI与原始UriPermission分别绑定；按精确原URI和实际read/write标记释放并读回，不按解码路径推断父子，不连带其他授权。不调用文档/文件删除或改名API。确认列全部路径、原URI及标记；快照变化停止剩余，释放异常逐项报告成败，后续核验失败保留已成功事实。取消确认0释放；以后可重新授权，另一重叠授权仍可能允许访问。
- 集成保护：任务读取、分享、生成、系统选择及PDF/享做/清理待恢复时不允许撤权；已确认撤权期间有进程内计数，关闭picker后仍锁Main读取/生成/分享/更新安装，结束回调解锁。旧输入、核对、草稿和任务状态不自动清除；后台门禁读取字段具有可见性。没有新增持久schema或权限。
- 最终冻结源离线assembleDebug/assembleDebugAndroidTest/lintDebug/testDebugUnitTest成功，71任务25执行46up-to-date。320项JUnit中318通过、0失败/错误、2项旧70条私人样本缺失跳过，24份XML。新增GrantSelection13项通过：URI稳定选择/刷新/快照、父子及编码隔离、标记变化、缺失、单项异常部分成功、批次中变化/核验失败停止、空选择及非法输入。全部23旧单测源及其断言保持，旧PDF/目录/替换/清理/更新核心源码保持，不为UI重复运行仍有效的127页47条引擎输出验证。
- lint0错误25警告。手势容器的ClickableViewAccessibility使用单方法、具体说明的误报标注：真实点击导航由内容处理，每个授权动作均有可聚焦菜单、复选框或工具条替代；不补空performClick，不将滑动结束转成误打开目录，不全局忽略检查。
- 独立审查修复：启动授权操作的回调与executor提交共同保护，尚未提交时发生RuntimeException准确回退计数，已提交则worker仅finally回退；旧根行按当前模式/同Entry和RootItem身份/当前列表成员核验，刷新后的旧点击忽略。这两处按源码复核修复，未向用户设备注入故障。新增复制路径按真实externalstorage primary ID生成/storage/emulated/0/...；其他来源保留精确content URI，显示用/内部存储不作粘贴地址。新helper只读核验复制值，不触剪贴板。
- 固定主APK10,949,683字节，SHA256 `879e7b77f59110d413a1893f7cb1dfd842f06681ee4581832704889272500cfb`；独立测试APK60,826字节。原证书/v2、对齐、实际version/API与两权限已核验。测试helper只读现有授权根，模拟真实事件/复选/确认取消，绝不点移除确认positive或复制按钮，前后及finally核对精确授权URI+flags与主输入不变；存在精确tree授权却0行时FAIL，真正无根才SKIP。编译不是设备执行。
- 本轮设备已获用户明确允许保留数据覆盖安装及只读授权根回归。首轮旧构建实际启动/窗口/合成草稿检查通过，GrantPickerSmoke在复选框performClick布尔返回处失败：CompoundButton已先切换并通知，返回值仅表示是否有OnClickListener，测试误把false当作未执行。仅修测试按真实checked与同URI模型1→0→1→0核验，普通按钮原断言保持。原失败日志及后续USB断连日志保留；用户重连后最终build06主包与测试组件安装均Success，设备base.apk SHA与上方879e7b77…完全一致。
- MRO-W00/API31最终完整runner通过：实际Main启动/重建与6次系统栏、3组主题/字号/密度及真实横竖屏2/2、PDF与JSON选择和空路径取消、合成3条草稿恢复/幂等/筛选/非法页回滚/详情返回及8项隔离AtomicFile IO通过。授权管理在2项真实持久根上验证纵向与横向MotionEvent、左滑与Back收起、详情/精确URI及复制值只读映射、长按、checkbox同URI1→0→1→0、全选/清空、单项与批量确认取消、选择Back及固定取消。移除确认均取消；精确授权URI+read/write标记及主输入在前后/finally一致，没有真实撤权、访问剪贴板或处理文档。两张最终自身picker渲染图已目视：红色移除和全选2项布局正常。
- 设备升级保留UID10229及firstInstallTime，四组原偏好键值保持。临时USB亮屏2已恢复原0并读回；仅卸载临时cn.local.pdfbookmarks.test，核对不存在，主包及数据保留。收尾后另一次实际冷启动Status ok/LaunchState COLD，当前PID崩溃缓冲无FATAL；不据此宣称性能提升或全部兼容验收。真实撤权/重授权、提供者故障、剪贴板复制按钮、更多菜单和刷新等未在设备执行，见[D61–D68](docs/DEVICE_ACCEPTANCE.md#v130-已授权位置管理验收)的实际覆盖与限制。

README原18条历史及旧CHANGELOG正文保持，新增第19条摘要；125个本地文档链接核验无损坏。最终五附件的源码、签名、校验和及安装字节绑定另存于本轮交付审计记录。本轮没有公开发行、主机清理、新依赖下载或外部代码复制。

<a id="candidate-v120-2026-10-22"></a>
## v1.2.0 草稿、分享与精简界面候选 — 2026-10-22

本轮在正式v1.1.1说明提交98bc51e基线上增量实施用户选定的1、2、3、4、6、7、8、9、10：核对草稿、导入问题、PDF及享做目录差异、外部分享、生成摘要、分层返回、长按详情、完成结果和必要设备回归。VERSION1.2.0/code15，原包名、签名、min26/target35、两权限及依赖保持；仅本地候选，未公开发行。本段不将“实现存在”或“宿主通过”解释为设备验收通过。

- 集成检查：最终离线assembleDebug/assembleDebugAndroidTest/lintDebug/testDebugUnitTest成功，71任务（14执行57up-to-date）；307项JUnit中305通过、0失败/错误、2项原70条私人JSON缺失按原规则跳过。新增41项：草稿15、诊断及两差异模型22、分享分类4，全部通过；旧18份测试源及断言保留。lint0错误、25警告，无全局忽略或降低检查。
- 最终APK10,915,864字节，SHA256 `c11c69c82d7043180a2ce0a10c404c6f57ba7e56b060caa12c21f5e4bced120b`。实际1.2.0/code15、原证书/v2、对齐、API范围与两原权限已检查；测试APK单独打包，不进入正常发行附件。模型/界面代码与实际Android资源合并编译成功，编译不等于设备执行。
- 真实只读样本：127页PDF和47条目录（12根35子）经新TocDiagnostics接受，0错误/提示。生产引擎实际完成完整目录写入、已有目录默认拒绝且不生成结果、47条整体+1全替换、草稿分段页码恢复并输出；身份变化、128/127越界被拒绝。pypdf独立核对3输出全部127页对象/页面盒/旋转/内容/文字和元数据保持，47条标题/层级/页码精确一致，原PDF与JSON的前后SHA相同。复用相同输入旧视觉证据及已核验依赖，不重复渲染，不将47条代替缺失70条样本。
- 兼容性修复：旧PDF的已有书签可能没有标题或目标。差异预览保留null、空白与真实同名标题的身份区别，分别显示未命名/空标题，未知目标继续标示未知。新增2项JUnit及实际合成PDF经过原PdfBookmarks.inspect的2组宿主检查通过，不改原读取器或严格导入规则。
- 事务与状态审查：诊断仍调用原严格解析器最终确认，不自动修复或删条；差异仅预览，享做预览使用prepare已核验的相同快照，未知手动页不伪造物理页。草稿核对URI/SHA/原树及实际页数后才恢复，初始无预览保留0，不保存核对勾选或覆盖/删除选项。分享识别与写入采用同一忙碌门禁，取消不改变现有选择；草稿离开检查本次保存成功，不能拿旧草稿冒充新保存。成功另存后继续调整会重新保存草稿，同路径替换后须重读新PDF基准。
- 精简UI保持三区域及底部主按钮：初始隐藏空白预览和重复读取入口，按需出现核对/检查/替换选框；读取收起文件区但保留更换和重读；行详情长按打开，不触发选行/写入；结果按部分状态显示并聚焦，恢复入口和唯一清理入口保持。代码和模型验证未替代实际触摸/键盘/笔/字体/窗口及来源兼容检查。
- 打包回归9项通过，新增合成.git指针排除检查；工作树控制文件及私人路径不会进入源码附件。原签名私钥、输入、旧成果、诊断和其他任务保留。没有新依赖下载、新外部代码复制或主机清理。
- 实机失败与修复：在用户明确允许后，主包及临时测试组件安装均Success。首轮真实WorkflowSmoke在“Back left row details open”断言失败。原生Dialog先同步关闭，再异步通知OnDismissListener；ReviewWorkspace此前仅检查引用非空，错误短暂判为modal并重复消费Back。最小修复仅按Dialog.isShowing判定可见状态，并同步清主动关闭的Popup引用；保留原严格测试断言，没有加等待或跳过规避。修复后build06集中回归及第二轮实际Instrumentation完整通过，首轮日志保留。
- 实机通过范围：MRO-W00/API31实际Main启动及Activity重建、6次系统栏检查通过；3组真实主题/字号/密度控件应用、实际横竖屏2/2、PDF与JSON选择取消、两种空路径输入取消、原方向及外观/分栏偏好精确恢复通过。独立3条内存目录验证初始无预览、草稿恢复/幂等、无匹配/隐藏选行、非法页码回滚、长按详情与返回，隔离cache中真实DraftStore/AtomicFile 8项IO检查通过。未选取、读取或写入真实PDF/JSON/笔记，不访问用户剪贴板。
- 升级与收尾：最终设备base.apk SHA与上述build06完全一致；UID10229与首次安装时间保持，未卸载主程序或清数据。MainActivity/enjoy-directory/ui_appearance/review_ui原键值SHA与升级前全部一致。实际深色主界面截图已目视完整显示，当前PID无FATAL；附加am start仅送意图到当前实例，不额外算一次冷启动。临时USB亮屏设置已恢复原0并回读；仅卸载本轮cn.local.pdfbookmarks.test，Success且包不存在，主包/数据保留。合成cache证据保留。
- 仍未覆盖：外部QQ等实际分享来源、主流程草稿跨强杀后真实书籍继续核对、分屏/系统字体/手写笔、真实笔记替换及享做同步/清理/更新完整端到端未在本轮设备执行。按实际范围填写[D52–D60](docs/DEVICE_ACCEPTANCE.md#v120-草稿分享与交互验收)，不将有限冒烟视为全部设备验收。原1.1.1证据及其他历史保留。

七份说明的全部旧17条README历史和旧CHANGELOG正文保留，114个本地链接核查通过；源码与交付核验在最终候选打包时另行执行。公开下载仍指向1.1.1，发布新版须取得对本次具体候选的明确确认。

<a id="formal-2026-10-22"></a>
## v1.1.1 正式发行 — 2026-10-22

用户已明确确认正式发布和发布后本地清理；Release419918861为Latest，标题v1.1.1、非草稿／非预发行，标签固定221c18dc64053b2f0654f85e734965962384bac8。PR #4合并d192467596991b8f7f6df574c5ee9754822443c0；候选push39654088092、PR39654147981及合并39654603130的GitHub Actions均success。原签名五附件全部实际公开下载，大小／SHA与已批准的本地包逐项相同；源码ZIP122条对应标签，含18份单测和独立androidTest，11份许可齐全。APK10659037字节／SHA256`614277b650edb3b8e3ef71878ac44ea02eed310a1bffa05c0bbca1a5a0e8d644`；源码ZIP1387557字节／SHA256`9cfc910b937d19d8568504735cd59222fd2114b830938fc5430e81d77e618071`。

复用下方已执行本地264通过／2缺失样本跳过、lint0错误26警告和实际平板界面证据，不重复构建或重签。独立测试组件未运行，D49仅安装／启动部分通过，D50–D51及原D33–D48其余设备行为按真实范围保持待验。发布确认不代替未执行的生命周期、主题、旋转或文档验收。旧版标签／附件、原签名及用户资料保持；正式说明更新不改变本版程序、源码标签或附件。

<a id="candidate-v111-2026-10-22"></a>
## v1.1.1 启动修复候选 — 2026-10-22

用户在MRO-W00／HarmonyOS4.2／API31实际安装v1.1.0/code13后，点击图标闪退或白屏。只读取得该应用22份崩溃记录；堆栈明确为`PhoneWindow.getInsetsController()`内部decor为空，来自`UiAppearance.applySystemBars:110 → MainActivity.onCreate:100`。此前宿主264项回归不执行Android Activity生命周期，不能证明真实启动通过；新设备失败不归因于PDF、JSON或原笔记，也不以猜测撤回图标和新功能。

- 最小修复：将首次系统栏配色调用移到主界面创建之后；配色方法先取得decor，在其附着后通过View获取系统栏控制器。保留系统／浅／深配色、全部v1.1.0界面和原业务，不捕获并忽略NullPointerException，也不删除功能规避错误。VERSION1.1.1/code14，包名、原签名、min26/target35、原两权限和依赖保持。
- 增加设备专用原生Instrumentation `StartupSmoke`：真实启动Main、检查完整界面及主线程空闲，系统／浅／深三模式系统栏调用，再重建Activity并重复，共6次配色检查；不更改外观偏好，不选择或处理书籍。无需AndroidX／Robolectric或新依赖；测试代码只进入单独测试APK，普通应用不增加测试入口。失败返回堆栈，框架致命崩溃仍视为失败。
- 本地离线`assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest`实际成功，71项任务；266项中264通过、0失败／错误、2旧70条私人输入缺失跳过，全部原18份单测源保留。lint0错误／26警告，分类与上一候选相同。设备runner另经真实SDK35编译通过；编译通过不计为真机执行通过。
- 修复APK10659037字节，SHA256`614277b650edb3b8e3ef71878ac44ea02eed310a1bffa05c0bbca1a5a0e8d644`；临时测试APK11378字节，两者原证书均核验通过。该修复未改PDF引擎／JSON解析／享做两模式／暂存替换／清理恢复／更新核心；旧源码及v1.1.0候选保留，没有覆盖旧交付。
- 获用户明确确认后，已用原签名在该MRO-W00上保留数据覆盖安装1.1.1/code14，安装返回Success；userId及firstInstallTime前后一致，未卸载主应用或清数据。实际截图已目视确认主标题、外观入口及JSON文件选择页完整显示，当前应用持续存活，两次进程核对相同，当前PID崩溃缓冲无FATAL。`am start -W`返回Status ok，但意图送给已运行的前台实例；不能把这次命令称为额外冷启动。设备当前由用户操作，未强停或中断其界面。
- 临时测试组件被设备拒绝安装，返回`INSTALL_FAILED_ABORTED: User rejected permissions`；包列表核实组件不存在，故无需移除，也未运行Instrumentation。没有关闭安全检查、强装或把编译的6次配色／recreate写成设备通过。设备主程序正常显示与持续运行的直接证据有效，自动化生命周期检查保持未执行。
- [D49–D51](docs/DEVICE_ACCEPTANCE.md#v111-启动修复验收)按实际范围记录：启动／覆盖安装部分已通过；旧授权及恢复资料访问、三主题／字体／旋转／分屏、完整旧功能及真实文档处理没有因此全部通过。原D33–D48其余交互仍需分别验证。本轮未操作PDF／JSON／笔记、清设备日志或更改系统安全设置；新图标及全部原业务核心保留。

本轮仍为本地候选，未推标签／公开发行或新版附件。以下旧候选的设备待测／当时验证状态作为历史保留，以本段新证据为准。

<a id="candidate-v110-2026-10-22"></a>
## v1.1.0 核对与界面候选 — 2026-10-22

本轮从正式版 main `237fba1101ffdbe01f4d3c400e6d3c9adade6bff` 增量开发。VERSION1.1.0／code13，原包名及签名保持；候选未发布，公开正式下载仍为v1.0.0。以下为实际电脑验证，不能代替新版本平板验收。

- 现有JDK17／SDK35／Gradle8.9下，最终离线完整重生成的 `assembleDebug lintDebug testDebugUnitTest --offline --rerun-tasks` 成功，47项构建任务全部执行。266项JUnit中264通过、0失败／错误、2跳过，18份最终XML保留。新增54项：目录显示14、页码调整14、缩放及分栏几何12、分项结果9、外观设置5，全部通过；全部13份原测试源逐字节保持。
- 两项仍缺原私人70条JSON：`EnjoyCatalogTest.exactFixtureMatchesVerifiedMergeAndIsIdempotent` 与 `TocParserTest.realUserJasminumKeepsAllSeventyRowsWithoutPageOffset`。未删除断言或用47条新输入冒充该样本；已有344页享做混合页码只读样本参与并通过最终回归。首轮未传混合样本参数导致额外一项跳过，后续已恢复该项，不重复累计测试数量。
- 最终lint0错误／fatal、26警告。24项为原有类别：ApplySharedPref6、DataExtractionRules1、SdCardPath3、SetTextI18n11、TrustAllX509TrustManager3；另保留旧图标源码导致UnusedResources1，自适应新图标未提供可选系统单色图层导致MonochromeLauncherIcon1。没有全局放宽lint。程序化Review构造和GestureDetector已延迟处理单击两项静态误报，使用带原因的方法／类精确标注，未增加不可用构造或重复单击。
- 目录回归覆盖大小写／全角空白搜索、父级上下文、无匹配、折叠及定位、稳定源索引，筛选不改变完整来源；页码覆盖正负偏移、从导入基准非累加试算、乱序／重叠／完整范围／整数溢出、错误后保留有效映射及恢复。几何覆盖焦点缩放、边界、尺寸变化与分栏最小尺寸；结果模型覆盖后续失败不掩盖前序成功与过期任务拒绝；外观验证偏好边界、缩放、独立设置与配色对比。
- 实际47条／127页PDF+JSON只读输入，用当前生产`TocParser → PageAdjustments → PdfBookmarks.write`生成整体+1和两分段调整两份隔离输出。4项宿主检查通过；独立pypdf确认各127页／47书签，全部标题、层级和物理目标符合各映射，所有页面对象、页面框、旋转、解码内容、文字及元数据保持。输出保留原PDF完整字节前缀，原PDF和JSON前后SHA256一致。Poppler分别渲染原件及两输出的第10／13／125页，共9张图逐像素一致，并已目视检查接触图。未写入原件或平板，未以其他PDF写入库替代生产引擎。
- 原PDF／享做目录的暂存替换、日志、恢复、清理事务核心，以及JSON解析和更新下载／身份校验核心保持；Main增加完整工作目录桥接、阶段／结果及外观。原中断、权限拒绝、写入／关闭失败、改名后旧URI、散列变化、清理局部失败及恢复回归均保持通过。真实设备空间耗尽、进程终止和华为文件提供者异常尚待测试副本实测，未实际填满磁盘或在真实笔记上注入故障。
- 结果记录按实际步骤更新：生成／保存／享做目录／所选JSON／旧件分开；享做新目录已核验而自动清理失败时，目录成功及清理失败分别保留；PDF已保存但目录未成时保留JSON。另存取消后可用底部按钮直接保存已生成PDF。读取阶段提示关联当前读取任务，迟到回调不能覆盖新任务提示；页面渲染使用独立队列和代际检查，先清除界面Bitmap引用再回收。
- 新图标PNG为用户批准的内置imagegen产物，原PNG未编辑，原vector保留。API26起使用原生自适应图标前景／背景及安全边距，跟随启动器遮罩，未增加依赖。图标资源目录调整后的增量构建曾报告找不到mipmap索引；保留失败日志，相同源码通过完整重生成后成功，未通过删源码、忽略资源检查或运行clean处理。
- 生成、同步、删除、恢复及更新安装都沿用原保护；显示偏好和两种布局比例分别保存。原签名校验、APK实际包名／1.1.0/code13／min26/target35、既有两权限及自适应图标资源实际核对；不新增无障碍或全盘权限。最终附件另由现有打包／逐文件核验脚本生成，源码包不得包含书籍、私有享做快照或升级私钥。
- 用户确认暂不便连接平板，未安装或运行新APK。[D33–D48](docs/DEVICE_ACCEPTANCE.md#v110-核对与界面验收)的触摸、键盘／笔、字体／主题、旋转／分屏、授权／取消、实际享做目录／笔迹及故障恢复均待设备验收，旧D01–D32受影响项目同样不能补写为通过。无模拟器运行证据，不提供虚构界面截图；生成图标和宿主渲染仅证明各自产物。

功能、使用与来源见[FEATURES](docs/FEATURES.md)、[USER_GUIDE](docs/USER_GUIDE.md)和[本次更新记录](CHANGELOG.md#v1-1-0)。以下历史状态保持原貌。

<a id="formal-2026-10-21"></a>
## v1.0.0 正式发布 — 2026-10-21

用户明确要求“正式发布，注意规范，发布完后清除本地文件”。据此合并PR #3；合并提交`12d20d54e5cbede0bfae2d55d4d88f8807727471`与候选源码树相同，保留原提交历史。标签`v1.0.0`固定到候选`ce80688f7d1d2f411f5ce7368bb64b04f8231a0a`；Release标题仅v1.0.0，draft=false、prerelease=false且为Latest，不重建或重签附件。原v0.1.9标签与五附件保留。

- 草稿正文、标签/提交、五附件名称、大小及GitHub SHA-256全部匹配后公开；五项无凭据公开链接实际下载后大小/散列与本地候选完全一致，源码ZIP 100项与标签逐文件对应。APK 9,388,609字节，SHA256 `bbf155f481b92a3a9d0a3d7ab25c5c68a5cb93ffb91196e9ba890c5fe445c149`；源码ZIP 358,174字节，SHA256 `3d2605a1b9174927bedc795394ce4320693ef1418c7c86ed58b33e43f5f5e660`。
- 候选同提交push运行39462853113及PR运行39462901866均success；合并main运行39466506537同样success。沿用下方已完成的本地构建/210通过、2私人样本跳过/0失败、lint及原签名校验，不因发布重复构建。
- README下载/全历史摘要、使用说明及维护文档同步正式状态；源码ZIP保留标签时说明，main为最新文档入口。应用代码、版本、签名和五附件字节保持；此次状态文档更新不增加版本。
- 发布确认不等于设备验收：D19–D32新版华为交互、系统安装来源/内容授权、安装及旧数据保留、未来更高正式版在线更新仍待实测；旧私人70条JSON两测试仍缺样本。本次未写平板，不更改用户PDF、JSON或笔记。

以下“候选”小节保留开发阶段的真实检查及当时发布状态，当前发行状态以上方正式记录为准。

## v1.0.0 自动更新候选 — 2026-10-21

基线为v0.1.10草稿提交`2a9a46f91b7d9568ce9fb64c388ee98a7607888d`，保留其文件选择修复、全部旧功能和有效交付；远端main仍为`98d9ccd8b6c95b8e165eee57d625a821e42b3ba6`。本次新增更新功能，VERSION1.0.0/code12；没有新标签、Release或公开发行资产。

- 当前JDK17/SDK35/Gradle8.9，最终`assembleDebug lintDebug testDebugUnitTest --offline`实际成功。212项：210通过、0失败/错误、2跳过；原186项保留，新增UpdateReleaseTest 12项和UpdateClientTest 14项全部通过。14份最终XML已归档。
- 两项旧私人70条JSON回归仍缺输入：`TocParserTest.realUserJasminumKeepsAllSeventyRowsWithoutPageOffset`及`EnjoyCatalogTest.exactFixtureMatchesVerifiedMergeAndIsIdempotent`，用户已确认暂时没有；没有删测试/断言，也不计入通过。可用的享做混合页码真实样本仍参与回归。
- 新回归覆盖数值版本比较、稳定版/附件/地址/大小/摘要约束、24小时间隔/关闭/时钟回退、APK身份/code/系统/证书拒绝、官方重定向、重定向前拒绝其他站点、循环限制、下载取消/短包/超量/哈希错误、旧目标保护、缓存变化、网络失败/限流/元数据大小限制。网络故障测试使用注入响应与真实文件IO，不冒充实际Android安装。
- 最终lint0错误/0fatal/29警告，与v0.1.10现有分类和数量相同，未放宽或忽略检查。首轮新界面产生的SetTextI18n及UsableSpace提示已通过格式化资源和只读可用空间检查修正，未主动清理其他缓存；首轮及修正后日志/报告保留。
- 使用本次真实签名APK（9,388,609字节，SHA256 `bbf155f481b92a3a9d0a3d7ab25c5c68a5cb93ffb91196e9ba890c5fe445c149`）作为从旧v0.1.10/code11升级的输入，生产UpdateClient/UpdateRelease实际完成模拟Release/校验清单的解析、完整APK流式传输、SHA256与真实SDK元数据的身份约束，输出逐字节一致。Release响应仅在宿主注入，APK字节与aapt/apksigner数据是真实文件；没有发布测试Release。Android PackageManager解析与系统安装器未在电脑运行。
- 生产UpdateClient还实际HTTPS访问本项目latest API成功，以当前1.0.0检查返回没有更高正式版。首次沙箱Socket权限拒绝的日志保留；经正式工具权限审批后原检查器成功，不关闭TLS或改用未知来源，不把沙箱限制说成平板故障。
- 最终APK实际包名cn.local.pdfbookmarks、versionName1.0.0/code12、min26/target35、原升级证书、v2签名及zipalign通过。新增仅INTERNET/REQUEST_INSTALL_PACKAGES，主界面singleTask保留，新增私有只读APK提供者。包名/原证书/原PDF任务配置不迁移。
- 原PDF引擎、解析、Enjoy同步/合并、替换/清理事务、FilePickerState/Dialog、全部11份旧测试源及原资源/许可保持字节不变。已提供的127页PDF/47条JSON上一阶段真实内容验证继续适用，本次不重写原件、不以该47条输入替代缺失70条断言。
- 新版D19–D32设备触摸、实际权限、APK内容授权、安装和数据保留尚未执行；原版总体用户验收不自动覆盖新版本。使用原签名本地候选保留数据覆盖安装后核对，详见[设备矩阵](docs/DEVICE_ACCEPTANCE.md#v100-更新流程验收)。后续从v1.0.0升级未来正式版的设备测试尚不具备已发布输入，不虚构在线更新成功。
- 参考来源与采用内容见[AUTO_UPDATE](docs/AUTO_UPDATE.md#本次参考及验证边界)，没有新运行依赖或外部代码。源码、APK/清单匹配与同提交云端结果随最终候选和草稿PR记录；公开发行需本次明确确认。

## v0.1.10 本地候选 — 2026-10-21

基线为远端main `98d9ccd8b6c95b8e165eee57d625a821e42b3ba6`，仅在此版本增量修改。候选VERSION0.1.10/code11；没有推送新版标签、公开发行附件或启动公开发布流程。

- JDK17／SDK35／Gradle8.9现有环境，`assembleDebug lintDebug testDebugUnitTest --offline`实际成功。186项中184通过，0失败／错误，2项因原私人70目录JSON输入不可用而按测试既有可选规则跳过，不计为通过。两项为`TocParserTest.realUserJasminumKeepsAllSeventyRowsWithoutPageOffset`及`EnjoyCatalogTest.exactFixtureMatchesVerifiedMergeAndIsIdempotent`；用户已确认暂时没有该输入。另一个已保留的混合内部页码真实样本仍参与回归。
- 新增`FilePickerStateTest`8项全部通过：未选择取消、空／失败列表关闭、授权根及子目录导航、过期结果拒绝、隐藏享做PDF和类型过滤、JSON两种文件名、只提交可见文件及重复／错误提交拒绝。JVM状态测试不等于Android对话框、系统选择器或设备触摸已实际运行。
- lint 0错误／0fatal、29警告；没有修改lint断言或忽略配置。既有路径检查实验选项及SDK XML工具版本提示仍在。首轮传入旧私人输入路径时2项因文件不存在失败，原日志/XML保留；后续验证不制造替代真实输入，不重写旧测试。
- 新版HarmonyOS系统选择器取消、从桌面图标返回已有界面、覆盖安装数据／权限、布局触摸及享做目录／笔迹复测尚未执行，见[新增设备清单](docs/DEVICE_ACCEPTANCE.md#v0110-文件选择与界面复测)。旧版总体用户验收不自动计为新版设备通过。
- 实际文件补测：用户于2026-10-21提供《动力系统基础》PDF（127页，原有47条书签）和另一份Jasminum JSON（47条／2级）。使用本次未修改的生产TocParser、PdfBookmarks和同版本官方Android AAR，在宿主运行4项检查：实际解析、默认跳过已有目录、明确全替换及精确重读、输出重名拒绝及原件保护。全部通过；这不是用其他PDF写入库替代生产代码的测试。
- 独立pypdf检查47条标题／层级／实际跳转页全部一致，127页页面对象、页面框、旋转、解码内容、提取文字和元数据保持；新文件保留原件完整字节前缀。Poppler渲染第10、13、125页，原／新像素一致，并人工检查目录页、首章与索引的渲染图。PDF SHA256 `50d5f5368a063e6a8713d30be79736669890bd22c40a2894aefe7f8303d0b5c3`、JSON SHA256 `a90d093afe2b062eb2e9d26a17077c90c1ac8752ac46d0c3b4bcf250bfb34743`测试前后不变。原件已有的交叉引用编号提示不影响此次读取和渲染。
- 新提供的47条JSON与原70条JSON不是同一输入，因此上方两项旧样本仍明确跳过；不以新样本通过替代原断言。此次不覆盖用户文件，不操作平板笔记，不将私人PDF／JSON放入仓库或源码包。
- 最终APK为9,480,157字节，SHA256 `38c69dd09b09efe22c8192b51f81edc3c7105463f1ca1bdae715a2b187845fe7`。包名cn.local.pdfbookmarks、versionCode11、versionName0.1.10；沿用原升级证书且未新增权限。程序有意将主界面入口改为singleTask，回到已有实例时只取消未完成系统选择，不重置预览；相关系统行为仍须D22设备验证。
- 相对88文件基线，原PDF引擎、解析、享做同步／合并、替换／清理事务、全部10份旧测试、资源及许可字节保持。应用内仅MainActivity、入口launchMode和新增选择页相关类调整；版本、使用文档及新增选择状态测试随之更新。
- 实现只使用Android原生SAF和界面组件，没有新增外部代码或运行依赖；官方接口参考见[完整更新记录](CHANGELOG.md#v0-1-10)。

## v0.1.9 正式版确认 — 2026-10-09

用户在目标平板验收后明确报告“我已验收，现在改为正式版”。据此接收v0.1.9总体用户验收并将现有Release晋级正式版/最新版本，完善功能和使用说明。该反馈来自用户实际使用；没有补写D01–D18逐项结果、故障注入或云同步测试结果。

此次仅更新文档和Release状态/正文，应用源码、资源、VERSION/code10、标签及原5附件不变。v0.1.9对应提交75c2b978e78f5a3bd5ea4e6a0988b012941c8885已完成178项本地回归（0失败/错误/跳过，lint0错误/27警告）以及main/tag云端CI；云端178项中175通过、3私人样本回归跳过，0失败/错误，lint0错误/28警告。现有5资产已实际下载核验，APK SHA256为9b2f2493a9af482eca0575d8638059e1f8431b366e2a8ffaa87db1b432b792a1。新文档提交按既有CI检查，实际记录见Actions。

下方为发布前阶段的历史记录，包含当时尚待设备验收的状态，不代表现在仍未取得总体用户验收。详细验收来源及后续复测清单见[DEVICE_ACCEPTANCE](docs/DEVICE_ACCEPTANCE.md)。

## GitHub 首次发布 — 2026-10-09

用户已授权公开PDF_Bookmarks_Android及v0.1.9个人测试版。本次只更新公开说明、CI失败诊断及月度依赖PR配置，应用代码/资源、VERSION、versionCode和原签名APK不变；下方“未创建/未发布”属于各次本地交付时的历史状态。现有178项本地回归通过、lint0错误/27警告的证据继续适用。云端不提供私人真实输入，对应测试跳过不计通过；实际运行见[Actions](https://github.com/Zerozero05/PDF_Bookmarks_Android/actions)，资产及SHA清单见[v0.1.9](https://github.com/Zerozero05/PDF_Bookmarks_Android/releases/tag/v0.1.9)。本段不将待执行云端检查当成通过；新版目标设备D01–D18仍待实测。

首次云端检查在setup-android/v3默认安装已停止提供的旧tools包时失败，未进入应用构建。已明确只安装platform-tools，保留JDK17、SDK35、Gradle8.9及应用源码，不通过忽略错误绕过检查；失败运行和修复后的实际结果保留在Actions中。

## v0.1.9 — 2026-10-09

- 沿v0.1.8断点精准调整享做“全部替换”成功后的目录副本保留政策，两同步入口一致。临时恢复副本仍用于安全写入；新目录重读核验且成功记录持久保存后，显示精确外部/内部两路径，再自动清理本次旧目录及内部目录恢复副本。保留并添加模式、PDF双备份、统一清理、JSON选项及原业务核心保持，不扫描历史备份、不删除活动catalog/temp或笔迹配置。
- assembleDebug/lintDebug/testDebugUnitTest实际通过（verification/v0.1.9/build.log）：178项，0失败/错误/跳过；原167项保留，新增11项单目录清理故障回归。lint0错误/0fatal/27警告。10份JUnit及lint共11份XML已归档并记录SHA；清理测试以内存包装模拟删除，宿主样本保留。
- 新回归覆盖只读预检、保存进度及显示清单失败不删、删除前变化/guard拒绝、提供者false/删后抛异常、内部删除失败、完成记录失败续接，同时检查当前catalog、PDF及独立PDF外部/内部备份保留。借用原整批清理核心，不改变其保护规则。
- 成功LAST记录以兼容可选boolean保存自动清理意图，与移除目录pending同一commit；Main删除进度复用pendingCleanup。早期预检/保存删除进度失败时仍可从LAST构造目录-only原清单，重启阻止新写入；失败可核验继续，或先同步归档原清单后保留剩余文件并结束。无变化操作不创建新副本，也不删除历史LAST。
- 本地测试不等于Android SharedPreferences实际掉电、UI显示、SAF提供者删除和享做重开验收；这些仍待设备验证。此前用户单笔记目录/跳转/笔迹正常属于历史事实，不能当新版自动清理实测。0Pad写入/0宿主清理，GitHub未创建或发布。版本0.1.9/code10，包核验和独立复核结果将记录在同目录release.log/package-check.json/final-check.json。

## v0.1.8 — 2026-10-09

- 沿有效v0.1.7继续，仅修改第3节展示和成功关联旧件的统一清理。处理选项/更多操作折叠，保存重试和只同步、目录两模式、JSON选项、内部备份导出/恢复保留；待恢复按钮直接显示。原PDF引擎、解析、替换核心与目录合并不重写。
- 第一轮assembleDebug/lintDebug/testDebugUnitTest实际成功：167项0失败/错误/跳过，lint0错误23警告。旧145项保留，新增22项批量清理回归；覆盖全清单预检拒绝、跨项别名/重复、部分外部删除失败保留全部内部、内部删除失败和已删续接、变化/权限/无效果/中断。内部删除用内存文件包装模拟，宿主fixture原件保留。
- 独立只读复核指出3处兼容/失败状态问题：未完成清理后目录变化会锁住后续操作；通用提供者文档ID不可拼接；再次PDF替换且目录no-op后旧同步记录PDF指纹变旧。精准修改为保留关联进度记录后可结束清理、实际目录列举身份核对、初始按组筛选可核验对象并完整列出保留项及理由，再对待删全计划预检。不更新或放宽旧目录指纹。后续最终构建与发布记录在verification/v0.1.8。
- 清理授权快照在首次删除前同步持久化；全部外部旧件确认不存在后才删内部副本。当前输出及关联记录反复校验，中断保留进度；全部核验完成才清除对应恢复入口。应用内部备份也清理是用户此次明确选择，原始JSON仍由独立可选项处理，不纳入统一清理。
- 第一次构建调用误将真实样本路径指向工作区根，脚本在实际构建之前拒绝；未修改任何样本，修正为工程/work中的既有路径，失败日志保留根verification/v0.1.8/build-unified.log。
- 修正后的最终assembleDebug/lintDebug/testDebugUnitTest再次实际成功，167项0失败/错误/跳过，lint0错误24警告；独立复核通过。9份JUnit及lint共10份XML逐SHA归档。相对v0.1.7源包84项，69项原字节保留、15项必要修改；16项历史交付/真实输入及旧诊断SHA保持，详见source-diff.json与preservation.json。签名、实际APK元数据、许可和源包逐项结果以同目录最终release.log/package-check.json为准。
- 新版UI、持久授权、真实提供者删除及进程重启未在平板验证。v0.1.7用户截图仅证明两模式界面与一次旧目录清理成功显示，不能当新版验收。0平板写入、0宿主清理；GitHub仍待用户确认。

## v0.1.7 — 2026-10-09

- 在v0.1.6有效修复基础上增加享做目录两模式，默认保留并添加；明确全替换仅替换根kids树，原根字段与备份/恢复/快照/身份/页面映射及精确清理门槛保留。原PDF引擎、解析器和替换核心未改，v0.1.6交付不覆盖。
- assembleDebug/lintDebug/testDebugUnitTest通过：145项0失败/错误/跳过，lint0错误19警告。新增5项回归验证全替换只保留导入树、两模式不同目标、层次及计数、重复全替换原字节不重写、无效/歧义目标失败且输入不变；既有实际344页混合格式样本扩展为全替换核对，仍保持原434页及72条合并回归。
- 两个入口均传入捕获的模式；互斥RadioGroup默认保留，换PDF或JSON/界面重建回到默认，任务运行禁用选择，确认弹窗明确是否包括全部手动项。服务旧prepare签名仍默认保留，两模式重复无变化时都不新建目录备份。独立只读审查未发现阻塞。
- 本轮没有安装/运行新APK或写平板；新UI触摸、两模式SAF写入、实际目录/跳转/笔迹及重开保存仍待设备验收。目标路径与诊断来自上轮用户确认和只读快照，不能代替新模式实测。
- 版本0.1.7/code8，新交付目录为deliverables/Android-v0.1.7；最终包、签名与逐项源包核对以verification/v0.1.7实际发行记录为准。失败保留JSON、原目录备份及恢复记录；不通过删除活动catalog/temp来选择模式。

## v0.1.6 — 2026-10-08

- 根据用户报错截图与当前笔记只读快照定位到EnjoyCatalog.validateNode：原实现要求每个节点page=-1。实际344页笔记已有10个目录节点，混合使用-1与19/21/22/26/28，全部子节点pageId非空；5个非负值与对应pageids.docIndex和doc.pages.index一致，未发现重复同级标题与页标识键。只读资料保存在work/device-format-20261008-v016，不随源码公开分发。
- 先增加4项回归，140项中2项失败，均复现截图同一IOException（合成混合页码及实际344页快照）。修复后assembleDebug/lintDebug/testDebugUnitTest通过，140项0失败、0错误、0跳过，lint为0错误、19警告；保留既有434页真实JSON和72条目录样本回归。原始日志、XML及统计位于本地verification/v0.1.6；v0.1.5历史验证及交付保留，不将失败尝试或重复运行累计为新增测试。
- 修复范围仅允许非根旧节点page=-1或非负整数并保留原值，根仍要求-1；子节点仍须明确非空pageId。新增目标继续按所选PDF路径、docIndex及doc页标识核实，新节点page=-1，不用旧page替代缺失或错PDF映射。回归涵盖page=0、正值与-1混合保留、匹配不重复、未知负值/错误数字类型/空pageId拒绝、实际样本旧节点逐字段保留及再次合并原字节不变。
- PathDocuments增加有类型的文件夹授权异常；替换失败只有缺少或失效的持久读写授权或SecurityException时才提供授权按钮，普通格式、映射、校验、文件夹地址与提供者能力错误不再误导重复授权。失败后仍清除核对勾选，要求重新核对；权限、目录身份、哈希、备份、恢复及删除门槛未放宽。
- 此格式错误来自enjoy.prepare的只读检查，位于原PDF备份/替换阶段记录及文件改名之前；不需要通过删除配置解决。本轮仅只读取得元数据和344页PDF用于宿主回归，没有写入平板、安装或运行v0.1.6。实际新版SAF操作、目录显示/跳转/持久保存和原笔迹仍待设备验收，不能以宿主测试代替。
- 版本0.1.6/code7；拟交付于新的deliverables/Android-v0.1.6目录。新版签名、APK及源码包哈希以最终打包校验记录为准，此处不将旧版本哈希当作新版结果。构建与发布入口支持-EnjoyMixedFixtureFolder，重建方法见文末。

## v0.1.5 — 2026-10-08

- 第一候选assembleDebug/lintDebug/testDebugUnitTest通过；134项0失败/错误/跳过。最终复核新增2项仅同步入口回归，复现无标题PDF书签导致界面异常，精准改为安全比较；最终136项结果和reviewed候选记录见本地verification/v0.1.5，原第一候选不覆盖。原108项保留，增加目录阶段6项、目录合并20项（含显式真实样本）与入口2项。同步提交用于持久恢复记录，不以异步保存替代，也未全局忽略错误。
- 真实样本回归使用只读原目录、pageids/doc和Jasminum JSON及实际434页：生成72条与已回读并获用户确认的候选逐字段相同；两条手动节点不变，重复导入0新增且原字节不变。合成测试覆盖嵌套保留、同名不同页、错PDF/ID/页索引、映射缺失/歧义、未知类型、重复键、UTF8与深度。
- 首次新增测试编译因Files.readString未提供而失败，已换兼容读取；第二次实际样本揭示19条origin=true也有明确PDF页面映射，修正为以document+docIndex+doc页ID确认，不以origin值猜测页号。最终仍拒绝无映射的第1页、歧义和越界。失败日志保留build.log/build-retry.log，成功日志build-final.log；原XML及test-summary.json在verification/v0.1.5。
- 享做目录服务静态复核确认prepare只读、独立目录阶段日志、原字节备份与新暂存件同步回读、切换前重新核验元数据和新PDF、完成后再次核验新旧目录和内部备份。恢复限制catalog及本次UUID暂存/备份身份，未知或变化不覆盖。PDF成功/native失败分别报告，失败保留输入JSON。
- 清理须手动确认弹窗唯一完整目标、无pending且成功记录未变；重新校验活动目录、新PDF及两份原目录备份，只删除关联旧备份。不会猜测清理电脑试验/旧版备份，不删除活动catalog/temp/页配置/.ncc/笔迹。享做保存或后续PDF重写导致指纹变化时安全拒绝。
- 单笔记试验：实际295原文件回读，仅主catalog改变、其他294份SHA不变；用户确认72条目录、第一章跳PDF12页及原笔迹正常。正式v0.1.5 APK的SAF写入、清理与恢复、保存再打开及云同步仍待实机验证；无ADB设备，未声称新APK已安装运行。

## v0.1.4 — 2026-10-08

用户明确当前长期维护范围为PDF＋JSON版，OCR/工作台/批量后续再加。保留已有功能与v0.1.3交付，只增加维护/发布保护、版本自动同步及说明；业务引擎未重写。

- 先在合成fixture复现旧package-release.ps1会覆盖已存在同版本APK并返回成功，保存verification/v0.1.4/overwrite-baseline.json。真实历史发行包未动。
- 修改后9项发布回归通过；命名根据现有PC下划线格式调整后，用本次0.1.4 APK在独立fixture再次运行9项通过，未重复累计测试数量。覆盖已存在目录/源码根及子目录、旧构建元数据、错签名、无效签名、实际APK错版本、正常打包和敏感输入排除/哈希、重复打包。结果为packaging-current-names/results.json；回归只操作合成资料与APK副本。
- assembleDebug/lintDebug/testDebugUnitTest成功，108项JUnit全部通过（0失败/错误/跳过，显式真实JSON与434页）；lint0错误16警告。原XML归档到verification/v0.1.4/test-results，日志为build.log。版本0.1.4/code5，界面版本由Gradle生成资源从VERSION同步。
- apksigner v2通过，同包名cn.local.pdfbookmarks，证书指纹仍为405036e63efe1bd2f106e887801f6deec0b1e496e564dc9b4d7e2a56d1017fca；minSdk26/targetSdk35，未新增权限。构建APK9322211字节，SHA-256 012a416d0774f833674a593f2f0824350843e474db00469e2f4198638fd8278a。
- 发布入口release.ps1在构建/回归成功后校验版本、实际签名与APK身份、zipalign，才创建全新的交付目录；源码包另由verify-release.ps1逐项核对当前源码/必需维护依赖/许可资源及SHA清单。打包失败不自动清理历史文件。实际本次交付结果见verification/v0.1.4/release.log及package-check.json，不将脚本存在本身当验证成功。
- .gitignore排除整个verification及私钥/书籍；源码包也独立排除诊断、私钥、环境文件和真实输入。维护说明明确签名私钥保留、旧数据兼容、版本递增、回归、设备验收和交接清理。
- 当前只读ADB列表为空，未安装APK或执行Pad操作。华为SAF授权、页面渲染/滚动、实际替换/删除/中断恢复、享做缓存/批注仍待设备验收；docs/REQUIREMENTS.md逐项标出这些缺失证据。GitHub仓库/Release仍未创建，云端CI未运行。现有PC命名只读核对为PDF_Bookmarks/v1.5.0，Android新包采用PDF_Bookmarks_v0.1.4_android.apk及_android_source.zip；旧包保持原名与内容。

## v0.1.3 — 2026-10-08

本轮在v0.1.2实际断点上增加两栏目录核对与手动旧备份清理，没有重做原有功能。证据保存在本地verification/v0.1.3，根WORK_CHECKPOINT.md记录恢复及执行状态。

- assembleDebug、lintDebug、testDebugUnitTest通过，build-release-candidate.log为BUILD SUCCESSFUL。7份JUnit XML共108项：新增旧备份清理24项，原有84项保留且通过；0失败、0错误、0跳过。真实JSON仍显式指定434页。lint为0错误、16警告。
- 新清理回归覆盖错误名称/URI、同名歧义、文件内容变化、缺失或损坏的私有备份、私有备份别名、权限拒绝、删除返回false、删除无实际效果、已删后抛异常的安全重试、删除后新PDF变化及线程中断。旧备份已消失时也必须重新核验新PDF和指定私有备份；未确认成功的操作保留关联记录。
- MainActivity静态复审确认：成功替换关联记录与新PDF标识同次同步提交；存在待恢复记录时禁用清理；手动确认列出唯一旧备份目标；同锁重查关联记录；应用内备份限制在私有pdf-backups目录；删除适配器重新查询精确名字/唯一身份并检查删除能力及写授权；恢复后作废对应清理记录。未发现将原名新PDF作为删除目标的路径。这是代码及宿主测试证据，不是华为SAF删除实测。
- 目录区域固定表头“目录标题/目标页”，长标题换行并保留层级缩进，物理页码独立右对齐；整行点击仍调用原目标页预览，DirectoryScrollView与旁边页面预览保留。实际触摸和布局仍待平板验收，未把静态检查记为设备测试。
- 与已交付v0.1.2源码ZIP逐字节比较，PdfBookmarks、TocParser、PathDocuments、StagedDocuments、StagedReplacement、DirectoryScrollView及所有旧测试均未变；取消、路径授权和旧生成/另存关键方法也保留。未重复执行历史真实PDF引擎场景，旧证据仅适用于未改变的引擎。
- APK包名cn.local.pdfbookmarks，versionCode4、versionName0.1.3，minSdk26/targetSdk35；apksigner v2和zipalign通过，未新增权限。签名证书SHA-256仍为`405036e63efe1bd2f106e887801f6deec0b1e496e564dc9b4d7e2a56d1017fca`，可覆盖安装；请保留应用数据。构建APK9321887字节，SHA-256 `0d49c8a208fe4881c33da6bba6a991454852af75386525d7ee39a3acea5f42be`。
- 只清理本轮确认可重建的小型宿主编译及fixture时，先另存交接并列完整绝对路径清单；历史交付、实际PDF/JSON输入、Pad诊断副本、源码、升级签名及工具依赖保留。实际清理结果以新交接和cleanup-result.json为准，清单不是已删除证明。
- 尚未验证：目标平板的两栏布局与触摸、用户手动确认流程、文件夹持久授权/删除提供者结果、系统中断后的设备重试、享做重新读取及批注显示。未安装/操作Pad，未新建GitHub仓库或发布Release。

## v0.1.2 — 2026-10-08

本节只记录已经执行的检查；下方v0.1.1结果属于历史版本，不能代替本轮验证。原始构建、JUnit、签名及设备诊断证据保存在本地verification/v0.1.2，不打入公开源码ZIP。

- 最终候选assembleDebug、lintDebug、testDebugUnitTest通过。84项JUnit：原有46项、分阶段替换29项、读取取消5项、恢复记录4项，0错误、0失败、0跳过。`build-release-candidate.log`为BUILD SUCCESSFUL；逐个读取JUnit XML确认上述计数。lint为0错误、16警告；未用全局忽略错误的方式通过。
- APK包名cn.local.pdfbookmarks，versionCode3、versionName0.1.2，minSdk26/targetSdk35；apksigner v2与zipalign通过，未声明联网、无障碍或存储权限。证书SHA-256仍为`405036e63efe1bd2f106e887801f6deec0b1e496e564dc9b4d7e2a56d1017fca`，可覆盖安装保留此前应用内备份；不要先卸载或清除数据。最终APK9380809字节，SHA-256 `b3ff0ba7e59ed17e1b30cf8d19bb4ac627d7d9df0950ce8ceb6b96901cba7da6`。
- 新分阶段核心对153MB真实PDF的电脑端副本执行同目录、同名称改名替换，再恢复原件；替换与恢复核验通过。这是生产核心与电脑文件流的检查，不是Android/HarmonyOS文档提供者实测，未改用户原PDF或JSON。
- `PathDocuments`、`StagedDocuments`与新的纯Java替换核心用JDK17及真实Android35 `android.jar`独立编译通过。静态审查涵盖实际持久读写tree授权、同卷目录边界、真实精确名称列举、新建文件唯一ID、仅新暂存文件可写、改名返回的新URI及文件夹创建/文件改名能力。编译和审查不等于Android提供者操作已通过。
- 用户USB连接的HUAWEI MatePad Pro可通过Windows MTP访问；已只读确认内部存储、享做笔记/note及Download，未修改、删除、安装或运行设备文件。ADB设备列表仍为空；没有安装模拟器或系统镜像。MTP存在不能作为Android SAF或APK运行测试的证据。
- 根据用户给出的完整位置，仅只读复制内部存储根目录的「带目录」输出，以及享做对应原资源PDF至本轮独立诊断目录；两份本地副本保留用于诊断及恢复，Pad上的原件保留。诊断时原资源PDF真实存在且可解析，不能把用户描述直接记为已复现的原文件删除。
- 两副本经pypdf严格读取均为434页、70条书签、最大2级、目标范围9–429，无无效跳转；标题、层级、实际页码逐条相同，均与只读Jasminum原生JSON的70条目录逐条吻合。原资源PDF在诊断时已包含同一完整目录；不能据此推断它在旧任务执行前的状态。
- 两副本全部434页解码内容流、页面框和旋转一致。没有比较图像资源、批注或其他对象，不能据此承诺所有PDF对象相同。根目录输出存在，但未取得旧任务的完整日志或复现过程，尚未查明它为何另存至根目录或用户当时看到原文件消失的触发条件。
- 待验收：误触路径输入/选择器/读取后的取消与返回、取消后重新选择、大PDF读取、旋转或退出后的过期回调、华为文件夹持久读写授权、暂存文件同步/改名返回URI、改名中断与重启恢复、旧版原件消失后的同名恢复、成功后JSON删除、享做原路径读取及缓存刷新。替换切换和恢复关键阶段不提供中途取消。

## v0.1.1 — 2026-10-06

- 最终 assembleDebug、lintDebug、testDebugUnitTest 通过。46项JUnit（解析20、共享路径11、替换事务15）：0错误、0失败、0跳过；真实JSON参数显式提供实际434页。
- 替换回归涵盖截断、原件变化门禁、无效/变化备份、输出与备份别名、部分写失败、close/sync失败、重读不一致、SecurityException、中断恢复及恢复失败保留备份。
- 生产SafeReplacement对真实原书的副本执行同路径同名覆盖，再恢复原件：覆盖后153366757字节，hash为已验证70条书签输出的`6ffde2b451d86a18ee6e1769f070e07c1992faedc1d2fea710d8b5bf46223b42`；恢复后153354101字节，hash为原书`3e737790f88c8d72d8338cd336adf481d243a98011710717ac27ab9ba92b243a`。这是真实文件流宿主测试，不是Android文档提供者实测。原PDF/JSON与上轮输出只读且哈希未变。
- PDF引擎PdfBookmarks.java与目录解析器TocParser.java逐字节与v0.1.0源码ZIP核对一致。下方此前27项引擎、434页/70条结构及渲染核验仍为该引擎的有效历史证据，没有把它们重复计为本轮新测试。
- APK包名cn.local.pdfbookmarks，versionCode2、versionName0.1.1，minSdk26/targetSdk35；apksigner v2、zipalign通过，与v0.1.0签名证书SHA-256相同：`405036e63efe1bd2f106e887801f6deec0b1e496e564dc9b4d7e2a56d1017fca`。可覆盖安装升级，未增联网/无障碍/存储权限。
- 最终APK9348052字节，SHA-256 `b69dddb0fed0a6b3c99ccda589642b58e04b15b7d429a44ad7d1c267231ffdcb`。
- lint为0错误、17警告：中文文字拼接8、共享路径提示/别名字符串3、第三方TLS类3、备份规则1、同步SharedPreferences提交1、ParcelFileDescriptor所有权识别1。恢复记录必须同步提交；文件描述符由AutoCloseOutputStream的finally关闭；这些警告没有通过全局忽略错误处理。
- 只读复审后的保护：优先读写tree授权而非旧只读精确URI；同进程静态锁串行化覆盖/回滚/启动核对；锁内重读恢复记录、仅清除本次所有的记录；回滚同步失败即使字节哈希吻合也保留记录。启动发现未清恢复记录只核对、不自动当成完成或删除JSON，先恢复后重试。
- JSON删除仅在输出重读校验成功、JSON仍与预览哈希相同、写授权及FLAG_SUPPORTS_DELETE可用时执行。失败单独报告PDF成功/JSON保留；应用中断不继续删JSON。代码顺序经只读复审，实际Android删除尚未设备验证。

电脑端ADB无连接设备、SDK没有已安装模拟器；本轮未安装系统镜像。未实测：华为目录授权/持久权限、嵌套列表实际滑动、PdfRenderer、原URI rwt与sync、JSON删除、系统杀进程恢复、享做缓存刷新/层级跳转/批注。截图证明旧APK能进入页面，但空白目录仍未加载；该状态已加明确提示。

原始日志、XML和真实文件harness仅保留在本地verification/v0.1.1/，不进入公开源码ZIP。GitHub仍未创建/发布，云端流程未运行。

## v0.1.0 — 历史检查

日期：2026-10-06。交付为开发签名测试APK；本地真实文件检查不代替目标平板安装和享做导入实测。

| 检查 | 实际结果 |
| --- | --- |
| 构建 | assembleDebug、lintDebug、testDebugUnitTest通过；JDK17、SDK35、Gradle8.9、AGP8.7.3 |
| 解析回归 | 20项JUnit通过，0失败、0跳过；包含显式只读真实JSON输入及实际434页参数 |
| PDF引擎 | 27项真实PdfBox-Android AAR宿主场景通过；Android平台日志/资源使用仅供测试的薄适配 |
| 原书测试 | 153354101字节、434页原书，完整70条书签按标题、层级、页码核验；明确替换原有书签 |
| 独立复核 | pypdf复核全部434页的对象、页面框、内容流、提取文字及文档元数据不变，输出目录一致 |
| 原件保护 | 原PDF哈希不变；输出完整保留原PDF字节前缀；原JSON未修改 |
| 渲染 | Poppler第12与429页原件/输出像素哈希分别一致，人工查看输出PNG通过；原件已有图形状态提示未影响渲染 |
| APK | 签名v2及zipalign通过，交付副本与构建输出相同 |
| 权限 | APK未声明无障碍服务、联网或存储权限，仅系统文件选择器授予所选文档访问 |
| 元数据 | cn.local.pdfbookmarks、versionCode1、versionName0.1.0、minSdk26、targetSdk35 |
| 许可 | 完整AGPL及第三方LICENSE/NOTICE随APK和源码附带 |

APK 9283525字节，SHA-256：`9c9b4335f7d2ceebd362ec488c523dce6ec7b756841906e8be9ef7b1a86eda70`。

最新真实输出153366757字节，SHA-256：`6ffde2b451d86a18ee6e1769f070e07c1992faedc1d2fea710d8b5bf46223b42`。原书及输出字节前缀SHA-256均为 `3e737790f88c8d72d8338cd336adf481d243a98011710717ac27ab9ba92b243a`。输出用于本地验证，不随公开源码包分发用户书籍。增量保存可能生成不同的第二文档ID，因此重复运行的输出哈希不承诺相同；每次均核验原字节前缀与内容一致。

lint为0错误、9项警告：中文界面文字拼接、Android备份属性提示和第三方BouncyCastle测试信任管理器提示。APK没有网络权限，未调用相关TLS代码。没有用忽略全部lint错误的方式通过检查。

UI只读审查后修复：Activity重建使用各自独立的UUID缓存目录，旧工作线程不能覆盖新会话；系统文档别名通过authority和documentId检查，拒绝选择输入文档作为输出；`readyVerified`不在重启后复用，未校验缓存无法导出。

## 尚未验证

- 目标MRO-W00/HarmonyOS4.2.0上实际安装、系统文件选择与持久授权、目标页预览、保存、退出和重启。
- v0.1.6在享做7.0.2中的实际目录同步、层级与跳转、保存再打开及原笔迹。此前单笔记合并已获用户确认，不能代替新版APK和其他笔记的设备验收。
- GitHub云端构建：已准备工作流但未创建远端仓库，因此未运行；CI测试APK使用临时签名，不作为未来覆盖升级的发行版签名。
- 桌面OCR、文字自动识别、批量和完整编辑工作台尚未移植；此版对应PDF＋JSON流程，不能称为全部PC功能移植。

## 重建检查

普通构建：`scripts/build.ps1`，或自行配置JDK17/SDK35后运行项目Gradle Wrapper。

真实JSON回归：`scripts/build.ps1 -InputJson '<只读JSON路径>' -ActualPageCount <从实际PDF读取的页数>`。未提供真实数据参数时，相关案例明确跳过；不能据此声称真实文件验证通过。

享做混合页码实际样本回归：添加 `-EnjoyMixedFixtureFolder '<只读344页笔记样本目录>'`，目录须含catalog.json、pageids.json和doc。该案例使用本次已核实344页样本，不是任意样本自动推断页数。原434页目录回归另用 `-EnjoyFixtureFolder` 配合对应真实JSON与实际页数；两个参数也适用于release.ps1。真实笔记、测试输出与备份不进入公开源码包。

真实PDF引擎回归：`testengine/run.ps1 -JavaHome '<JDK目录>' -Python '<含pypdf的Python>' -InputPdf '<只读PDF路径>' -InputJson '<只读JSON路径>'`。下载的测试依赖SHA已锁定，脚本在本项目work目录产生fixture与输出；不得把这些用户输入/结果上传公开仓库。
