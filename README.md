# PermHook

PermHook 是一个基于 Modern Xposed API 102 的 Oplus/ColorOS 活动启动确认模块。

## 功能

- Material Design 3 设置界面。
- 使用规则指定 `caller package -> target package` 两项匹配条件。
- 未填写的包名保存为 `*`，表示匹配任意包。
- 包名完整填写 `any_user` 时匹配任意非系统 User App；只有完整等于 `any_user` 才启用该语义。
- 命中规则后可选择经过 Activity 确认或直接启动应用。
- 规则支持 `*` 通配符，且只对不同包之间的启动生效。
- 规则列表按从上到下的顺序匹配，顶部优先级最高；长按规则卡片可拖动排序，新规则默认插入顶部。
- 同包启动始终直接允许，不经过确认 Activity；编辑器也禁止保存相同的具体调用方和目标包名（`*/*`、`any_user/any_user` 除外）。
- “经过 Activity 确认”操作会走 `com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity`。
- 使用 Modern Xposed Remote Preferences 在设置 App 与 `system_server` 之间同步规则。

规则示例：

```text
启动方：com.example.source
目标包：com.example.target
操作：经过 Activity 确认
```

这条规则会匹配目标包内的启动，并经过 `AppStartConfirmDialogActivity` 确认；也可以把操作改为“不经过 Activity 确认”，让命中规则的启动直接继续。设置 App 本身和确认 Activity 会被排除，避免确认流程递归。确认页面放行原始 Intent 时会携带由 `system_server` 登记的一次性随机令牌，防止同一启动再次被强制拦截；普通应用伪造 extra 名称或布尔值不会获得放行。

`any_user` 可以分别用于调用方包名或目标包名；例如调用方填写 `any_user`、目标填写具体包名时，只匹配非系统 User App 发起的启动。

## Modern Xposed 配置

- `META-INF/xposed/java_init.list`：Modern API 入口类。
- `META-INF/xposed/module.prop`：API 101/102 声明。
- `META-INF/xposed/scope.list`：使用 Modern API 命名的 `system`，即 `system_server`。

模块针对当前分析的 Oplus 固件 hook：

```text
com.android.server.wm.OplusAppStartConfirmManager
  .checkStartActivityForConfirm(...)
```

这个类名和返回结构属于 Oplus 私有实现；不同 ColorOS/Oplus 版本可能需要单独适配。

## 构建

本地需要 JDK 21、Gradle 9.5.1、Android SDK 37 和 Build Tools 37.0.0。GitHub Actions 会自动安装这些依赖。

```bash
gradle assembleDebug
```

release 签名从 Gradle properties 读取，不把私钥提交到仓库：

```text
signing.storeFile=/path/to/release.jks
signing.storePassword=...
signing.keyAlias=...
signing.keyPassword=...
```

## GitHub Actions Secrets

仓库的 release workflow 使用以下 Repository Secrets：

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Actions 会把 keystore 写入 runner 临时目录，构建结束后由 runner 清理；源码仓库只保存 workflow 和签名配置约定。

## 安装与启用

1. 安装 LSPosed 2.x/支持 Modern Xposed API 102 的框架。
2. 安装构建出的 APK。
3. 在 LSPosed 中启用 PermHook，确认 `system` 作用域已生效。
4. 重启 `system_server` 或重启设备。
5. 打开 PermHook，确认 Remote Preferences 已连接后添加规则。
