# AGENT_REPORT.md — Legacy Commerce Recovery (Round 2)

## 1. 执行时间与环境

| 项目 | 值 |
| --- | --- |
| 收到任务提示词 / 开始执行时间 | `2026-11-24T19:50:42+08:00` |
| 首次完整通过公开测试时间 | `2026-11-24T19:53:04+08:00` |
| 开始到首次全绿耗时 | 2 分 22 秒 |
| 最终完成时间 | `2026-11-24T19:54:51+08:00` |
| 总耗时（开始 → 结束） | 4 分 9 秒 |
| 测试失败 / 返工 / 重新验证次数 | 基线失败 1 次（4 通过 3 失败），修复后首次全绿，最终又完整复验 2 次，无返工 |
| 人工干预次数 | 0 |
| 无法准确获取的时间或统计项 | 无 |

说明：唯一一次失败是任务要求的**基线测试**（诊断证据），非修改后回归失败；修改后所有测试运行均一次通过，不存在返工。

**环境：**
- Java：`javac 17.0.20.1` / `openjdk 17.0.20.1`（Temurin, 64-Bit Server VM, mixed mode, sharing）
- OS：`MINGW64_NT-10.0-19045`（Windows 10 Pro x64, Git Bash）
- 实际使用命令：
  - `bash run-tests.sh`（基线、修复后、含新增用例、最终验证共 4 次）
  - `date +"...%:z"`（记录 ISO 8601 时间戳）
  - `javac --release 17 -Xlint:all -d out ...`（编译警告检查）
  - `git status --short`、`git diff --check`、`git diff --stat`、`git checkout -- run-tests.sh`、`git -c core.filemode=false status --short`

## 2. 问题理解

用户反馈的"已支付却库存被释放/被其他订单占用""重复取消或超时后库存数字不对""重复回调/迟到支付/取消交错导致异常""退款后财务记录不一致"——本质是**跨模块状态与副作用缺乏一致的提交边界，且关键转换不是幂等的**。具体表现为：

- 支付回调重放会重复执行业务转换（重复提交库存、重复发成功事件）。
- 已取消/已过期的订单收到迟到支付会被"复活"为 PAID，库存被错误提交。
- 重复退款请求会把退款金额重复累加。
- 超时扫描与支付并发时，可能在提交瞬间基于过期状态判断，留下"已支付但库存被释放"或"已取消但库存被提交"的脏数据。

普通单次流程正常，是因为这些缺陷只在**重复请求或并发交错**下才暴露。

## 3. 项目与依赖关系

| 模块 | 职责 | 相互影响 |
| --- | --- | --- |
| `CommerceSystem` | 公开门面 | 所有公共入口的锁边界所在 |
| `CommerceStore` | 共享内存状态 + `mutex` | 持有 orders/inventory/reservations/coupons/payments/refunds/events；`mutex` 原先定义但未使用 |
| `OrderService` | 订单生命周期（创建/取消/支付转换） | 协调库存与优惠券副作用；状态判断与副作用必须原子 |
| `InventoryService` | 库存预留/释放/提交 | 依赖 `Reservation` 状态机保证幂等 |
| `CouponService` | 优惠券预留/释放/消费 | 与订单/退款状态耦合 |
| `PaymentService` | 回调幂等、冲突检测、迟到补偿 | 调用 `OrderService` 应用支付；写支付幂等记录与审计事件 |
| `ExpirationJob` | 超时扫描与取消 | 与支付/手动取消竞争同一订单状态 |
| `RefundService` | 退款幂等、累计上限、支付记录更新 | 依赖订单状态与支付捕获记录 |

因果链：`PaymentService`/`ExpirationJob`/`OrderService.cancel` 都读写订单状态并触发库存、优惠券、事件副作用，但原先**没有任何共享锁**，且 `Reservation` 用字符串状态、release/commit 不检查状态，导致重放与竞争下状态与库存分叉。

## 4. 根因和故障链

| # | 根因 | 位置（原始代码） | 复现证据 | 故障链 |
| --- | --- | --- | --- | --- |
| 1 | 支付幂等记录写入后仍重放业务转换 | `PaymentService.success`：把 paymentId 放入 `store.payments` 后仍调用 `orders.markPaid`，重复回调第二次走 `markPaid` 返回 false 但订单已 PAID → 返回 `REFUND_REQUIRED` | 公开测试 `duplicatePaymentCallbackMustBeIdempotent` 基线失败：期望 `DUPLICATE` 实际 `REFUND_REQUIRED` | 重复回调 → 误判为需要退款 → 财务/审计事件混乱 |
| 2 | 迟到支付可复活已取消订单 | `OrderService.markPaid` 只拒绝 PAID/REFUNDED/PARTIALLY_REFUNDED，不拒绝 CANCELLED，`inventory.commit` 在状态判断外执行 | 公开测试 `latePaymentMustNotResurrectCancelledOrder` 基线失败：期望 `REFUND_REQUIRED` 实际 `ACCEPTED` | 已取消订单收到迟到支付 → 状态变 PAID + 库存被提交 → 与用户取消后的库存释放冲突 |
| 3 | 退款重放重复累加金额 | `RefundService.refund`：重放时注释"used to be treated as another financial operation"但代码继续 `order.refunded += amountCents` | 公开测试 `refundReplayAndCumulativeLimit` 基线失败：期望 600 实际 1200 | 重复退款 → `refunded` 翻倍 → 越过累计上限 → 财务记录不一致 |
| 4 | 库存预留释放/提交不幂等 | `InventoryService.release/commit`：仅检查 `reservation == null`，不检查状态；`Reservation.state` 是字符串且转换无保护 | 新增测试 + 代码审查；重复 cancel 或支付后再次 release 会把库存重复退回/提交 | 重复释放 → available 凭空增加；重复提交 → sold 重复累加 |
| 5 | 无一致提交边界 | `CommerceStore.mutex` 定义但从未被使用；各服务各自读写共享 Map | 并发竞争测试（200 次支付/超时竞争、16 线程重复回调）在修复前无法保证原子性 | 状态检查与副作用之间被并发插入另一操作 → 脏状态 |

## 5. 修改方案

1. **统一提交边界**：`CommerceSystem` 的每个公共方法用 `synchronized (store.mutex)` 包裹，使订单状态检查与库存/优惠券/支付/事件副作用在同一临界区完成。保留 `CommerceStore.mutex` 原字段，不引入新依赖。
2. **支付回调重构**：`PaymentService.success` 先查幂等记录——命中且一致直接返回 `DUPLICATE`，不一致返回 `CONFLICT`；金额错误直接 `REJECTED` 且**不写幂等记录**（允许后续用正确金额重试）；调用新的 `OrderService.applyPayment` 得到结果；迟到/重复支付写 `REFUND_REQUIRED` 状态并发 `PAYMENT_REFUND_REQUIRED` 事件。
3. **订单支付转换集中化**：新增 `OrderService.applyPayment`，只有 `PENDING_PAYMENT` 且金额匹配才提交库存、消费优惠券、置 PAID 并发成功事件；其他状态一律 `REFUND_REQUIRED`。移除了原 `markPaid`（不再被任何调用方使用），避免分散判断。
4. **退款修正**：先查 `refundId`——冲突直接拒绝，重放直接返回 `duplicate=true` 且**不再累加**；金额校验改为 `amountCents > order.paid - order.refunded`（剩余可退额）；仅对 `CAPTURED`/部分退款状态的支付记录更新退款累计，跳过 `REFUND_REQUIRED` 记录（迟到支付产生的补偿记录不应被当作捕获款）。
5. **库存预留状态机**：`Reservation.state` 改为枚举 `{RESERVED, RELEASED, COMMITTED}`；`release/commit` 仅在 `RESERVED` 时执行副作用并转换状态，重复调用为 no-op。
6. **超时二次校验**：`ExpirationJob.run` 在提交取消前重新读取订单并再次确认 `PENDING_PAYMENT`、`now >= expiresAt`，与 `orders.cancel` 内部的判断形成双保险。

**考虑过但未采用的方案：**
- 引入数据库事务/消息队列：超出"无第三方依赖、单进程内存服务"的约束，且收益不匹配。
- 把全部业务塞进一个"万能管理器"：违反任务的代码质量要求（不要巨型方法/万能管理器），当前按领域拆分更清晰。
- 在门面层做深度拷贝后整体替换状态：改动面大且无实际价值。

## 6. 功能保护

刻意保护并验证的原有正常行为：
- 单次下单→支付→库存提交→优惠券消费（公开 happy path 通过）。
- 手动取消释放全部行项目与优惠券，重复取消 no-op（公开测试通过）。
- 多 SKU 全有或全无、失败创建不遗留优惠券（公开测试 + 新增 `multiSkuOrderIsAllOrNothing` 通过）。
- 超时只取消到期订单（公开 `expirationCancelsOnlyDueOrders` 通过）。
- 迟到支付不改变订单/库存，只记录补偿（公开 + 新增验证）。
- 退款不释放/回加库存、已使用优惠券不因退款复活（新增 `fullRefundThenNoFurtherRefund` 通过）。
- `CommerceSystem` 公开方法与公开类型签名全部保持不变。

## 7. 测试与验证

**基线（修改前）**：`4 passed, 3 failed`，失败项：迟到支付、重复回调、退款重放。

**最终结果**：`15 passed, 0 failed`（公开 7 项 + 新增 8 项，共 15 项）。

**新增测试（覆盖根因、副作用、重复、边界、并发）：**
- `expirationRacingPaymentCommitsExactlyOnce`：200 次随机竞争，支付与超时并发，断言"要么干净成交要么干净取消"，库存不丢失不重复，恰好一个终态支付事件。
- `paymentIdConflictDoesNotMutateOrders`：paymentId 复用冲突不改订单。
- `wrongAmountCanBeRetriedWithSamePaymentId`：错误金额不写幂等记录，可用同一 paymentId 重试。
- `secondDistinctPaymentForPaidOrderRequiresRefund`：已支付订单第二笔扣款记补偿、不重复提交库存。
- `refundIdConflictAndInvalidAmounts`：退款 id 跨订单/改金额冲突、负数/零/超额拒绝。
- `fullRefundThenNoFurtherRefund`：全额退款后状态、优惠券、库存、后续退款拒绝。
- `multiSkuOrderIsAllOrNothing`：多 SKU 缺货全部回滚。
- `parallelDuplicateCallbacksCommitOnce`：16 线程重复回调，恰好 1 个 ACCEPTED、其余 DUPLICATE，库存只提交一次。

**重复/并发运行**：并发竞争测试循环 200 次，重复回调测试 16 线程，均通过。`bash run-tests.sh` 在修改后共运行 4 次（首次全绿 + 含新增用例 + 最终验证 2 次），全部通过。

**编译检查**：`javac --release 17 -Xlint:all` 无任何警告。`git diff --check` 无空白错误（仅 Windows CRLF 提示，非缺陷）。

**未执行的验证**：无（隐藏测试套件未提供，无法执行）。

## 8. 代码质量审查

- **重复逻辑收敛**：支付状态判断从 `PaymentService` 与 `OrderService.markPaid` 两处收敛到 `OrderService.applyPayment` 一处；退款重放分支不再继续执行副作用。
- **统一边界**：所有共享状态访问集中在门面的 `synchronized (store.mutex)`，消除散落的锁缺失。
- **魔法字符串消除**：`Reservation.state` 从 `"RESERVED"/"RELEASED"/"COMMITTED"` 字符串改为枚举。
- **死代码清理**：移除不再被引用的 `markPaid`；移除误导性的"historical code"注释和复制粘贴补丁式注释。
- **命名与聚焦**：新增 `applyPayment`、`capturedPayment`、`totalRefunded` 等表达业务含义的方法；`PaymentService`/`RefundService` 职责更单一。
- **未做的不必要重构**：未引入框架、依赖或架构迁移；未改动公开类型与方法签名；未格式化无关文件；未做"为整洁而整洁"的大范围重写。

## 9. 剩余风险

- 隐藏测试套件（Judge_Only_Package）未提供，无法验证其覆盖的额外场景；当前所有可见与自补测试均通过。
- 并发正确性依赖所有调用方通过 `CommerceSystem` 门面访问；若未来有代码绕过门面直接操作 `CommerceStore`，需自行保证持锁。当前仓库内不存在此类调用。
- `REFUND_REQUIRED` 仅作为审计记录，不实际调用外部网关退款（任务明确外部资金退回不在范围内）。
- 未发现其他未解决问题。

## 10. 最终文件清单与 Git 状态

**实际修改文件（8 个）：**
- `src/main/java/bench/commerce/CommerceSystem.java`
- `src/main/java/bench/commerce/ExpirationJob.java`
- `src/main/java/bench/commerce/InventoryService.java`
- `src/main/java/bench/commerce/OrderService.java`
- `src/main/java/bench/commerce/PaymentService.java`
- `src/main/java/bench/commerce/RefundService.java`
- `src/main/java/bench/commerce/Reservation.java`
- `src/test/java/bench/commerce/CommerceTest.java`

**新增文件：**
- `AGENT_REPORT.md`

**构建产物与临时文件**：`out/` 由 `run-tests.sh` 生成，已被 `.gitignore`（`out/`）忽略，未纳入提交；无其他临时文件。

**Git 状态说明**：`git status --short` 默认显示 `run-tests.sh` 为修改，但它内容零差异（`git diff --numstat` 为 `0 0`），仅为 Windows 下文件模式（755/644）跟踪噪音；用 `git -c core.filemode=false status --short` 可见它不在修改列表中。未对该文件做内容修改。
