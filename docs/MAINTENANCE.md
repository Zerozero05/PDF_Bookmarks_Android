# 更新与维护

适用当前PDF＋JSON版。首次交接或续接先读根WORK_CHECKPOINT.md及最新工作交接，再读[现行需求](REQUIREMENTS.md)与VALIDATION.md；不能用旧无障碍工程替代本工程。

## 代码与兼容性

MainActivity负责选文件、两栏预览和用户确认；TocParser负责两种JSON和映射；PdfBookmarks负责增量写入/结果校验；PathDocuments/StagedDocuments负责真实SAF身份与授权；StagedReplacement负责可恢复改名；BackupCleanup只负责用户确认后的旧备份门禁。精确修改对应模块，不重写未改变的引擎。

保持cn.local.pdfbookmarks、既有SharedPreferences键、files/pdf-backups目录及两种输入格式兼容。新增字段缺失必须保守处理：旧版没有lastReplacement关联时不能猜删备份。升级不清除应用数据，不自动执行pending任务，不自动删除内部备份。v0.1.8仅在用户确认完整清单后允许统一清理本次关联内部备份；清理后不能恢复。pendingCleanup保存授权快照，未完成时阻止新写入；可先保存原快照到files/cleanup-records后结束清理并保留所有剩余旧件，不能丢弃记录来获得干净状态。未来如需迁移，先保存原值，失败保留原数据，并补真实兼容回归。

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

当前是本地个人测试APK，测试签名不代表应用商店正式生产签名。Android官方说明更新涉及应用签名，丢失自己管理的签名密钥会失去原应用的更新能力；debug证书也不适合作为应用商店发行签名。未来正式发行需先制定已安装用户的数据和签名迁移方案，不能靠卸载清数据解决。[Android签名说明](https://developer.android.com/studio/publish/app-signing)

GitHub Actions使用runner临时测试签名，只证明源码构建/回归，不作为本机应用的覆盖更新包。不得把私钥明文提交到工作流、仓库或公开日志；远端签名发布需另行明确授权与凭据配置。

## 设备验收、GitHub与清理

按[DEVICE_ACCEPTANCE](DEVICE_ACCEPTANCE.md)填写真实结果，安装/授权/文件提供者/享做刷新必须与电脑测试分开。发生错误先保留应用数据、导出原件备份，记录选定路径、版本、操作和具体提示；不要反复盲点替换或删除。

公开仓库与发行约定见[GITHUB_PLAN](GITHUB_PLAN.md)，本次新仓及v0.1.9测试版已获授权。提交只包含审核过的源文件与维护配置；verification/和work/等已忽略，不上传用户书籍、日志、私钥或本机路径配置。CI失败先修复再发布，既有Release保持不变。

已采用A1/A2/A3：现有Android CI在提交和PR上构建、lint及单元测试，成功或失败均尝试保留14天诊断；CI附件使用runner临时签名，不能作为覆盖升级发行包。发布先核验本地APK及同提交CI，再上传草稿并核对名称、大小、SHA-256与资产集合，公开后读回并实际下载核验。Dependabot按月检查Gradle和GitHub Actions，分别分组，普通更新PR上限分别为2和1；更新须人工审查兼容性、许可与回归，不自动合并或发布。配置提交不代表机器人已成功完成首次检查，以实际运行记录为准。

本地文件默认保留，工作完成不自动清理；只有用户明确请求清理或肯定答复本次具体清理询问后才执行。获准清理时先写读回交接，并展示完整绝对路径清单及重建依据；仅逐项删除已确认无依赖的可重建临时物，再移除已空目录。保留源码、历史/最终交付、原输入、诊断证据、私钥与未来构建依赖。平板已安装程序不依赖电脑的fixture目录；应用内按用户选项处理本次关联旧件的功能不受宿主保留规则影响。
