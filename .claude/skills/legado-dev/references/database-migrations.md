# Room 数据库与迁移

数据库变更关系到用户持久数据。先还原真实 schema 演进，再选择 Room 能正确表达且可测试的迁移方式。

## 调查清单

开始前共同核验：

1. 相关 `@Entity`、嵌套对象、索引、外键和 TypeConverter。
2. 相关 DAO 查询、写入、事务及调用方。
3. `AppDatabase.kt` 中 `@Database` 的 entities、version、autoMigrations 和数据库构建方式。
4. `app/schemas/` 中当前版与前序版本的导出 schema。
5. `DatabaseMigrations.migrations` 已注册的手动迁移及最早支持路径。
6. `app/src/androidTest/` 中真实 `MigrationTest`、测试资产和 runner 配置。

当前仓库在 `AppDatabase.kt` 定义顶层全局实例 `appDb`。使用前仍需复核其声明、初始化和线程语义。禁止发明 `AppDatabaseInstance` 或其他不存在的单例 API。

## 先判断是否改变 schema

不改变持久化 schema 的代码修改，不应提升数据库版本。例如只改业务逻辑、UI、非持久字段或 DAO 调用方时，先证明 schema 未变。

若改变 schema，则作为同一个原子变更同步处理：

- Entity、索引、外键或持久字段；
- 受影响 DAO 与查询；
- `AppDatabase` entities、version 和迁移声明/注册；
- 导出 schema；
- 迁移测试和业务数据断言。

只升版本或只改 Entity 都不完整。

## 迁移方式决策

### 普通 AutoMigration

仅用于 Room 能从相邻导出 schema **无歧义推导** 的简单兼容变更，例如满足 Room 限制的新增可空列或有默认值的列。必须以实际编译和导出 schema 结果证明可推导，不能仅凭经验判断。

### AutoMigration + AutoMigrationSpec

用于需要显式告诉 Room 语义的自动迁移，例如列/表重命名或删除。使用适配当前 Room 版本的注解，并核对生成结果；Spec 不是复杂数据转换的替代品。

### 手动 Migration

以下情况优先手动迁移：

- 需要数据回填、格式转换或多步 SQL；
- 需要条件判断、临时表、聚合或跨表处理；
- Room 无法从 schema 推导，或自动结果不满足数据语义；
- 需要显式保留、去重或修复历史数据。

手动迁移必须加入当前 `DatabaseMigrations.migrations` 注册链，并与 `AppDatabase` 的构建配置共同核验。

## 不可用的捷径

- 不删除或改写已发布的历史 schema 和迁移来让当前测试通过。
- 不用 `fallbackToDestructiveMigration` 或同类破坏性回退掩盖缺失路径。
- 不假设新装测试能覆盖升级用户。
- 不把 Room 编译成功、普通 JVM 测试或 APK 构建成功称为“迁移通过”。

如果产品需求本身允许清空数据，必须先得到明确授权，并把数据影响写入交付说明；这不等同于常规迁移。

## 测试要求

至少覆盖：

1. **上一版 → 新版**：创建代表性旧数据，执行迁移，断言表结构、索引、约束和关键业务数据均保留或按设计转换。
2. **最早支持版 → 最新版**：保留现有完整迁移链测试，防止中间注册缺失。
3. 对重命名、删除、默认值、回填、唯一约束等风险点增加专门数据断言。
4. 必要时验证真实 DAO 在迁移后可读写，而不只调用 schema 校验。

`MigrationTest` 是 instrumentation 测试，需要可用设备或模拟器及匹配的 runner。建议命令为：

```bash
./gradlew connectedAppMaxDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.MigrationTest
```

该 task 名只是当前候选。运行前先用当前 Gradle task、variant 和测试配置核验；若名称变化，选择实际等价 task 并在报告中记录。没有设备或任务未运行时，只能报告“未完成 instrumentation 迁移验证”，不能用编译结果替代。

## 交付证据

列出旧版和新版 version、所选迁移类型及理由、注册位置、schema 差异、测试数据断言、设备/模拟器环境和实际命令。若没有覆盖所有支持升级路径，明确剩余风险。
