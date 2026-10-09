# Android 验证记录

## GitHub 首次发布 — 2026-10-09

用户已授权公开PDF_Bookmarks_Android及v0.1.9个人测试版。本次只更新公开说明、CI失败诊断及月度依赖PR配置，应用代码/资源、VERSION、versionCode和原签名APK不变；下方“未创建/未发布”属于各次本地交付时的历史状态。现有178项本地回归通过、lint0错误/27警告的证据继续适用。云端不提供私人真实输入，对应测试跳过不计通过；实际运行见[Actions](https://github.com/Zerozero05/PDF_Bookmarks_Android/actions)，资产及SHA清单见[v0.1.9](https://github.com/Zerozero05/PDF_Bookmarks_Android/releases/tag/v0.1.9)。本段不将待执行云端检查当成通过；新版目标设备D01–D18仍待实测。

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
