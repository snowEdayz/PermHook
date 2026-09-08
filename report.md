# AppStartConfirmDialogActivity 绕过路径分析

## 结论

分析对象为当前目录中的 `权限管理_16.02.007.apk`、`oplus-services.jar` 和 `oplus-framework.jar`。在该 APK 的静态预置配置中，匹配下面三类规则时，system_server 的活动启动检查返回 `-1`，因此不会构造并启动 `com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity`：

1. `src_pkg`：发起启动的 caller 包名匹配。
2. `dst_pkg`：被启动的 target 包名匹配。
3. `activity`：完整的 `包名/组件名` 匹配。

当前静态 asset 中共有 62 个 `src_pkg`、67 个 `dst_pkg`、2 个 `activity`，没有 `action` 项。

## 静态预置名单

### caller 包名（`src_pkg`，62 个）

```text
com.coloros.shortcuts
com.nearme.gamecenter
com.oneplus.brickmode
com.oplus.games
com.coloros.gamespaceui
com.oplus.ipspace
com.oplus.studycenter
com.morph.reducio
com.finshell.wallet
com.heytap.themestore
com.nearme.themespace
com.heytap.market
com.heytap.yoli
com.coloros.yoli
com.heytap.quicksearchbox
com.heytap.browser
com.oplus.appdetail
com.heytap.pictorial
com.heytap.music
com.coloros.wallet
com.oppo.music
com.heytap.reader
com.oppo.reader
com.heytap.book
com.oppo.book
com.tencent.mm
com.coloros.filemanager
andes.oplus.documentsreader
com.coloros.note
com.uusafe.portal
com.oppo.quicksearchbox
com.oplus.play
com.nearme.play
com.nearme.themestore
com.oppo.community
com.oppo.store
cn.wps.moffice.lite
com.coloros.calendar
com.viva.todo_list.p3
com.oppo.marketdemo
com.opposs.marketdemo
com.opposs.marketdemo1
com.opposs.marketdemo2
com.opposs.marketdemo3
com.opposs.marketdemo4
com.opposs.marketdemo5
com.opposs.marketdemo6
com.opposs.marketdemo7
com.opposs.marketdemo8
com.opposs.marketdemo9
com.opposs.marketdemo10
com.opposs.marketdemo11
com.opposs.marketdemo12
com.opposs.marketdemo13
com.opposs.marketdemo14
com.opposs.marketdemo15
com.opposs.marketdemo16
com.opposs.marketdemo17
com.opposs.marketdemo18
com.opposs.marketdemo19
com.market.heydemo
com.oplus.heydemo
```

### target 包名（`dst_pkg`，67 个）

```text
com.tencent.mobileqq
com.tencent.mm
com.android.email
com.coloros.alarmclock
com.coloros.backuprestore
com.coloros.calculator
com.coloros.calendar
com.coloros.compass2
com.coloros.familyguard
com.coloros.favorite
com.coloros.filemanager
com.coloros.note
com.coloros.shortcuts
com.coloros.soundrecorder
com.coloros.translate
com.coloros.weather2
com.finshell.wallet
com.heytap.book
com.oppo.book
com.heytap.health
com.heytap.music
com.heytap.smarthome
com.heytap.yoli
com.nearme.gamecenter
com.oneplus.brickmode
com.oplus.consumerIRApp
com.oplus.games
com.oplus.melody
com.oplus.member
com.oppo.usercenter
com.oplus.play
com.oppo.community
com.oppo.store
com.redteamobile.roaming
com.unionpay.tsmservice
com.heytap.themestore
com.nearme.themespace
com.heytap.reader
com.oppo.reader
com.coloros.oppopods
com.coloros.pictorial
com.coloros.yoli
com.coloros.wallet
com.coloros.gamespaceui
com.coloros.operationtips
com.oplus.ipspace
com.oplus.studycenter
com.heytap.market
com.heytap.quicksearchbox
com.heytap.browser
com.oplus.appdetail
com.heytap.pictorial
com.oppo.music
andes.oplus.documentsreader
com.antutu.benchmark.full
com.uusafe.frame.nut
com.oppo.quicksearchbox
com.autonavi.minimap
com.tencent.map
com.baidu.BaiduMap
com.tencent.wemeet.app
com.nearme.play
com.nearme.themestore
cn.wps.moffice.lite
com.eg.android.AlipayGphone
com.heytap.wearable.retaildemo.phone
com.oplus.earbuds.retaildemo
```

### 精确组件（`activity`，2 个）

```text
com.tencent.qqmusic/com.tencent.qqmusic.third.DispacherActivityForThird
com.baidu.youavideo/com.mars.united.jkeng.launcher.LaunchActivity
```

## 不是固定名单的其他绕过条件

“哪些 App 不经过该 Activity”不能只由包名决定。`OplusAppStartConfirmManager.checkStartActivityForConfirm` 在进入确认 Activity 前还会跳过以下情况：

- 从 Home/Launcher 发起的普通启动（代码检查 `isCalledFromHome`，并检查顶部包名 `com.android.launcher`）。因此从桌面直接打开 App 时，可能表现为几乎所有 App 都不经过该 Activity。
- system app、同包启动、系统进程或 SecurityPermission 自身。
- `SEND`、`PICK`、`CHOOSER` 等 Intent。
- 预加载、已有 Activity/应用实例、多窗口/部分灵活窗口场景、Mirage display、特定 Oplus flag，以及近期重复启动历史。
- Mini Program 和 SecurePay 专用逻辑返回 `-1` 的路径。

## 动态配置影响

APK 中的 `permission/activity_start_whitelist.xml` 只是本地默认 asset。`d9.n` 会把它与 GrayProductProvider 的远程/灰度配置合并，再通过 `ISecurityPermissionService.putActivityStartWhiteList` 发送给 system_server。用户已经允许的启动关系还会写入系统配置文件。因此实际设备上的名单可能与上述静态名单不同。

system_server 的关键逻辑位于：

- `services_jadx/sources/com/android/server/am/OplusActivityStartController.java`
- `services_jadx/sources/com/android/server/wm/OplusAppStartConfirmManager.java`
- `jadx_out/sources/com/oplusos/securitypermission/permission/ui/AppStartConfirmDialogActivity.java`

静态列表原文位于：

- `jadx_out/resources/assets/permission/activity_start_whitelist.xml:3-133`

关键源码位置：

- `OplusActivityStartController.java:931-970`：黑白名单判断，`src_pkg`、`dst_pkg`、`activity` 命中返回 `-1`。
- `OplusActivityStartController.java:432-447`：本地/系统预置配置加载。
- `OplusAppStartConfirmManager.java:51-105`：弹窗前的总闸门以及向 SecurityPermission 发起检查。
- `AppStartConfirmDialogActivity.java:67-69`：确认 Activity 读取 caller/target 包名；它是展示阶段，不是最终的判定位置。

## IDA Pro 交叉验证

已用 IDA Pro MCP 检查 APK 的 `classes2.dex` 和 `oplus-services.jar` 中的 DEX：

- `ida_input/classes2.dex.i64`：标注 `parseActivityStartWhitelistAsset`、`updateActivityStartWhitelistIfNeeded`、`parseAppStartConfirmIntentExtras`。
- `ida_services_input/classes.dex.i64`：标注 `evaluateActivityStartPresetLists`、`buildAppStartConfirmIntent`。
- `ida_services_input/classes2.dex.i64`：标注 `OplusAppStartConfirmManager` 的总闸门逻辑。

DEX 在当前 IDA 环境中没有 Hex-Rays 伪代码插件，但通过 IDA 反汇编、字符串交叉引用和 JADX 源码对照确认了上述判断链。
