# 深海导航 · Subnautica Nav

《深海迷航》(Subnautica) 安卓手游的**存档定位**工具：把存档里的玩家世界坐标解析出来，叠加到地图上显示“你在哪”。不依赖 root，靠 **Shizuku** 授予的 shell(ADB) 权限读 `/sdcard/Android/data`。

## 成品下载

- v1.3 debug APK：<https://aka.doubaocdn.com/s/UFWGbiMoAY> （约 4.9 MB，debug 签名，直接安装）
- 源码自行编译见下方“编译”；每次 push 也会自动出 APK（Actions → Artifacts）

## 功能

- 点「更新地图」重新读一次存档 → 重画一次位置（位置来自存档，不是实时内存）
- 显示 X / Z 坐标、深度（m）、本次存档的游戏时长
- 支持多个存档槽切换（带时长与修改时间），或自动取最新
- 红点 + 朝向三角，**长按地图任意点**即可指定朝向（角度会记住）
- 双指缩放 / 拖动 / 双击回到玩家
- 右上角 GitHub 图标，点了跳到本仓库
- 底图可替换：优先读 `/storage/emulated/0/DeepSeaNav/map.png`，没有再用 APK 内置图

## 为什么不用 root

手机端 `adb shell` 的身份是 `uid=2000(shell)`，补充组里带 `ext_data_rw`、`sdcard_rw`，本来就有权读 `/sdcard/Android/data/<包名>`。Shizuku 能把这个身份借给普通 App，所以读存档不需要 root。

## 原理（三段）

1. **取文件**：Shizuku 反射调用 `Shizuku.newProcess()` 起一个 `sh -c "cat …/scene-objects.bin"`，拿到原始字节。
2. **解坐标**：存档是“一个 varint 长度前缀 + 一串 protobuf 对象记录”。定位到名字为 `Player` 的记录，在其内部找 `UnityEngine.Transform` 组件，位置藏在 `0a 15 "UnityEngine.Transform" 10 01` 之后：`0f` 标记 + 15 字节 = 3 个带 tag 的 float（x / y / z）。
3. **贴图**：地图按 `x∈[-2000,2000] → 横向`，`z∈[2000,-2000] → 纵向`（北在上）线性映射成像素点。

详细字段与逐字节结构见 [docs/存档格式.md](docs/存档格式.md)；注意事项（为什么要手动存档、底图怎么放、已知坑）见 [docs/注意事项.md](docs/注意事项.md)。

## 使用

1. 手机装 [Shizuku](https://shizuku.rikosage.dev/)，用「无线调试」方式启动（Android 11+），或用 root 启动。
2. 装本 App，打开，点「更新地图」→ 弹 Shizuku 授权 → 允许。
3. 进游戏 → 设置里**手动保存**一次 → 回 App 点「更新地图」。

> 位置是**存档那一刻**的，不是实时位置；想刷新就在游戏里手动存一次。手游自动存档（打开 PDA 触发、可设 2.5 分钟）也算。

## 编译

需要 JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）+ Gradle 8.7。

```bash
echo "sdk.dir=/你的/android/sdk" > local.properties
gradle assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk
```

也带了 GitHub Actions（`.github/workflows/build.yml`），push 后自动编译并把 APK 传成 artifact。

## 目录结构

```
app/src/main/java/com/doubao/subnauticanav/
├─ MainActivity.java   # 界面、Shizuku 授权、选存档、刷图
├─ MapView.java        # 自绘地图：缩放/拖动/长按定朝向/红点与三角
└─ SaveParser.java     # scene-objects.bin → 玩家坐标
```

## 底图说明（重要）

仓库**不附带**任何地图图片。App 支持把你自己准备的地图放到：

```
/storage/emulated/0/DeepSeaNav/map.png      # 推荐
/storage/emulated/0/深海导航/map.png
```

要求：正方形、坐标范围 -2000~2000 全覆盖。也可以放到 `app/src/main/assets/subnautica_map.png` 一起编进 APK。

## 已知限制

- 只适用于包名为 `com.unknownworlds.subnautica` 的手游移植版，路径写死 `files/SavedGames`（兼容旧版 `SNAppData/SavedGames`）。
- 存档里**没有玩家朝向**（Player 的 Transform 旋转是单位四元数），所以朝向只能手动指定。
- 坐标系按 ±2000 线性铺满整图，若底图带边距需要自行微调映射。
- Shizuku 重启后需重新启动（无线调试方式），除非用 root 启动。

## 免责

仅供单机存档可视化，不修改游戏数据；请勿用于联机作弊。游戏及相关素材版权归 Unknown Worlds / 移植方所有。

## License

代码采用 MIT，详见 [LICENSE](LICENSE)。
