# 构建说明（NeoForge 1.21.1）

本工程提供两种构建方式。

## 方式一：默认 `build.gradle`（推荐，离线可用）

直接对 `libs/` 目录下的 jar 做 `compileOnly` 编译，不需要 NeoForm Runtime。

```bash
./gradlew build
```

产物：`build/libs/key_panel-<版本>.jar`

需要 JDK 21。

`libs/` 目录需要包含：

| 文件 | 说明 |
|---|---|
| `minecraft-1.21.1-official.jar` | Minecraft 1.21.1 客户端（官方名映射） |
| `neoforge-21.1.251-universal.jar` | NeoForge 本体 |
| `fancymodloader-loader-*.jar` | FML（`@Mod` / `FMLPaths` / `EventBusSubscriber`） |
| `bus-*.jar` | `net.neoforged.bus` 事件总线 |
| `mergetool-*-api.jar` | 含 `net.neoforged.api.distmarker.Dist` |
| `sponge-mixin-*.jar` | Mixin（用于关闭背景模糊） |
| `mc/` | Minecraft 的传递依赖 |

> 这些 jar **不随仓库分发**（其中 Minecraft 客户端受 Mojang 许可限制）。请自行从对应官方来源获取。

## 方式二：官方工具链 `build-moddev.gradle`

使用 ModDevGradle，需要能访问 NeoForged 官方 Maven：

```bash
./gradlew -b build-moddev.gradle build
```

## 为什么默认方式可行

NeoForge 1.20.5 起运行时使用 Mojang 官方名称（不再使用 SRG 中间名），
因此直接对官方名映射的 Minecraft jar 编译，产出**无需 reobf** 即可被 NeoForge 加载。
