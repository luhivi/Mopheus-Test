# Mopheus-Test · taskboard

一个可交互的全栈**任务看板**，用来验证 Mopheus 平台的完整代码交付链路：
**建工单 → 方案对抗审查 → 编码 → 代码挑刺审查 → PR 合入**。

前端零框架、零构建链，后端是标准的 Spring Boot 单体，静态资源由 Spring 直接托管。
一条 `java -jar` 就能起，打开浏览器即可用。

---

## 这个项目是干什么的

一个四列任务看板：新建任务、拖拽或按钮推进状态、按状态/优先级/逾期筛选、关键词搜索、
按创建时间/截止日期/优先级排序、编辑与删除，顶部实时显示各状态计数与逾期数。

它最初只是一个「新建 → 推进 → 删除」的最小样例，用来给 Mopheus 的编码流程提供一块
**可验收的试金石**：需求能从接口契约反向验证，代码能被真实跑起来，缺陷能被审查流程抓出来。
第一轮确实抓出了 8 个真实缺陷（见[对抗审查记录](#对抗审查记录)），于是第二轮把它做厚成
**能给客户直接演示的形态**，同时保留原来那套已被验证的契约。

---

## 技术栈

| 层 | 技术 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Java | 17 (LTS) | `pom.xml` 中 `java.version=17` |
| 框架 | Spring Boot | 3.5.16 | 3.x 最新补丁版 |
| Web | Spring MVC | 随 Boot | `@RestController` + `@RestControllerAdvice` |
| 校验 | Jakarta Bean Validation | 随 Boot | `@Valid` + `@NotBlank` / `@Size` / `@NotNull` |
| JSON | Jackson | 随 Boot | 开启严格反序列化，拒绝隐式强转 |
| 构建 | Maven | 3.9.9 | 由仓库自带 `mvnw` 下载，无需全局安装 |
| 持久化 | Spring Data JPA + Hibernate | 随 Boot | `spring-boot-starter-data-jpa` |
| 数据库 | H2（file 模式） | 随 Boot BOM | 演示库落在 `./data/taskboard.mv.db`，已 gitignore |
| 测试 | JUnit 5 + MockMvc | 随 Boot | 116 个用例 |
| 前端 | 原生 HTML5 / CSS3 / ES6+ | — | **不引入任何框架与构建链**，无 Node 依赖 |

**只需要 JDK 17。** Maven 由 wrapper 自举，npm / node 完全不需要。

存储是**嵌入式 H2 文件库**（`./data/taskboard.mv.db`），重启不丢、可离线演示。
仍然**只能单实例部署**：H2 文件库对同一文件加进程锁，第二个进程会启动失败。
要回到出厂空库，停掉进程后删除 `./data` 目录即可（见[重置数据](#重置数据)）。

---

## 快速开始

```bash
# 1. 跑测试
./mvnw test

# 2. 启动（默认 8080）
./mvnw spring-boot:run

# 3. 打开浏览器
open http://localhost:8080/
```

打包成可执行 jar：

```bash
./mvnw package -DskipTests
java -jar target/taskboard-0.0.1-SNAPSHOT.jar
```

换端口：

```bash
java -jar target/taskboard-0.0.1-SNAPSHOT.jar --server.port=9090
```

### 首次打开会自动准备示例数据

服务端为空时，前端会写入 8 条示例任务（覆盖四个状态、四种优先级、逾期/今日到期/无截止日期）。
**只有服务端一条任务都没有时才会写入**，不会覆盖已有数据。

示例数据和其他任务一样会**落盘保留**。想重新体验「首次打开自动播种」，先按下面清库。

### 重置数据

```bash
# 1. 先停掉进程（不要按端口杀，会误伤企业微信/微信）
kill "$(cat app.pid)"

# 2. 删掉数据目录，下次启动自动建空库并重新播种
rm -rf ./data
```

`./data/` 已在 `.gitignore` 里，不会被提交。数据库结构由 `ddl-auto=update` 自动维护，
升级版本后启动即可补齐新表新列；**它不做破坏性迁移**，真正的生产场景应换成 Flyway。

---

## 界面能做什么

| 能力 | 怎么用 |
| --- | --- |
| 快速新建 | 顶部输入框回车或点「添加」，只填标题 |
| 详细新建 / 编辑 | 「详细新建」或卡片上的「编辑」，弹窗里可设优先级、负责人、截止日期、标签（**没有状态字段**，新建一律进「待办」） |
| 改状态 | 拖拽卡片到目标列，或点卡片上的按钮：待办「推进」、进行中「推进」、阻塞「恢复进行中」 |
| 筛选 | 状态、优先级、只看逾期、标题/标签关键词搜索 |
| 排序 | 创建时间 / 截止日期 / 优先级，点右侧按钮切升序降序 |
| 统计 | 顶部总数、各状态计数、逾期数。**口径是当前筛选结果**（前端按返回列表自己算） |
| 状态可分享 | 筛选与排序条件写进 URL hash，复制链接即可还原同一视图 |

**拖拽的边界与服务端用同一张表判断**：`TODO` 拖到「已完成」、`BLOCKED` 拖到「已完成」都会被拒绝
并给出「不能从 X 直接移到 Y」的提示，已完成卡片本身也拖不动。前端查表提前拒绝，而不是发一个
注定 409 的请求让卡片弹回去。

**每一列都能用按钮离开**，包括阻塞 —— 移动端浏览器普遍不支持 HTML5 拖拽，拖拽不能是唯一入口。
这与「已完成」不同：`DONE` 是终态，卡片上没有任何按钮，这是刻意的。
拖拽之外，每张卡片都保留了「推进」按钮，键盘和触屏用户不用依赖拖拽。

---

## 接口

所有错误统一为 `{code, message, timestamp}`，`message` 是白名单文案，不含堆栈、类名或表名。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/tasks` | 列表，支持筛选与排序 |
| `GET` | `/api/tasks/{id}` | 单条详情 |
| `GET` | `/api/tasks/stats` | 全局统计（各状态/优先级计数、逾期数、今日到期数），**不随筛选变化** |
| `POST` | `/api/tasks` | 新建 |
| `PUT` | `/api/tasks/{id}` | 全量替换可编辑字段（**不改状态**） |
| `PATCH` | `/api/tasks/{id}/status` | 只改状态，走流转校验 |
| `DELETE` | `/api/tasks/{id}` | 删除 |
| `GET` | `/api/tasks/{id}/activities` | 活动流，`?limit=50`（1–200） |
| `POST` | `/api/tasks/batch-status` | 批量改状态，逐条独立 |
| `POST` | `/api/tasks/batch-delete` | 批量删除，逐条独立 |
| `GET` | `/api/tasks/export` | 导出全库 JSON（带 `Content-Disposition`） |
| `POST` | `/api/tasks/import` | 导入，`mode=merge\|replace` |

### 查询参数（`GET /api/tasks`）

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `status` | `TODO` `DOING` `BLOCKED` `DONE` | 可重复，多值为「或」 |
| `priority` | `LOW` `NORMAL` `HIGH` `URGENT` | 可重复，多值为「或」 |
| `q` | 任意文本 | 匹配**标题或标签**，忽略大小写（不搜索负责人，负责人请用 `assignee`） |
| `assignee` | 任意文本 | 匹配负责人，忽略大小写 |
| `overdue` | `true` | 只留逾期。`false` 与不传等价，都表示**不筛选** |
| `sort` | `createdAt` `dueDate` `priority` | 默认 `createdAt` |
| `order` | `asc` `desc` | 默认 `desc`；只有显式 `asc` 才升序 |

取值非法（例如 `?status=NOPE`）返回 **400 `MALFORMED_QUERY`**，不会掉成 500。

### 任务对象

```json
{
  "id": 7,
  "title": "确认演示环境网络可用",
  "status": "DOING",
  "priority": "HIGH",
  "assignee": "运维",
  "dueDate": "2026-11-23",
  "tags": ["环境"],
  "createdAt": "2026-11-23T07:22:05.506069Z",
  "updatedAt": "2026-11-23T07:22:09.100087Z",
  "overdue": false,
  "daysOverdue": 0
}
```

`overdue` / `daysOverdue` 是服务端算出来的，前端不自己判断日期 —— 只有一处真相。
业务日期按**服务器本地时区**计算（`Clock.systemDefaultZone()`）；用 UTC 会让东八区在每天
0 点到 8 点之间把「明天到期」算成「今天到期」。

### `PUT` 是严格全量替换

请求体里**缺失的字段会被清空**，而不是保留旧值。`priority` 缺失按 `NORMAL` 处理，
`assignee` / `dueDate` 缺失按 `null`，`tags` 缺失按空数组。

这意味着「只想改标题」也必须把其余字段一起传回来，否则会被清空。这不是偷懒，
而是刻意选择了一种语义并贯彻到底：早期版本是「`priority` 保留、其余清空」的混合行为，
客户演示时「改个标题，负责人和截止日期全没了」是最容易当场翻车的操作。
现在行为一致且可预测，前端编辑弹窗本来就会回填完整对象，所以正常使用不会触发。

### 未知字段会被拒绝

`spring.jackson.deserialization.fail-on-unknown-properties=true`。拼错字段名（`priorty`）
返回 400 `MALFORMED_BODY`，而不是静默用默认值 —— 这一点在 `PUT` 上尤其重要，因为它做全量替换，
拼错 `priority` 会把紧急任务悄悄降级成普通。

例外是 `GET` 返回的那些字段：`POST` / `PUT` 会忽略 `id`、`status`、`createdAt`、`updatedAt`、
`overdue`、`daysOverdue`；`PATCH .../status` 会忽略除 `status` 外的全部字段。
它们被忽略而不是报错，这样「读一条、改一个字段、写回去」这种常见写法不会失败。
两条写路径规则一致，不存在「PUT 宽容、PATCH 严格」这种不对称。

### 字段约束

| 字段 | 约束 |
| --- | --- |
| `title` | 必填，去首尾空格后 1–80 字符 |
| `priority` | `LOW` / `NORMAL` / `HIGH` / `URGENT`，默认 `NORMAL` |
| `assignee` | 可空，去空格后最长 40 字符；空串按 `null` 存 |
| `dueDate` | `yyyy-MM-dd`，可空。**允许过去日期**，见[已知限制](#已知限制) |
| `tags` | 最多 5 个，每个 1–20 字符，去重后保持首次出现顺序 |
| `status` | **不属于新建/编辑入参**。新建一律从 `TODO` 开始，之后只能走 `PATCH .../status` |

### 状态流转

| 当前 | 允许去向 |
| --- | --- |
| `TODO` | `DOING`、`BLOCKED` |
| `DOING` | `TODO`、`BLOCKED`、`DONE` |
| `BLOCKED` | `TODO`、`DOING` |
| `DONE` | 无（终态） |

这张表是**白名单**，不是「除了 DONE 随便转」。两者差别很具体：

- `TODO → DONE` 被拒绝。没开始过的任务不可能完成，跳步在这里是最常见的操作失误。
- `BLOCKED → DONE` 被拒绝，必须先回到 `DOING`。阻塞中的任务直接结项，等于没人处理它。
- `DONE` 不可退回任何状态 —— 退回等于悄悄撤销别人已经当作完成的工作。

重复提交当前状态是幂等的空操作，不算错误 —— 拖拽、重试、另一个标签页都会产生这种请求。

**新建任务永远从 `TODO` 开始**，`POST` 请求体里的 `status` 会被忽略。
这一条不是洁癖：如果允许新建时指定状态，任何客户端都能一步绕过整张表。

前端 `app.js` 里的 `ALLOWED_TRANSITIONS` 是这张表的副本，用于拖拽时提前拒绝，
避免发出注定 409 的请求。两份表的对齐由 `TaskApiTest` 与前端行为测试共同锁住。

违反规则返回 **409 `INVALID_TRANSITION`**（不是 400：请求本身合法，是资源当前状态不允许）。

### 活动流

```
GET /api/tasks/{id}/activities?limit=50
```

`limit` 缺省 50，取值 1–200；非数字、`0`、负数、`>200` 一律 **400 `MALFORMED_QUERY`**（同样不回显取值）。

排序固定 `createdAt DESC, id DESC`。事件类型与抑制规则：

| 类型 | `detail` 示例 | 何时写入 |
| --- | --- | --- |
| `CREATED` | `创建任务` | 新建 |
| `STATUS_CHANGED` | `TODO → DOING` | 状态**真的**变化时 |
| `UPDATED` | `标题、优先级` | PUT 归一化后**确有**差异时 |
| `DELETED` | `删除任务` | 删除瞬间 |

两条抑制规则是刻意的，避免写出自相矛盾的历史：

- **同状态 PATCH 不落 `STATUS_CHANGED`**，也不刷新 `updatedAt`（幂等空操作）。
- **PUT 归一化后零差异不落 `UPDATED`**（trim/去重/空串转 `null` 之后没有实质变更）。
  `UPDATED` 的 `detail` 只列**实际变化**的字段，标签固定为
  `标题` / `优先级` / `负责人` / `截止日期` / `标签`，按此顺序用 `、` 连接。

删除任务时**不级联删活动**：任务行删掉、活动行保留，最后一条是 `DELETED`。
这就是删除后的取证依据 —— 删了任务也能查到它存在过、谁在什么时候改过。

因此 `GET /api/tasks/{id}/activities` 有三种结果：任务在 → 200 + 活动列表（可能为空，
例如导入进来的任务没有 `CREATED` 行）；任务已删但有历史 → **200** + 历史；
任务不存在且无任何历史 → **404 `TASK_NOT_FOUND`**。

### 批量操作

```
POST /api/tasks/batch-status   { "ids":[1,2,3], "status":"DOING" }
POST /api/tasks/batch-delete   { "ids":[1,2] }
```

两者都返回 **200**，逐条结果在 `results` 里：

```json
{"results":[
  {"id":3,"ok":true,"task":{ ...TaskResponse 全字段... }},
  {"id":1,"ok":false,"code":"INVALID_TRANSITION","message":"task cannot move from DONE to DOING"}
]}
```

- `results` 顺序 = **请求 ids 去重后的首现顺序**（`[3,1,2,1]` → `3,1,2`）。
- 成功条目：`{id, ok:true, task}`，`task` 与 `GET /api/tasks/{id}` 完全同形状。
  `batch-delete` 的成功条目省略 `task`，即 `{id, ok:true}`。
- 失败条目：`{id, ok:false, code, message}`，**没有 `task` 键**。`code` ∈
  `TASK_NOT_FOUND` / `INVALID_TRANSITION` / `CONCURRENT_MODIFICATION`。
- 逐条独立执行、独立提交：**一条失败不回滚其他条**，完全没有包大事务。
- HTTP 恒为 200，哪怕全部失败 —— 所以**前端只能逐条解析 `results` 来算成功/失败数**，
  看 HTTP 状态码没有意义。

请求级校验失败才返回 **400 `VALIDATION_FAILED`**，且整个请求不执行：
`ids` 缺失 / 不是数组 / 空 / 超过 200 条 / 含非整数，或 `status` 缺失 / 非法。
注意这里用 `VALIDATION_FAILED` 而不是 `MALFORMED_BODY`：批量接口对客户端只有一个 400 分支。

### 导入导出

```
GET  /api/tasks/export
POST /api/tasks/import
```

导出返回 `application/json`，并且带一个前端会解析的文件名：

```
Content-Disposition: attachment; filename="taskboard-export-20261126-104500.json"
```

`<yyyyMMdd-HHmmss>` 按**服务器本地时区**生成。响应体：

```json
{"exportedAt":"...", "count":2, "tasks":[ ...TaskResponse 全字段... ]}
```

`tasks` 与 `GET /api/tasks` 默认顺序一致（`createdAt DESC`），包含 `overdue`/`daysOverdue`。

导入请求：

```json
{"mode":"merge","tasks":[{"title":"...","status":"DOING","priority":"HIGH","tags":["a"]}]}
```

- `mode` 只能是 `merge` 或 `replace`。`replace` **必须**同时带 `"confirm":"replace"`，
  否则 **400 `REPLACE_NOT_CONFIRMED`**。
- 返回 `{mode, imported, skipped, total}`；`total` 是**导入后库内任务总数**。
- **逐条校验，坏的整行计 `skipped`，好的照常导入**。判定规则：

| 字段 | 判定 |
| --- | --- |
| `title` | 去空格后 1–80 字符 |
| `status` / `priority` | 必须是合法枚举名（大小写不敏感）；缺省为 `TODO` / `NORMAL` |
| `dueDate` | `yyyy-MM-dd`，缺省为空 |
| `tags` | **先归一化**（去空格/去重/去空）**再**校验 ≤5 个、每个 1–20 字符 |
| `assignee` | 去空格后空串按 `null`；最长 40 字符 |

- `id` / `createdAt` / `updatedAt` 一律**忽略并重新生成**，所以导出的文件可以原样再导入。
- 导入**允许直接落到任意合法状态**（例如直接 `DONE`）。这是恢复路径，不走交互状态机 ——
  否则导出再导入会把所有已完成任务打回待办。
- `merge`：全部追加新 id，不覆盖已有任务。
- `replace`：**单事务原子完成**（清任务 + 清活动 + 导入），中途失败整体回滚。
- `tasks` 缺失 / 非数组 → 400 `VALIDATION_FAILED`；条数 >1000 → 400。

### 错误码

| HTTP | `code` | 触发场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段校验失败（标题空、标签过多等）；批量/导入的请求级校验也用它 |
| 400 | `REPLACE_NOT_CONFIRMED` | `mode:"replace"` 但缺少 `confirm:"replace"` |
| 400 | `MALFORMED_BODY` | JSON 语法错误、枚举值不存在，或出现未知字段 |
| 400 | `MALFORMED_QUERY` | 查询参数取值非法（只回显参数名，**不回显取值**） |
| 400 | `MALFORMED_PATH` | 路径段无法转换（例如 `/api/tasks/abc`） |
| 404 | `TASK_NOT_FOUND` | 任务不存在 |
| 404 | `NOT_FOUND` | 路由不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持（带 `Allow` 头） |
| 406 | `NOT_ACCEPTABLE` | 客户端要求非 JSON 表示 |
| 409 | `INVALID_TRANSITION` | 状态流转非法 |
| 409 | `CONCURRENT_MODIFICATION` | 乐观锁冲突，另一个请求先写成功；刷新后重试即可 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 不是 `application/json` |
| 500 | `INTERNAL_ERROR` | 未预期异常（不泄露堆栈） |

---

## 项目结构

```
pom.xml
mvnw / mvnw.cmd / .mvn/            Maven wrapper，钉死 3.9.9
src/main/java/com/example/taskboard/
  TaskboardApplication.java        入口 + Clock bean（时间可注入，便于测试）
  model/
    Task.java                      领域对象 + isOverdue / isDueToday
    TaskStatus.java                四态枚举 + canMoveTo 流转规则
    TaskPriority.java              四档优先级
    TaskActivity.java              活动行领域对象（taskId 非外键，删除后仍可查）
    TaskActivityType.java          CREATED / UPDATED / STATUS_CHANGED / DELETED
  persistence/
    TaskEntity.java                存储态；枚举按名存、tags EAGER + @OrderColumn、@Version
    TaskActivityEntity.java        活动行实体（无外键，保住删除后的历史）
    TaskMapper.java                record ↔ entity 映射，领域对象不带注解
    TaskRepository.java            JPA 仓库 + 清库语句
    TaskActivityRepository.java    活动查询（createdAt DESC, id DESC）
    JpaTaskStore.java              实现 TaskStore，把乐观锁异常翻译成领域异常
  service/
    TaskService.java               全部业务规则：归一化、状态机、严格 PUT、活动抑制
    TaskStore.java                 存储端口 + Planner/Plan（"零差异=不写"）
    ImportExportService.java       导入校验 / 导出快照
    TaskQuery.java                 筛选 + 排序（含 null 截止日期处理）
    TaskStats.java                 统计聚合结果
    BatchOutcome.java              批量单条结果
    ImportOutcome.java             导入结果
    TaskNotFoundException.java     404
    InvalidTaskTransitionException.java  409 INVALID_TRANSITION
    TaskConflictException.java     409 CONCURRENT_MODIFICATION
  web/
    TaskController.java            原有 7 个端点；/stats 声明在 /{id} 之前
    TaskTransferController.java    活动流 / 批量 / 导入导出 6 个新端点
    TaskPayload.java               新建/编辑入参 + 校验注解
    ImportTaskPayload.java         导入行（不复用 TaskPayload，否则 status 会丢）
    TaskResponse.java              出参；overdue 在此计算
    TaskStatsResponse.java         统计出参
    UpdateStatusRequest.java       改状态入参
    BatchResultResponse.java       批量单条出参（成功/失败字段互斥）
    ApiExceptionHandler.java       统一错误体，保留框架状态码
    JacksonStrictConfig.java       拒绝数字/布尔强转成字符串与枚举
    MalformedQueryException.java   查询参数非法 → 400
    ValidationFailedException.java 请求级校验失败 → 400 VALIDATION_FAILED
    ReplaceNotConfirmedException.java   400 REPLACE_NOT_CONFIRMED
src/main/resources/
  application.properties          端口 + 枚举严格模式 + H2/JPA 配置
  static/index.html               看板、筛选栏、统计条、编辑弹窗
  static/app.js                   请求封装、渲染、拖拽、筛选、弹窗
  static/style.css                四列看板与卡片样式
src/test/
  resources/application.properties  测试库：jdbc:h2:mem + create-drop，永不碰 ./data
  java/com/example/taskboard/
    TaskApiTest.java              35 个 MockMvc 用例（原契约准绳，零改动）
    TaskPersistenceTest.java       6 个 JPA 往返用例（枚举按名、tags 顺序、Instant）
    TaskActivityBatchApiTest.java 21 个活动流/批量/导出接口用例
    TaskImportExportApiTest.java  12 个导入导出用例（含导出文件原样回灌）
    TaskOptimisticLockTest.java    4 个乐观锁用例（老版本写入必须被拒）
    ImportAtomicityTest.java       2 个事务用例（replace 失败必须回滚）
    service/TaskServiceTest.java  24 个服务层用例（含 4x4 流转矩阵）
    service/TaskActivityServiceTest.java  12 个活动抑制规则用例
    support/MutableClock.java     可推进的测试时钟
    support/InMemoryTaskStore.java  服务层测试用的内存 TaskStore
```

四条设计要点：

1. **领域对象与服务规则不碰数据库**。`Task` 仍是 record，`TaskService` 只管归一化/状态机/
   严格 PUT/活动抑制；存取的差异收在 `TaskStore` 端口后面。所以 24 个服务层用例用内存实现
   跑得飞快，JPA 的正确性由 `TaskPersistenceTest` 单独钉住 —— 两边不会靠假设对齐。
2. **状态机只有一个实现**，在 `TaskStatus.canMoveTo`。服务端与前端各自判断，但规则来源唯一。
3. **错误处理只有一条出口**，`ApiExceptionHandler` 把所有异常收成药丸状 JSON，并保留 Spring
   自己算出来的状态码与响应头（405 的 `Allow`、406 的 JSON 兜底都在这里）。
4. **前端三态 + 请求编号**：加载中/空/失败互斥渲染；每次列表请求带递增编号，旧响应回来
   直接丢弃，避免快速筛选时后到的旧数据覆盖新数据。

---

## 测试

```
$ ./mvnw -B test
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- TaskImportExportApiTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- TaskPersistenceTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0 -- TaskActivityBatchApiTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- TaskActivityServiceTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- TaskServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- TaskOptimisticLockTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0 -- TaskApiTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- ImportAtomicityTest
[INFO] Tests run: 116, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

测试库与演示库严格隔离：`src/test/resources/application.properties` 用
`jdbc:h2:mem:taskboard-test` + `create-drop`。测试**永不触碰 `./data`**，也不会与正在演示的
进程抢 H2 文件锁 —— 这一点比测试数量更重要，抢锁失败会让整轮验证全是假红。

服务层用一张 4×4 矩阵逐格断言流转白名单（`transitionTableMatchesTheDocumentedMatrix`），
另有 `TODO→DONE`、`BLOCKED→DONE` 跳步、`DONE` 终态、幂等空操作、失败不改状态等独立用例；
以及排序（优先级序、截止日期 null 排最后）、筛选组合、逾期与今日到期边界、标签去重、统计聚合。

`TaskApiTest` 用 `@TestMethodOrder(Random)` 跑，并在 `@BeforeEach` 清空存储 —— 只有在这种
随机顺序下仍然全绿，才说明用例之间真的隔离。

接口层除正常路径外，专门钉住了几个容易回归的地方：
`Accept: text/html` 不能退化成 Whitelabel 页面、405 必须带 `Allow`、415 不能被压成 400、
数字形式的枚举 ordinal 必须被拒、非法查询参数不能变成 500。

本轮新增的四组用例各自守住一类失真：

- `TaskPersistenceTest`：直接查库，钉住枚举存的是**名字**不是 ordinal、`tags` 有 `position`
  列且顺序保留、`Instant` 往返一致、`open-in-view=false` 下读取不炸。
- `TaskActivityServiceTest`：两条抑制规则（同状态 PATCH、PUT 零差异）、`DELETED` 保留、
  排序与 id tie-break、`limit` 边界，全部用 `MutableClock` 推时间，不依赖真实时钟。
- `TaskActivityBatchApiTest`：去重后**顺序**、混合成功/失败**不回滚**、请求级 400 统一
  `VALIDATION_FAILED`、`Content-Disposition` 文件名形状。
- `TaskImportExportApiTest`：导出文件**原样回灌**、`replace` 原子、`confirm` 缺失、
  导入尊重 `status`、`tags` 归一化后再判 ≤5、坏行逐条 `skipped`。
- `ImportAtomicityTest`：在清库之后**人为抛异常**，断言旧任务仍在 —— 注解写了不等于回滚，
  这条用例是「replace 原子」这句话的唯一证据。
- `TaskOptimisticLockTest`：制造一个过期版本的写请求，断言它拿到
  `OptimisticLockingFailureException` 而不是覆盖别人刚提交的数据。

两个**防失真断言**单独拎出来：`sameStatusPatchLogsNothingAndKeepsUpdatedAt` 直接比较两次
GET 的 `updatedAt`；`createWritesOneCreatedActivity` 用正则断言 `createdAt` 是 ISO-8601 形状
（`\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z`）。

---

## 对抗审查记录

这个样例最有价值的部分不是代码，而是**审查流程抓出来的缺陷**。第一轮的 8 个全部已修复：

| 级别 | 位置 | 问题 | 后果 |
| --- | --- | --- | --- |
| P0 | `style.css` | `.task-list { display: flex }` 压过浏览器默认的 `[hidden] { display: none }` | 空态/错误态叠在空列表上，三态界面全错 |
| P1 | `ApiExceptionHandler` | 406 走 Whitelabel HTML | 客户端拿到 HTML 而非统一 JSON |
| P1 | `ApiExceptionHandler` | 405 / 415 被压成 `BAD_REQUEST` | 状态码失真 |
| P1 | `app.js` `loadTasks` | 无请求编号，并发时旧响应覆盖新列表 | 列表显示过期数据 |
| P1 | `app.js` `createTask` | 先设成功提示再 `await loadTasks()`，而后者开头清空提示 | 成功提示永不显示 |
| P1 | `app.js` 行内按钮 | 连点在首个 `await` 前可发出两次请求 | 重复 PATCH / 重复删除 |
| P1 | 405 响应 | 缺 `Allow` 头（RFC 9110 §15.5.6 要求） | 响应不合规 |
| P2 | 构建 | 仓库无 `mvnw`，无法在无全局 Maven 的机器上验证 | 审查流程卡死 |

第二轮（本次功能增强）继续沿用同样的纪律，额外钉住了：

| 问题 | 处理 |
| --- | --- |
| `/api/tasks/stats` 会被 `/{id}` 抢先匹配，`stats` 无法转成 `long` | `/stats` 声明在前，并加用例锁死 |
| 查询参数枚举解析失败会掉进 catch-all 变成 500 | 改为 400 `MALFORMED_QUERY`，参数名回显、值不回显 |
| `PUT` 可能绕过状态机偷改状态 | `PUT` 忽略 `status` 字段，状态只能走 `PATCH` |
| 拖到 `DONE` 列会先视觉接受再被 409 打回 | `dragover` 阶段就拒绝，不给错误暗示 |
| 重复提交当前状态被当成错误 | 定义为幂等空操作，不刷新 `updatedAt` |
| PUT 语义一半一半（`priority` 保留、其余清空） | 统一为严格全量替换 |
| 拼错字段名被静默吞掉 | 开 `fail-on-unknown-properties`，返回 400 |
| 错误体回显了查询参数的值 | 只回显参数名，值不回显 |
| 新建弹窗有「初始状态」下拉但服务端丢弃 | 删除该字段，新建一律 `TODO` |
| 种子守卫用筛选后的列表判断，可能污染真实数据 | 改为向服务端查无筛选总数 |
| 阻塞卡片只能靠拖拽移出 | 增加「恢复进行中」按钮 |
| 测试共享内存状态，只在默认顺序下全绿 | `@BeforeEach` 清空 + 随机顺序执行 |

---

## 已知限制

这是**样例**，不是生产组件。以下是刻意的取舍，不是遗漏：

- **单实例**：H2 文件库对同一文件加进程锁，第二个进程起不来。要多实例得换真正的数据库
  服务端（PostgreSQL / MySQL），本样例不做。
- **迁移靠 `ddl-auto=update`**：它只加表加列，不处理改列型、改约束、删列。生产项目应换成
  Flyway，并用版本化的迁移脚本替代它。
- **并发控制只有乐观锁，没有 `ETag`**：`@Version` 让第二个写请求拿到 **409
  `CONCURRENT_MODIFICATION`** 而不是覆盖，这是真进步；但接口没有暴露版本号，
  客户端没法发送 `If-Match` 做条件写，只能「冲突 → 重新拉取 → 再提交」。
- **允许创建已过期的截止日期**：新建时也不拦。因为编辑一条本来就已经逾期的任务时，
  「逾期」是一个**状态**而不是**非法输入**；拦了既会把正常编辑变成无法保存，也和「逾期数」统计
  自相矛盾。逾期改为在界面上标红提示。
- **导入没有干跑（dry-run）**：只有 `merge` / `replace` 两种模式，没有「先校验、看报告、
  再确认」。`replace` 用 `confirm:"replace"` 挡住误操作，但看不到「到底会跳过哪几行」。
- **活动流不参与鉴权**：任务删掉后活动行永久保留，包括 `detail` 里的字段标签。
  演示够用；真实场景要按删除做保留期策略，否则会无限增长。
- **两个统计口径不同**：`GET /api/tasks/stats` 是全局（不受筛选影响），顶部统计条是按筛选结果
  在前端算的。这是刻意的 —— 演示时需要「筛出来多少」和「总共有多少」同时可见。
- **`overdue` 只做正向筛选**：`?overdue=true` 才筛；`false` 与不传等价。因为逾期只对
  未完成任务有意义，`DONE` 任务即便截止日期已过也不算逾期。
- **没有登录与权限**：任何能访问端口的人都能改所有任务。
- **拖拽依赖 HTML5 原生 API**：桌面浏览器体验完整；移动端浏览器普遍不支持 HTML5 拖拽，
  所以每张卡片都保留了按钮操作作为等价入口。

---

## 开发

```bash
./mvnw -B test                              # 全量测试
./mvnw -B package -DskipTests               # 打包
java -jar target/taskboard-0.0.1-SNAPSHOT.jar --server.port=9090
```

前端是裸 JS，没有构建步骤，改完 `src/main/resources/static/` 下的文件重启即可生效
（或者用 IDE 的静态资源热替换）。语法自检：

```bash
node --check src/main/resources/static/app.js    # 推荐
```

没有 Node 的机器上可以退回到 JavaScriptCore：

```bash
/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
  -e "new Function(readFile('src/main/resources/static/app.js')); print('SYNTAX_OK')"
```
