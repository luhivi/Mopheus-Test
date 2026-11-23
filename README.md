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
| 测试 | JUnit 5 + MockMvc | 随 Boot | 43 个用例（服务层 19 + 接口层 24） |
| 前端 | 原生 HTML5 / CSS3 / ES6+ | — | **不引入任何框架与构建链**，无 Node 依赖 |

**只需要 JDK 17。** Maven 由 wrapper 自举，npm / node 完全不需要。

存储是进程内 `ConcurrentHashMap`：**重启即丢，且只能单实例部署**。这是刻意的，
因为样例要的是「一条命令起得来」，不是「能上生产」。

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
**只有服务端一条任务都没有时才会写入**，不会覆盖已有数据；重启进程后示例数据也一起消失。

---

## 界面能做什么

| 能力 | 怎么用 |
| --- | --- |
| 快速新建 | 顶部输入框回车或点「添加」，只填标题 |
| 详细新建 / 编辑 | 「详细新建」或卡片上的「编辑」，弹窗里可设优先级、负责人、截止日期、标签 |
| 改状态 | 拖拽卡片到目标列，或点卡片上的「推进」（待办 → 进行中 → 已完成） |
| 筛选 | 状态、优先级、只看逾期、标题/标签关键词搜索 |
| 排序 | 创建时间 / 截止日期 / 优先级，点右侧按钮切升序降序 |
| 统计 | 顶部总数、各状态计数、逾期数，随筛选结果实时更新 |
| 状态可分享 | 筛选与排序条件写进 URL hash，复制链接即可还原同一视图 |

**拖拽的边界是显式处理的**：不能拖到「已完成」列，已完成卡片也不能被拖动 ——
服务端把 `DONE` 当终态，前端提前拒绝并给出提示，而不是发一个注定 409 的请求让卡片弹回去。
拖拽之外，每张卡片都保留了「推进」按钮，键盘和触屏用户不用依赖拖拽。

---

## 接口

所有错误统一为 `{code, message, timestamp}`，`message` 是白名单文案，不含堆栈、类名或表名。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/tasks` | 列表，支持筛选与排序 |
| `GET` | `/api/tasks/{id}` | 单条详情 |
| `GET` | `/api/tasks/stats` | 统计（各状态/优先级计数、逾期数、今日到期数） |
| `POST` | `/api/tasks` | 新建 |
| `PUT` | `/api/tasks/{id}` | 全量编辑（**不改状态**） |
| `PATCH` | `/api/tasks/{id}/status` | 只改状态，走流转校验 |
| `DELETE` | `/api/tasks/{id}` | 删除 |

### 查询参数（`GET /api/tasks`）

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `status` | `TODO` `DOING` `BLOCKED` `DONE` | 可重复，多值为「或」 |
| `priority` | `LOW` `NORMAL` `HIGH` `URGENT` | 可重复，多值为「或」 |
| `q` | 任意文本 | 匹配标题或标签，忽略大小写 |
| `assignee` | 任意文本 | 匹配负责人，忽略大小写 |
| `overdue` | `true` / `false` | 只留逾期 / 只留未逾期；不传表示不筛 |
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

### 字段约束

| 字段 | 约束 |
| --- | --- |
| `title` | 必填，去首尾空格后 1–80 字符 |
| `priority` | `LOW` / `NORMAL` / `HIGH` / `URGENT`，默认 `NORMAL` |
| `assignee` | 可空，去空格后最长 40 字符；空串按 `null` 存 |
| `dueDate` | `yyyy-MM-dd`，可空。**允许过去日期**，见[已知限制](#已知限制) |
| `tags` | 最多 5 个，每个 1–20 字符，去重后保持首次出现顺序 |
| `status` | 仅新建时可指定；编辑时由 `PATCH .../status` 独占 |

### 状态流转

```
TODO ⇄ DOING        TODO ⇄ BLOCKED
DOING ⇄ BLOCKED     DOING → DONE
```

规则很简单：**除了 `DONE` 之外，任意状态可以互转；`DONE` 是终态。**
把已完成的任务退回待办，等于悄悄撤销别人已经当作完成的工作，所以服务端直接拒绝。
重复提交当前状态是幂等的空操作，不算错误 —— 拖拽、重试、另一个标签页都会产生这种请求。

违反规则返回 **409 `INVALID_TRANSITION`**（不是 400：请求本身合法，是资源当前状态不允许）。

### 错误码

| HTTP | `code` | 触发场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段校验失败（标题空、标签过多等） |
| 400 | `MALFORMED_BODY` | JSON 语法错误，或枚举值不存在 |
| 400 | `MALFORMED_QUERY` | 查询参数取值非法 |
| 404 | `TASK_NOT_FOUND` | 任务不存在 |
| 404 | `NOT_FOUND` | 路由不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持（带 `Allow` 头） |
| 406 | `NOT_ACCEPTABLE` | 客户端要求非 JSON 表示 |
| 409 | `INVALID_TRANSITION` | 状态流转非法 |
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
  service/
    TaskService.java               内存存储、增删改查、统计
    TaskQuery.java                 筛选 + 排序（含 null 截止日期处理）
    TaskStats.java                 统计聚合结果
    TaskNotFoundException.java     404
    InvalidTaskTransitionException.java  409
  web/
    TaskController.java            7 个端点；/stats 声明在 /{id} 之前
    TaskPayload.java               新建/编辑入参 + 校验注解
    TaskResponse.java              出参；overdue 在此计算
    TaskStatsResponse.java         统计出参
    UpdateStatusRequest.java       改状态入参
    ApiExceptionHandler.java       统一错误体，保留框架状态码
    JacksonStrictConfig.java       拒绝数字/布尔强转成字符串与枚举
    MalformedQueryException.java   查询参数非法 → 400
src/main/resources/
  application.properties          端口 + 枚举严格模式
  static/index.html               看板、筛选栏、统计条、编辑弹窗
  static/app.js                   请求封装、渲染、拖拽、筛选、弹窗
  static/style.css                四列看板与卡片样式
src/test/java/com/example/taskboard/
  TaskApiTest.java                24 个 MockMvc 用例（含 406/415/405/409 回归）
  service/TaskServiceTest.java    19 个服务层用例（含完整流转矩阵）
  support/MutableClock.java       可推进的测试时钟
```

三条设计要点：

1. **状态机只有一个实现**，在 `TaskStatus.canMoveTo`。服务端与前端各自判断，但规则来源唯一。
2. **错误处理只有一条出口**，`ApiExceptionHandler` 把所有异常收成药丸状 JSON，并保留 Spring
   自己算出来的状态码与响应头（405 的 `Allow`、406 的 JSON 兜底都在这里）。
3. **前端三态 + 请求编号**：加载中/空/失败互斥渲染；每次列表请求带递增编号，旧响应回来
   直接丢弃，避免快速筛选时后到的旧数据覆盖新数据。

---

## 测试

```
$ ./mvnw -B test
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- TaskServiceTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- TaskApiTest
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

服务层覆盖完整流转矩阵（含 `DONE` 终态与幂等空操作）、排序（优先级序、截止日期 null 排最后）、
筛选组合、逾期与今日到期的边界、标签去重、统计聚合。

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

---

## 已知限制

这是**样例**，不是生产组件。以下是刻意的取舍，不是遗漏：

- **数据在内存里**：重启即丢，无法多实例部署，也没有持久化与迁移。
- **没有并发控制**：两个标签页同时编辑同一任务，后保存的覆盖先保存的，`updatedAt` 无法
  判断谁更新。演示场景可以接受，真实场景需要 `ETag` / `If-Match`。
- **允许创建已过期的截止日期**：新建时也不拦。因为编辑一条本来就已经逾期的任务时，
  它的截止日期必然在过去 —— 拦了就会把正常的编辑变成无法保存。逾期改为在界面上标红提示。
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
（或者用 IDE 的静态资源热替换）。语法自检（无 Node 环境下）：

```bash
/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
  -e "new Function(readFile('src/main/resources/static/app.js')); print('SYNTAX_OK')"
```
