# 自动检测与安装更新

适用于v1.0.0及以后包含此更新器的版本。[v1.0.0已正式发行](https://github.com/Zerozero05/PDF_Bookmarks_Android/releases/tag/v1.0.0)；v0.1.9/v0.1.10没有此更新器，首次需手动保留数据覆盖安装v1.0.0。原有PDF＋JSON、享做目录、清理及恢复功能继续保留。

## 使用方法

1. v1.4.0从主界面“更多 → 版本与更新”进入；旧版使用原“版本与更新”入口。默认勾选“启动时自动检查更新（每天最多一次）”。程序启动或回到前台时检查间隔，应用关闭期间没有定时后台任务。
2. 也可点“检查更新”，不受24小时间隔限制。自动检查可以关闭；手动检查仍可使用。检查失败不会阻止PDF处理。
3. 发现更高正式版后查看版本、下载大小与更新说明，点“下载更新”。可以取消检查/下载；文件处理使用独立工作队列。
4. 完整下载及校验成功后出现“安装更新”。请先完成PDF、目录写入、恢复或清理任务，退出文件选择/路径输入，再点安装。
5. 首次安装时系统可能要求允许本工具安装应用。点“前往授权”，允许此来源后返回，再点“安装更新”；取消授权仍可继续使用原版。
6. 按系统安装界面确认更新。安装后点系统“打开”或桌面图标启动。应用数据、文件夹授权、PDF、JSON、享做笔记和本工具备份不会由更新器清除。

普通应用不执行静默安装，也不强制退出或自动清除数据。系统安装时可能结束旧进程，所以未完成文件任务、恢复或清理时不会发起安装。已经下载但未点击安装的包不会因重开程序自行安装。

## 来源、校验与权限

更新只读取[本项目正式发行](https://github.com/Zerozero05/PDF_Bookmarks_Android/releases/latest)。只接受三段版本号及对应`v`标签，拒绝相同/更低版本、草稿、预发行版、其他仓库链接、缺失/重复附件或错误大小。下载仅访问允许的GitHub HTTPS主机，每次重定向先验证目标；不携带Token、不上传用户文档，不使用第三方更新服务器。

沿用原来的五附件，不要求新建更新清单：

- `PDF_Bookmarks_v{版本}_android.apk`
- 对应`.apk.sha256`
- `PDF_Bookmarks_v{版本}_android_source.zip`
- 对应`.zip.sha256`
- `SHA256SUMS.txt`

检查器读取GitHub发行信息和`SHA256SUMS.txt`；API提供APK的SHA256摘要时也必须一致。完整下载先放应用缓存临时文件，大小和SHA256通过后才生成完整APK。Android再核对包名、APK版本名、递增versionCode、最低系统及真实签名证书；包名固定`cn.local.pdfbookmarks`，证书必须同时匹配当前安装包和`SIGNING_CERTIFICATE.sha256`的原升级证书。不能用云端CI临时签名包覆盖正式安装。

新增权限只有`INTERNET`（获取更新）和`REQUEST_INSTALL_PACKAGES`（请求系统安装）；PDF处理仍可离线使用。不开启无障碍、全盘权限、后台启动或文档上传。系统对“允许此来源”的授权由用户控制。[Android安装来源规则](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())

校验后的APK通过只读`content:`地址临时交给安装器；提供者不对外开放目录，只有当前已登记并重新校验的单个APK可读取，不提供写入或删除接口。其他缓存、PDF原件、JSON、笔迹及恢复备份都不在可共享范围。[Android临时文件读取授权](https://developer.android.com/training/secure-file-sharing/share-file)

## 中断、失败与缓存

| 情况 | 处理 |
| --- | --- |
| 无网络、GitHub限流、超时 | 显示失败，可手动重试或打开发行页；原程序继续处理文件 |
| 草稿/预发行、版本更低 | 不提供更新，不降级 |
| 发行附件尚未传齐 | 停止检查，待维护者完成发布后重试 |
| 大小/哈希/签名/身份不符 | 不打开安装器，不修改现有程序 |
| 检查或下载取消 | 停止本次请求；本次未完成下载不注册为可安装文件，原文件保持 |
| 下载中断或系统结束进程 | 不会把部分包当完整包；再次选择下载会重新下载，不承诺断点续传 |
| 已完整下载后重启 | 重新核对缓存、版本和签名，再提供安装按钮；不自动安装 |
| 系统回收缓存或缓存变化 | 停止安装，提示重新下载 |
| 用户拒绝授权/取消系统安装 | 原程序保留，可回到工具稍后再安装 |

更新设置独立保存在应用内`app_updates`配置中；只记录自动检查开关、时间和已校验安装包信息，不迁移或重置原PDF任务配置。更新负载只在本工具私有缓存`updates/`，不写入用户选择的PDF/JSON文件夹。PDF统一清理按钮的范围保持原成功任务清单，不扩展到更新设置或其他内容。

## 以后发布新版本时

1. 同时更新根`VERSION`和`app/build.gradle`的递增`versionCode`；版本名是`1.0.1`这样的三段正式版本，code必须高于所有已发行APK。v1.0.0使用code12。
2. 沿用原升级私钥和包名。完成实现、回归、lint、签名、同提交CI以及本地原签名APK/源码/清单核验。CI调试附件不能代替升级附件。
3. 按既有发布流程，先展示具体版本、变更、兼容性、测试、发行说明及五附件，取得本次明确确认后再创建标签和公开Release。发行标题只用版本号，例如`v1.0.1`。
4. 标签用`v{VERSION}`，附件名称保持上方格式；一次上传同一候选生成的五附件，SHA清单中的APK行、实际大小和SHA必须一致。
5. 将完成上传的版本作为本仓库最新正式版（Latest），不勾选预发行。旧安装的v1.0.0或更高版本下次启动/回前台且到检查间隔时即可发现；手动检查可立即进行。[GitHub Latest Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
6. 发布后读回标签/Release/资产并实际下载核验，保留源码与升级私钥；不自动删除历史发行。

## 本次参考及验证边界

方法参考用户PC仓库提交`e9c006f531c447f1a5183fd073cc71e57bb6992f`的[AUTO_UPDATE](https://github.com/Zerozero05/PDF_Bookmarks/blob/e9c006f531c447f1a5183fd073cc71e57bb6992f/docs/AUTO_UPDATE.md)、[checker](https://github.com/Zerozero05/PDF_Bookmarks/blob/e9c006f531c447f1a5183fd073cc71e57bb6992f/updater/checker.py)和[manifest](https://github.com/Zerozero05/PDF_Bookmarks/blob/e9c006f531c447f1a5183fd073cc71e57bb6992f/updater/manifest.py)：采用每日自动/手动检查、稳定版筛选、下载前后验证、重定向前检查和空闲安装的方法。

Android代码独立实现，没有复制PC代码、引入新第三方代码或运行依赖。安装通过Android签名升级与系统安装器完成，不套用Windows EXE自替换/helper/健康回滚机制；旧PDF任务的恢复功能保持。

纯Java规则/网络故障测试、真实签名APK的宿主校验及构建检查不能证明华为系统实际授权/安装成功。新版设备验收见[DEVICE_ACCEPTANCE](DEVICE_ACCEPTANCE.md#v100-更新流程验收)，实际测试及局限见[VALIDATION](../VALIDATION.md#v100-自动更新候选--2026-10-21)。
