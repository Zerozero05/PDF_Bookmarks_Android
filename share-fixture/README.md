# 独立身份的分享验证源

仅在 `-PfixtureTests=true` 时加入构建。没有运行依赖或用户文件入口；provider保持 `exported=false`、`grantUriPermissions=true`。所有内容均在本模块应用缓存中生成，不读取共享存储。

构建（由根代理集中执行）：

```powershell
./gradlew.bat :app:assembleFixture :app:assembleFixtureAndroidTest :share-fixture:assembleDebug -PfixtureTests=true --offline
```

主验证应用为 `cn.local.pdfbookmarks.testhost`，验证组件为 `cn.local.pdfbookmarks.testhost.test`。默认不加参数时仍沿用正式主应用的debug构建与debug测试，不额外构建该模块。

源应用为 `cn.local.pdfbookmarks.sharefixture`，显式控制Activity为 `cn.local.pdfbookmarks.sharefixture.ShareFixtureActivity`。Activity由signature级CONTROL权限保护，隔离testhost声明该权限，需从同签名testhost的Context启动；普通ADB shell不能直接启动该Activity。Activity只向隔离testhost发送，不接受任意目标包名。provider没有signature读取例外，仍须真实单URI读写授权。通过Intent extras控制：

| 参数 | 默认 | 可用值／意义 |
| --- | --- | --- |
| `operation` | `share` | `share`、`revoke`、`status`、`mutate`；mutate只把已存在的deletable.json换为固定Other标题，不能传入任意内容 |
| `run` | `run` | 每个场景应使用新ID，限1–64位字母数字、`_`、`-` |
| `file` | `readonly.json` | 下方固定文件名之一 |
| `mode` | `SEND` | `SEND`、`VIEW`、`SEND_MULTIPLE` |
| `read` | `true` | 是否添加来源的临时读取授权flag |
| `write` | `false` | 是否添加来源的临时写授权flag；仅deletable支持删除 |
| `document` | `false` | true时走标准DocumentsProvider单文档URI，供正向删除与变更保护验证 |
| `clip` | `both` | `both`同一URI在Extra/ClipData；`clip`仅ClipData；`extra`仅Extra（系统可能补ClipData）；`mismatch`使Extra与ClipData不同 |
| `grantPrimary` | `false` | 仅歧义测试：来源所有者额外给主Extra URI精确READ授权；与mismatch组合产生两个真正可读的不同JSON，不能用其证明普通分享本来就有授权 |
| `files` | `other.json` | SEND_MULTIPLE附加的固定文件名数组；不重复首项 |

JSON固定内容为3条／2层目录，物理页1、2、3；`fixture.pdf`由Android PdfDocument生成3页。可用JSON：`readonly.json`、`deletable.json`、`other.json`（不同标题）、`broken.json`（读取故障）、`slow.json`（open延迟1.5秒）、`oversize.json`（大于4MB）、`malformed.json`（语法错误）。不提供任意内容、路径、insert或update功能。

URI为 `content://cn.local.pdfbookmarks.sharefixture.files/files/<run>/<file>`。获得读取授权后，可对同一URI query `_display_name`、`_size`、`flags`、`fixture_open_count`、`fixture_delete_count`、`fixture_exists`。事件也记录在源应用私有 `fixture_events` 偏好及 `ShareFixture` 日志中；撤权后不得通过provider查这些值来绕过权限，可由源Activity的status操作或ADB只读检查。

status操作还向testhost发送 `cn.local.pdfbookmarks.sharefixture.STATUS` 广播（双方需CONTROL签名权限），携带run/file/open/delete/exists及原请求token。测试可注册带CONTROL发送方权限约束的receiver，在删除文档导致精确URI授权被系统撤回后，仍通过源所有者核实合成文件是否存在；不能通过给目标应用补权限来查询已撤权的provider。

控制Activity同时处理首次启动和已有实例的新Intent。status广播在控制Activity的onDestroy中发送，测试再等待testhost窗口恢复焦点后才发起下一次控制请求；不能把尚未结束的控制Activity内发送广播当成已返回，也不用固定等待时间替代生命周期条件。

`document=true` 改为 `content://cn.local.pdfbookmarks.sharefixture.documents/document/<encoded run/file>`。额外DocumentsProvider按平台要求为exported=true，同时受系统 `MANAGE_DOCUMENTS` 权限保护并允许精确URI授权；testhost不请求该系统权限，无grant仍应读取失败。它只委托同模块生成的固定合成文件，queryRoots为空，不在文件管理器增加浏览位置。原.files provider始终非导出，DocumentsProvider正向删除不代表QQ只读FileProvider可删除。这个标准形态依据[AOSP DocumentsProvider.attachInfo](https://android.googlesource.com/platform/frameworks/base/+/7e391cb8acc36eb7a9f489e60e127ca8dfab37ac/core/java/android/provider/DocumentsProvider.java)，该方法会拒绝非导出或缺少MANAGE_DOCUMENTS保护的声明。

该代码只构造验证条件，不代表构建或实机测试已经通过。安装、设备操作、备份和清理由根代理按本轮授权统一处理。
