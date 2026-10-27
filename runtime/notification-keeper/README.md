# 聊天通知保活（项目内置）

Java 入口在 `app/src/main/java/dev/dsh/notifykeep/`，随主 APK 编译；三个固定脚本由 app Gradle 的 assets source set 打进 APK。运行时不再依赖外置 `keeper.jar`。

## 自动部署与升级

- `CoworkConnection.connect` 在启动 Broker 前调用 `ChatKeeperRuntime.ensureInstalled`，把 APK 内脚本提取到 App 私有目录，再经 KernelSU 复制到 `/data/local/dsh-native/chat-notify-keeper/`，保留 `disabled`。
- 安装 `/data/adb/service.d/continuo-chat-notify.sh`；独立 root 监视进程每 30 秒检查 keeper。停用时监视仍在，便于恢复。
- keeper 的 `CLASSPATH` 每次由 `pm path --user 0 dev.dsh.pocket` 解析；APK 路径或 SHA-256 改变，只重启 keeper，等待旧进程释放令牌后启动新代码。不重启 Broker、不重建副屏。
- 已部署的独立 jar 版本会在首次安装时迁移：停止旧监视循环，替换 keeper 生命周期，重启为 APK 入口。旧 jar 可暂留作备份但不再使用。
- 监视程序连续三次成功列包却看不到主用户 Continuo 时停止 keeper、移除开机入口；包管理器暂时错误不作为卸载依据。仅清理本功能入口，保留日志与禁用状态。

新设备须先装主 APK、准备现有 root/工作资料环境，再正常连接一次 Cowork 才会安装本功能。APK 更新后的监视会加载新 Java 类；更新脚本在 App 下次连接时刷新。未新增 Android 服务或开机接收器，KernelSU 是开机入口。

## 范围与生命周期

只保护验证过的工作资料 user 10（serial 10、parent 0、资料拥有者及其签名固定）中名单上应用的已存活进程：主进程，加上 QQ 的 `:MSF` 和名字里带 push 的进程（微信的 `:push`），其他进程（微信的 `:br`、`:patch`、WebView 沙箱等）不管。名单在 `/data/local/dsh-native/chat-notify-keeper/packages`（root 所有，一行一个包名，最多 8 个），没有这个文件时是 QQ、微信；App 在“设置 → 它的手机 → 保持哪些应用”里改。keeper 启动时读一次名单；`keeper.sh start` 把名单的哈希并进 `apk.sha256`，名单一变就像 APK 更新一样重启 keeper。`setProcessImportant` 让 AMS 分类为 `force-imp`，而不是只改内核回收分数。每 10 秒核对 UID/PID/进程名/出生时间，避让外部重要性令牌与前台服务。不拉起已死亡或强行停止的应用，不改冻结/全局省电，不恢复全量 ParkedApps。

手机重启自动启动的是 keeper；聊天应用本身仍按懒启动，需正常打开后才被保护。重建副屏、重启 Broker、关闭 App 不停止独立 keeper。保护接口不是不可杀进程；PID-only 调用仍有极小复用竞态，Binder death 在 AOSP 上可能影响目标后来新增的前台服务标记；真实通话/长时锁屏收信尚待验收。

## 运维与退出

App 里的开关在“设置 → 它的手机 → 后台保持在线”，做的就是下面的 `disable` 和“恢复”，并显示哪个应用正在被保护；下面一行“保持哪些应用”写名单。安装失败不挡 Cowork 连接，原因显示在那一行，点一下重试。

```sh
# 状态
su -c 'sh /data/local/dsh-native/chat-notify-keeper/keeper.sh status'
# 持久停用，后续 App 升级/连接不自动启用
su -c 'sh /data/local/dsh-native/chat-notify-keeper/keeper.sh disable'
# 恢复；监视继续存在
su -c 'rm -f /data/local/dsh-native/chat-notify-keeper/disabled; sh /data/local/dsh-native/chat-notify-keeper/keeper.sh start'
# 移除本功能开机入口并停止；保留 disabled 防止下次连接重启
su -c 'sh /data/local/dsh-native/chat-notify-keeper/keeper.sh uninstall'
```

项目测试：`ChatKeepPolicyTest` 的 19 项策略回归及 3 项打包/连接/动态 APK 源码合同已登记 `test-unit.ps1`。构建同主 APK，无额外 Java/D8 工具链或下载步骤。

## 本次验证记录（2026-10-26）

- 主 APK `assembleDebug` 与测试编译成功，`test-unit.ps1 -SkipBuild` 实跑 **423 项全部通过**。
- 设备 `5b9a17cd` 已安装集成 APK，通过正常控制服务 CONNECT 入口自动提取/安装脚本，未手工代替 App 执行安装器。
- 旧 jar keeper/旧监视循环已迁移成 APK keeper PID 8560 与独立监视 PID 8645；重复 `keeper.sh start` 前后仍为同一 PID，没有新起第二个实例。
- QQ/微信四目标仍为 `force-imp`、adj=200；APK 的动态 CLASSPATH 路径与哈希记录已生成。整个迁移未停止 Broker、未重建副屏。
- 迁移前备份：手机 `/data/local/tmp/continuo-chat-integration-20261026/` 下 `prev-app.apk`、`previous-keeper/`、`previous-service.sh`。即时回滚优先用上方 `disable` 停用新增保护；需要完整版本回退时再停用 keeper、装回备份 APK 并恢复备份脚本。
- 最终 APK（包含开机残留锁校验修正）再次构建并覆盖安装成功，keeper 已自动换代为 PID 9878；安装包 SHA-256 为 `83e35595d02785df61deec5780453684a014eed98aeb81dd940023243944edee`，日志确认四个收信进程重新保护。前述 PID 8560 是上一轮集成验收快照。
- 整机重启、真实对端消息/长时锁屏收信仍未执行，未列为通过。
