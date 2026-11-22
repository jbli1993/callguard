# CallGuard 0.1.0

首个公开版本。按号码归属地（省/市/运营商）拦截来电，判定在本机完成，拒接/静音交给系统 Telecom。

**主要面向中国大陆 +86 手机号**；号段库和省市判断都基于中国大陆的 7 位号段，境外号码只按「国际来电」开关处理。

## 下载

| 文件 | INTERNET | 说明 |
|---|---|---|
| `CallGuard-0.1.0.apk` | 无 | 推荐，完全离线 |
| `CallGuard-0.1.0-online.apk` | 有 | 可选在线标记，默认关闭 |

**两个包都不包含 `phone.dat`。** 需要按省市拦截时，请自行下载并导入：

- <https://github.com/xluohome/phonedata>（GPL-3.0）

应用内「导入新版 phone.dat」→ 选择文件即可，不需要存储权限。

在线版开启「在线查询」后，会把号码发给第三方标记服务；不填 Key 时使用 kit9 免费接口，填 Tmini Key 时走付费接口。拦截判定始终不联网。

## 安装

1. 安装 APK。
2. 打开 CallGuard →「授予来电筛选权限」，在系统页面授予「来电筛选」角色。
3. 导入 `phone.dat`，选择要拦截的省/市。
4. 打开「启用拦截」。使用系统拨号器时，建议同时打开悬浮窗或来电标签通知。

## 校验

SHA-256：

```text
71a4b61cb7dd62618848e454e2cd7262c72bc7b995c8b3965d8658d5a33c9233  CallGuard-0.1.0.apk
da709f65ac096555eb7f36aac6292f9cca886d3f7f7c702d57db9ab82f78acf2  CallGuard-0.1.0-online.apk
```

签名证书（两个包相同）：

```text
SHA-256 fdf85b711698095b0be8481db59f9fc6a193d4d93cc94161faf4666a3a384e11
```

```powershell
apksigner verify --print-certs CallGuard-0.1.0.apk
```

## 说明

- Android 13+，需要系统提供「来电筛选」角色
- 离线包没有 `INTERNET` 权限；在线包的拦截判定也从不联网
- 代码 GPL-3.0；号段数据由用户自行获取，不随 APK 分发
- 完整说明见仓库 README
