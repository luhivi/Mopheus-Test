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
**能给客户直接演示的形态**，同时保留原来那套已被验证的契约。第三轮把它从「演示版」推到
「可日常使用」：数据落库重启不丢、每条任务带操作历史、支持批量推进/删除、支持 JSON 导入导出。

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
| 存储 | Spring Data JPA + H2 | 随 Boot（Hibernate 6.6 / H2 2.3） | H2 文件库 `./data/taskboard`，重启不丢 |
| 测试 | JUnit 5 + MockMvc | 随 Boot | 85 个用例（服务层 24 + 既有接口 35 + 新能力 24 + 并发 2） |
| 前端 | 原生 HTML5 / CSS3 / ES6+ | — | **不引入任何框架与构建链**，无 Node 依赖 |

**只需要 JDK 17。** Maven 由 wrapper 自举，npm / node 完全不需要。

任务存在 H2 文件数据库里（`./data/taskboard.mv.db`），进程重启数据仍在。删除 `data/` 目录即可
完全重置（该目录已进 `.gitignore`）。仍然是单实例本地部署，不做集群。

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

### 重置数据

```bash
# 必须先停进程：H2 文件库同一时刻只允许一个进程持有
rm -rf data/
```

`data/` 是纯本地状态，删掉等于回到全新看板。它已进 `.gitignore`，不会被提交。

### 首次打开会自动准备示例数据

服务端为空时，前端会写入 8 条示例任务（覆盖四个状态、四种优先级、逾期/今日到期/无截止日期）。
守卫用的是**服务端无筛选总数**，所以筛选后看起来是空页面也不会误播种。示例数据同样落库，
重启后仍在。删掉其中几条不会触发重播种；**把任务清到一条不剩再刷新页面**，守卫判定为空，
会重新播一遍 —— 想彻底清空并保持为空，请用上面的「重置数据」并留意这一点。

---

## 界面能做什么

| 能力 | 怎么用 |
| --- | --- |
| 快速新建 | 顶部输入框回车或点「添加」，只填标题 |
| 详细新建 / 编辑 | 「详细新建」或卡片上的「编辑」，弹窗里可设优先级、负责人、截止日期、标签（**没有状态字段**，新建一律进「待办」） |
| 改状态 | 拖拽卡片到目标列，或点卡片上的按钮：待办「推进」、进行中「推进」、阻塞「恢复进行中」 |
| 操作历史 | 点开卡片详情，抽屉里有该任务的倒序时间线（创建/编辑/改状态/删除），时间显示为相对时间 |
| 批量操作 | 卡片左上角勾选，顶部出现批量栏：已选计数、全选/清空、批量推进到某状态、批量删除（带确认） |
| 导入导出 | 工具栏「导出 JSON」直接下载；「导入 JSON」选文件后可 merge 追加或 replace 替换（替换有红字警示 + 二次确认） |
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
| `GET` | `/api/tasks/{id}/activities` | 该任务的操作历史，倒序 |
| `GET` | `/api/tasks/export` | 导出全部任务为 JSON 附件 |
| `POST` | `/api/tasks` | 新建 |
| `PUT` | `/api/tasks/{id}` | 全量替换可编辑字段（**不改状态**） |
| `PATCH` | `/api/tasks/{id}/status` | 只改状态，走流转校验 |
| `DELETE` | `/api/tasks/{id}` | 删除（历史保留） |
| `POST` | `/api/tasks/batch-status` | 批量改状态，逐条独立 |
| `POST` | `/api/tasks/batch-delete` | 批量删除，逐条独立 |
| `POST` | `/api/tasks/import` | 从 JSON 导入，merge / replace |

### 操作历史（`GET /api/tasks/{id}/activities?limit=50`）

倒序返回该任务的历史行，`limit` 缺省 50、取值 1–200，越界或非数字返回 400 `MALFORMED_QUERY`。

```json
[
  {"id":2,"taskId":1,"type":"STATUS_CHANGED","detail":"TODO → DOING","createdAt":"2026-11-26T04:02:25.339705Z"},
  {"id":1,"taskId":1,"type":"CREATED","detail":"创建任务","createdAt":"2026-11-26T04:02:25.246048Z"}
]
```

`type` 四值：`CREATED` / `UPDATED` / `STATUS_CHANGED` / `DELETED`。

**两条抑制规则**，避免历史里出现自相矛盾的记录：

- 同一状态的 `PATCH`（幂等空操作）**不写历史**；
- `PUT` 在归一化（trim / 去重 / 空→null）之后零差异，**不写历史、也不动 `updatedAt`**。

**删除任务保留历史**：删除行任务本身，另追加一条 `DELETED` 活动行。任务不存在但留有历史时，
该接口仍返回 200（审计可查）；任务从未存在、也没有任何历史才返回 404 `TASK_NOT_FOUND`。
活动行与任务行之间**没有外键**，正是为了让删除证据留下来。

### 批量操作

```json
POST /api/tasks/batch-status  {"ids":[1,2,3],"status":"DOING"}
POST /api/tasks/batch-delete  {"ids":[1,2]}
→ 200 {"results":[
    {"id":1,"ok":true,"task":{ ...TaskResponse 全字段... }},
    {"id":2,"ok":false,"code":"INVALID_TRANSITION","message":"..."}]}
```

- **逐条独立执行、独立提交**：单条失败不影响其他条，整批仍返回 200。
- `results` 顺序 = 请求 ids **去重后**顺序（重复 id 落到首次出现的位置）。
- 成功条目 `task` 是完整 `TaskResponse`；失败条目只有 `{id, ok:false, code, message}`，没有 `task`。
- batch-delete 的成功条目**不含** `task`（任务已删，没有资源可返回）。
- 请求级问题（`ids` 缺失 / 非数组 / 空 / 含非整数 / 去重后 >200；`status` 非法）→ 400 `VALIDATION_FAILED`。

> 单条失败在 `code` 里体现，**不能以 HTTP 200 判定全部成功**。前端必须逐条读 `results`。

### 导入 / 导出

```json
GET /api/tasks/export
→ 200 application/json
  Content-Disposition: attachment; filename="taskboard-export-20261126-120301.json"
  {"exportedAt":"...","count":2,"tasks":[ ...全字段... ]}

POST /api/tasks/import
{"mode":"merge","tasks":[{"title":"...","status":"DOING","priority":"HIGH",...}]}
{"mode":"replace","confirm":"replace","tasks":[ ... ]}
→ 200 {"mode":"merge","imported":2,"skipped":1,"total":5}
```

- `merge` 全部追加、分配**新 id**；`replace` 在**单个事务**里清空任务与历史后导入，中途失败整体回滚。
- `total` 是**导入完成后库内总数**。
- 行内 `id` / `createdAt` / `updatedAt` / `overdue` / `daysOverdue` 一律忽略，重新生成 —— 导出文件可原样回灌。
- **导入是恢复路径**，允许直接落到任意合法状态（含 `DONE`），不受交互状态机约束。
- 逐条校验，**整行跳过**而不报 400：`title` 空白或超 80；`status` / `priority` 非法枚举；
  `dueDate` 非法日期；`tags` 先归一化（trim/去重/去空）后超过 5 个或存在超过 20 字符的项；
  `assignee` 超过 40 字符。
- 请求级：`mode` 非法 → 400 `VALIDATION_FAILED`；`tasks` 缺失/非数组 → 400 `VALIDATION_FAILED`；
  条数 >1000 → 400 `VALIDATION_FAILED`；`replace` 缺 `confirm:"replace"` → 400 `REPLACE_NOT_CONFIRMED`。

**`replace` 为什么要 `confirm`**：清库是不可逆操作。前端的二次确认只活在前端，任何脚本、
`curl` 调用或重放请求都能绕过它。要求 body 里显式带 `confirm` 是让「我要删光」成为调用方的
声明，而不是服务端根据 `mode` 替它推断。

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

### 错误码

| HTTP | `code` | 触发场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段校验失败（标题空、标签过多等） |
| 400 | `MALFORMED_BODY` | JSON 语法错误、枚举值不存在，或出现未知字段 |
| 400 | `MALFORMED_QUERY` | 查询参数取值非法（只回显参数名，**不回显取值**） |
| 400 | `MALFORMED_PATH` | 路径段无法转换（例如 `/api/tasks/abc`） |
| 404 | `TASK_NOT_FOUND` | 任务不存在 |
| 404 | `NOT_FOUND` | 路由不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持（带 `Allow` 头） |
| 406 | `NOT_ACCEPTABLE` | 客户端要求非 JSON 表示 |
| 409 | `INVALID_TRANSITION` | 状态流转非法 |
| 409 | `CONCURRENT_MODIFICATION` | 乐观锁冲突（并发改同一条任务，本次写入未生效） |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 不是 `application/json` |
| 400 | `REPLACE_NOT_CONFIRMED` | `mode=replace` 但 body 缺 `confirm:"replace"` |
| 500 | `INTERNAL_ERROR` | 未预期异常（不泄露堆栈） |

---

## 项目结构

```
pom.xml
mvnw / mvnw.cmd / .mvn/            Maven wrapper，钉死 3.9.9
src/main/java/com/example/taskboard/
  TaskboardApplication.java        入口 + Clock bean（时间可注入，便于测试）
  model/
    Task.java                      领域 record + isOverdue / isDueToday（不随存储变更）
    TaskStatus.java                四态枚举 + canMoveTo 流转规则
    TaskPriority.java              四档优先级
    TaskActivity.java              历史行的领域形态
    TaskActivityType.java          CREATED / UPDATED / STATUS_CHANGED / DELETED
  persistence/
    TaskEntity.java                JPA 实体 + toDomain 映射；枚举按名存、tags EAGER+@OrderColumn、@Version
    TaskActivityEntity.java        历史表；taskId 是普通列（无外键），删除后仍留存
    TaskRepository.java            仅 CRUD + replace 用的清理查询
    TaskActivityRepository.java    按 taskId 倒序取历史
  service/
    TaskService.java               用例：增删改查、统计、历史、导入导出；归一化与状态机
    TaskBatchService.java          批量逐条独立提交（每条独立事务）
    TaskQuery.java                 筛选 + 排序（含 null 截止日期处理）
    TaskStats.java                 统计聚合结果
    TaskNotFoundException.java     404
    InvalidTaskTransitionException.java  409
  web/
    TaskController.java            13 个端点；/stats、/export 声明在 /{id} 之前
    TaskPayload.java               新建/编辑入参 + 校验注解
    TaskResponse.java              出参；overdue 在此计算
    TaskStatsResponse.java         统计出参
    UpdateStatusRequest.java       改状态入参
    TaskActivityResponse.java      历史出参
    BatchRequest / BatchResponse / BatchResultItem   批量入参 / 出参 / 逐条结果
    ImportRequest / ImportResponse / ExportResponse  导入导出
    ApiExceptionHandler.java       统一错误体，保留框架状态码
    JacksonStrictConfig.java       拒绝数字/布尔强转成字符串与枚举
    MalformedQueryException.java   查询参数非法 → 400
    ValidationFailedException.java 请求级结构非法 → 400 VALIDATION_FAILED
    ReplaceNotConfirmedException.java  replace 缺确认 → 400 REPLACE_NOT_CONFIRMED
src/main/resources/
  application.properties          端口 + 枚举严格模式 + H2 文件库 + open-in-view=false
  static/index.html               看板、筛选栏、统计条、编辑弹窗、批量栏、导入弹窗
  static/app.js                   请求封装、渲染、拖拽、筛选、弹窗、活动流、批量、导入导出
  static/style.css                四列看板与卡片样式
src/test/
  resources/application.properties  测试用 H2 内存库（create-drop），永不碰 ./data
  java/com/example/taskboard/
    TaskApiTest.java                    35 个既有契约用例（随机顺序跑）
    TaskFeatureApiTest.java             24 个新能力用例
    ConcurrentModificationTest.java     2 个并发冲突用例
    service/TaskServiceTest.java        24 个服务层用例（含 4x4 流转矩阵）
    support/MutableClock.java           可推进/可重置的测试时钟
    support/StoreReset.java             清库 + 重置自增（H2 自增非事务性）
```

四条设计要点：

1. **状态机只有一个实现**，在 `TaskStatus.canMoveTo`。服务端与前端各自判断，但规则来源唯一。
2. **错误处理只有一条出口**，`ApiExceptionHandler` 把所有异常收成药丸状 JSON，并保留 Spring
   自己算出来的状态码与响应头（405 的 `Allow`、406 的 JSON 兜底都在这里）。
3. **前端三态 + 请求编号**：加载中/空/失败互斥渲染；每次列表请求带递增编号，旧响应回来
   直接丢弃，避免快速筛选时后到的旧数据覆盖新数据。
4. **领域对象与存储分离**：`Task` record 不认识 JPA，`TaskEntity` 不认识业务规则。过滤、排序、
   统计仍在内存里对领域对象做（`TaskQuery`），没有把 id tie-break 和 nullsLast 移植成第二套 JPQL。

---

## 测试

```
$ ./mvnw -B test
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- TaskFeatureApiTest
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- ConcurrentModificationTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- TaskServiceTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0 -- TaskApiTest
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

服务层用一张 4×4 矩阵逐格断言流转白名单（`transitionTableMatchesTheDocumentedMatrix`），
另有 `TODO→DONE`、`BLOCKED→DONE` 跳步、`DONE` 终态、幂等空操作、失败不改状态等独立用例；
以及排序（优先级序、截止日期 null 排最后）、筛选组合、逾期与今日到期边界、标签去重、统计聚合。

`TaskApiTest` 与 `TaskFeatureApiTest` 都用 `@TestMethodOrder(Random)` 跑，并在 `@BeforeEach`
清库且**重置自增序列** —— H2 的自增不随事务回滚，不重置的话断言 `id == 1` 只会通过一次。
只有在这种随机顺序下仍然全绿，才说明用例之间真的隔离。

接口层除正常路径外，专门钉住了几个容易回归的地方：
`Accept: text/html` 不能退化成 Whitelabel 页面、405 必须带 `Allow`、415 不能被压成 400、
数字形式的枚举 ordinal 必须被拒、非法查询参数不能变成 500。

新能力另补的防失真断言：同状态 PATCH 后 `updatedAt` 不变、`Instant` 线格式是 ISO-8601 UTC 形状、
枚举确实按名落库（直接读原生列）、tags 顺序列保序、清空 tags 会真的删掉子行。

接口层除正常路径外，专门钉住了几个容易回归的地方：
`Accept: text/html` 不能退化成 Whitelabel 页面、405 必须带 `Allow`、415 不能被压成 400、
数字形式的枚举 ordinal 必须被拒、非法查询参数不能变成 500。

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

- **单实例本地部署**：H2 文件库同一时刻只允许一个进程持有，不能多实例水平扩展。
- **环境变量没有鉴权**：任何能访问端口的人都能改所有任务，也能触发 `replace` 清库。
  `confirm:"replace"` 防的是「误调用」，不是「恶意调用」。
- **没有登录与权限**：同上，见上一条。
- **乐观锁只到 409，且只在写入事务重叠时生效**：两个请求**同时**改同一条任务时，后提交者拿到
  `CONCURRENT_MODIFICATION`，本次写入不生效，服务端不自动重试。但客户端既拿不到也传不回版本号
  （响应无 `version`，PUT 不接受 `version`），所以两个标签页**先后**读、**先后**存（间隔几十毫秒以上）
  时，后存者仍会以 200 覆盖前者——这不是丢更新保护，只是同瞬间并发保护。要真正防丢更新，
  需要响应暴露 `version` 并让 PUT 支持 `If-Match`。演示场景够用。
- **`ddl-auto=update`**：适合本地与演示，不做版本化迁移。结构演进到需要回滚/审计的规模时，
  应换成 Flyway/Liquibase 这类迁移工具。
- **允许创建已过期的截止日期**：新建时也不拦。因为编辑一条本来就已经逾期的任务时，
  「逾期」是一个**状态**而不是**非法输入**；拦了既会把正常编辑变成无法保存，也和「逾期数」统计
  自相矛盾。逾期改为在界面上标红提示。
- **两个统计口径不同**：`GET /api/tasks/stats` 是全局（不受筛选影响），顶部统计条是按筛选结果
  在前端算的。这是刻意的 —— 演示时需要「筛出来多少」和「总共有多少」同时可见。
- **`overdue` 只做正向筛选**：`?overdue=true` 才筛；`false` 与不传等价。因为逾期只对
  未完成任务有意义，`DONE` 任务即便截止日期已过也不算逾期。
- **历史只记「变了什么」，不记「谁改的」**：没有登录体系，就没有可信的操作人。
  接入认证后应给 `task_activities` 增加 `actor` 列。
- **拖拽依赖 HTML5 原生 API**：桌面浏览器体验完整；移动端浏览器普遍不支持 HTML5 拖拽，
  所以每张卡片都保留了按钮操作作为等价入口。

---

## 开发

```bash
./mvnw -B test                              # 全量测试
./mvnw -B package -DskipTests               # 打包
java -jar target/taskboard-0.0.1-SNAPSHOT.jar --server.port=9090
```

### 启停要按 PID 精确控制

```bash
# 启动并记录真实 JVM 的 PID
java -jar target/taskboard-0.0.1-SNAPSHOT.jar > /tmp/taskboard.log 2>&1 &
echo $! > /tmp/taskboard.pid

# 停止：只杀这个 PID，等 5 秒不退再 kill -9 同一个 PID
kill "$(cat /tmp/taskboard.pid)"
```

**不要用 `lsof -ti:8080 | xargs kill` 或任何按端口/按进程名批量杀。** `lsof -ti:<port>` 匹配的是
所有本地端口等于该值的套接字，**包含出站连接** —— macOS 上企业微信、微信会大量使用 8080 作出站
远端端口，这条命令会连它们一起杀掉，而且往往连自己的服务都没杀到（如果它已退出）。
只想查端口占用请用只读命令：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN   # 必须带 -sTCP:LISTEN
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
