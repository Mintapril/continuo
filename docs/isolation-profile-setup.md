# 创建 Cowork 隔离测试资料

> 这是兼容性实验，不是已经可用的并行 Cowork。系统资料授权由手机持有人确认；不使用命令强制创建、跳过同意或替换现有资料。不要在创建阶段导入个人账号或重要文件。

## 当前准备结果

- 用户已完成系统创建。固定设备 `5b9a17cd` 最新只读核验：个人用户 0；工作资料 userId=10、serialNumber=10、parentId=0，状态 `RUNNING_UNLOCKED`，拥有者为 `dev.dsh.pocket.isolation/.ProfileAdminReceiver`。不要再次执行创建步骤。
- 系统创建入口解析为 `com.android.managedprovisioning/.preprovisioning.PreProvisioningActivity`，检查时只有一个匹配项；这不是已完成系统授权的证据。
- 新应用包名 `dev.dsh.pocket.isolation`，名称 **Cowork 隔离实验**。独立于现有 Pocket；不会修改 Code、Cowork 生产守卫、会话或凭据。最低 Android 13，确保离线创建参数有平台支持；测试签名但关闭调试标志。
- 已构建并安装到个人用户 0；6 项一次性创建保护测试通过，签名与最终 APK 权限检查通过。安装后仍只有用户 0、无拥有者、仅物理显示；未启动系统创建。见[构建与安装证据](<../test-evidence/cowork/isolation-provisioning-build.json>)。
- 资料创建已由用户确认并经系统元数据复核；并行焦点、同包独立数据和副屏输入仍未验收。DSH 的共享目录已另行切到该资料，见[存储迁移证据](<../test-evidence/cowork/work-profile-storage.json>)；这不等于现有手机工具已切入工作资料。

## 在手机上操作

1. 打开个人空间里的 **Cowork 隔离实验**。
2. 确认页面显示「可以发起系统确认；尚未创建资料」。如果显示已有资料或系统不允许，停止并反馈原文，不卸载或删除其他资料。
3. 点击 **创建测试工作资料（系统确认）**，阅读应用确认内容，再进入 Android/ColorOS 的系统确认页。
4. 系统可能显示「工作资料」「组织管理」等标准文案。此应用不连接企业服务器，但会成为新资料的**资料拥有者**，因此系统授予的资料管理能力是真实存在的，不应视为普通应用权限。
5. 若显示资料就绪页，**先点击「我已了解，完成」，再返回对话**报告结果。按返回键会取消完成步骤，按主页键只会让流程停留后台。由只读元数据再次核对真实 userId、父资料和拥有者，不能把同步返回 `RESULT_OK` 当作完整成功。

当前阶段不会启动测试副屏。后续监督测试会单独开始，测试过程中可能短暂发生焦点/键盘干扰；出现串屏、抢焦点或身份异常即停止。

## 此版本实际做什么

| 项目 | 实现边界 |
|---|---|
| 创建方式 | 仅显式按钮与确认框；`ACTION_PROVISION_MANAGED_PROFILE`；立即解析唯一平台签名系统 Activity，并绑定显式组件；不退回选择器 |
| 重复启动 | 打开系统流程前同步保存一次性尝试标记；多 Activity、进程重启、失败或取消都不自动解除，避免重复创建 |
| 模式 | 只选择系统允许的 managed-profile；不选择 fully-managed/device-owner |
| 拥有者操作 | 验证本进程位于工作资料且本包是有效拥有者后，只设置资料名称并启用 |
| 传统管理员策略 | 空 `uses-policies`，不声明 wipe-data、force-lock 或密码策略 |
| 应用权限 | 不请求网络、账号、存储、传感器、无障碍或 root；空传统策略不意味着系统资料拥有者角色没有其他能力 |
| 配置 | 不迁移账号、不复制个人文件、不安装其他应用、不设置跨资料分享、证书、VPN 或密码 |
| 导出组件 | 只有启动页面对普通应用公开；管理员与系统回调组件由 `BIND_DEVICE_ADMIN` 保护 |
| 删除 | 应用没有删除资料、清除用户数据的功能；任何后续主动移除需另行确认 |

Android 自身可能在创建失败或中途取消时回滚**本次新建**资料。这与应用主动删除不同，不能承诺系统永不回滚。已有个人数据不属于本实验修改范围；不要在未完成创建的资料中存放重要内容。系统还会保留所需的系统应用，因此「只装测试应用」是指不额外安装用户应用，不意味着资料内没有系统组件。

## 拒绝与恢复

- **已有资料或身份不明**：不继续，先只读核实；不自动移除旧资料腾位置。
- **系统不允许/无法安全确认条件**：不使用 root 命令绕过，报告界面原文。
- **创建被取消/失败**：只读核对新资料是否被系统回滚，既不自动重试也不主动删除残留资料。此一次性实验版本会保留尝试标记；不要自行清除应用数据、卸载重装或反复点击创建来绕过。若要再次尝试，先核实系统真实状态并另行安排。
- **未来不再使用测试资料**：先确认准确的测试 userId、拥有者和其中数据，再单独询问是否删除；本应用没有一键擦除入口。

## 实现与依据

- [应用清单](<../isolation/src/main/AndroidManifest.xml>)、[创建界面](<../isolation/src/main/java/dev/dsh/pocket/isolation/IsolationActivity.java>)、[系统入口验证](<../isolation/src/main/java/dev/dsh/pocket/isolation/SystemProvisioning.java>)。
- [资料拥有者回调](<../isolation/src/main/java/dev/dsh/pocket/isolation/ProfileAdminReceiver.java>)、[允许模式处理](<../isolation/src/main/java/dev/dsh/pocket/isolation/ProvisioningModeActivity.java>)、[完成页](<../isolation/src/main/java/dev/dsh/pocket/isolation/ProfileReadyActivity.java>)。
- [Android 16 DevicePolicyManager 源码](<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/admin/DevicePolicyManager.java>)：创建失败回滚、同步结果不等于完整成功，以及离线创建参数的系统约定。
- [并行架构与验收条件](<cowork-concurrent-design.md>)：工作资料不是虚拟机，副屏窗口 containment 和系统资源共享仍需验证。
