# AGENT_REPORT — Legacy Commerce Recovery (Round 2)

## 1. 执行时间与环境

| 项目 | 值 |
| --- | --- |
| 收到任务提示词 / 开始执行 | `2026-11-24T20:32:44+08:00` |
| 首次完整通过公开测试 | `2026-11-24T20:41:36+08:00`（距开始 8 分 52 秒） |
| 最终完成时间 | `2026-11-24T20:51:26+08:00` |
| 总耗时 | **18 分 42 秒**（按上两行计算） |
| 修改后测试失败 / 返工 / 重新验证 | 4 次（3 次新增测试代码编译错误 + 1 次新增断言期望值写错），另有 6 次完整验证运行 |
| 人工干预次数 | **0** |

环境：

- Java：`openjdk version "17.0.20.1" 2026-08-18`（Temurin 17.0.20.1+1），编译目标 `--release 17`
- 操作系统：`Microsoft Windows 10 专业版`，`Microsoft Windows NT 10.0.19045.0`
- Shell：PowerShell 7 (`pwsh`)

实际使用的命令：

```powershell
# 基线 / 最终验证（项目自带脚本，PowerShell 版本，入口 bench.commerce.CommerceTest）
powershell -ExecutionPolicy Bypass -File .\run-tests.ps1

# 编译警告闸门 + 重复运行
javac --release 17 -Xlint:all -Werror -d out-check <all *.java>
java -ea -cp out-check bench.commerce.CommerceTest    # 连续 5 次

git diff --check
git status --short
```

> 时间说明：开始时间取任务元数据中给定的本地时间；各阶段时间点取自命令执行记录的实际时间戳，未做估算。最终完成时间为最后一次校验之后读取的系统时间。

## 2. 问题理解

用户反馈的三个现象其实指向同一类缺陷：**“状态检查”和“副作用”不在同一个提交边界内，而且状态本身没有成为唯一事实来源。**

- “订单显示已支付，但库存像被释放后被别的订单占用”：迟到的支付回调在订单已取消后仍然走了支付路径，把已释放的预留再次 commit，`reserved` 变成负数、`sold` 增加。
- “重复点击取消 / 超时任务重复执行后库存数字不对”：预留没有状态机，`release()` 可以被执行任意多次，每次都在 `reserved/available` 上再改一次。
- “支付平台重复投递回调”：幂等记录被写入后仍然继续执行业务迁移，重复回调要么再次迁移，要么被错判为需要退款。
- “部分退款和重复请求后财务记录不一致”：退款重放没有提前返回，累计上限用的是“已支付额”而不是“剩余可退额”，退款登记又发生在校验之前。

普通单次下单/支付不触发这些路径，所以问题只在“重复 + 交错”时出现。

## 3. 项目与依赖关系

```
CommerceSystem (公开门面, 唯一入口)
  └─ CommerceStore        共享状态 + 事件流水 + 事务边界
       ├─ InventoryService    SKU 三桶: available / reserved / sold
       ├─ CouponService       AVAILABLE → RESERVED → USED / 释放回 AVAILABLE
       ├─ OrderService        订单状态机 + 创建/取消/过期/支付落账
       ├─ PaymentService      回调幂等、金额校验、迟到支付补偿
       ├─ ExpirationJob       到期扫描（复用取消迁移）
       └─ RefundService       退款幂等与累计上限，回写 PaymentRecord
```

关键耦合：

- 订单状态决定库存和优惠券能否迁移；库存预留状态决定 `release/commit` 是否还有效。
- 支付回调读订单状态 + 写 `payments` + 改库存 + 发事件，四个动作必须原子。
- 过期扫描与人工取消共用同一个“未支付 → 已取消”迁移。
- 退款只读订单的 `paid/refunded`，并回写“该订单真正成功的那笔支付”。

## 4. 根因与故障链

### RC-1 预留没有状态机，`release`/`commit` 可重复执行

`InventoryService.release` / `commit` 只看预留是否存在，不看它已经处于什么状态（旧代码用字符串 `state` 但从不校验，也没有 `RELEASED/COMMITTED` 常量）。任意第二次调用都会再改一次桶值。

**证据**：取消后再执行一次过期扫描，`reserved` 被减两次而变负，`available` 被加两次。
**传播**：这是“重复取消 / 重复超时任务后库存不对”的直接原因，也是 RC-2 能造成破坏的前提（迟到支付 commit 一个已释放的预留）。

### RC-2 迟到 / 重复支付回调会重放业务迁移

`PaymentService.success` 在写入幂等记录后**没有提前返回**，继续调用 `orders.markPaid`；而 `markPaid` 只排除 `PAID / REFUNDED / PARTIALLY_REFUNDED`，**不排除 `CANCELLED`**。

**证据**（基线失败用例）：`latePaymentMustNotResurrectCancelledOrder` 期望 `REFUND_REQUIRED`，实际 `ACCEPTED`，订单被改回 `PAID`，库存被 commit。
**传播**：取消/过期释放库存 → 迟到回调 commit 同一预留 → `reserved` 变负、`sold` 增加 → 库存“像被释放又被占用”；同时缺 `PAYMENT_REFUND_REQUIRED` 审计事件，钱已扣但无补偿记录。

### RC-3 重复回调被误判为“需要退款”

`markPaid` 对已支付订单返回 `false`，`success` 随即落到 `REFUND_REQUIRED` 分支，而不是 `DUPLICATE`。

**证据**：`duplicatePaymentCallbackMustBeIdempotent` 期望 `DUPLICATE`，实际 `REFUND_REQUIRED`。

### RC-4 退款重放会重复扣款

`RefundService.refund` 找到同 `refundId` 记录后只写了注释，**没有 return**，继续执行 `order.refunded += amount`。

**证据**：`refundReplayAndCumulativeLimit` 期望 `refunded=600`，实际 `1200`。

### RC-5 累计退款上限用错基准

校验是 `amountCents > order.paid`，不是 `amountCents > paid - refunded`。因此“再退 500（剩 400）”被接受，累计可超过实收。

### RC-6 退款登记早于校验

`store.refunds.put(...)` 在金额校验之前执行。被拒绝的退款仍占用 `refundId`，后续同 id 合法请求会被当成重放或冲突——账实不符且无法补救。

### RC-7 退款回写到“第一笔找到的支付”而不是成功的那笔

`for (PaymentRecord payment : store.payments.values())` 取该订单第一条支付记录就 `break`。当同一订单存在迟到支付（`REFUND_REQUIRED`）时，退款可能记到错误的支付记录上，财务流水错乱。

### RC-8 全局没有任何同步

`CommerceStore` 里已有 `mutex` 字段但从未使用。所有公开方法都无锁：过期扫描是“先扫一遍再逐个取消”的两段式，中间可以被支付插入 → 已支付订单被释放库存，或已取消订单被 commit。

### RC-9 金额不符的回调污染幂等记录

`success` 在金额校验之前就把 `paymentId` 写入 `payments`。金额不符被拒后，同一 `paymentId` 用正确金额重试时会被当成已存在记录，无法完成支付——违反“金额不符不得改变支付幂等记录，同一 paymentId 可稍后用正确报文重试”。

### 故障链总览

```
取消/超时释放库存(reserved↓ available↑)
        │  RC-1 无状态保护
        ▼
迟到回调 markPaid 不看 CANCELLED (RC-2) ──► commit 已释放的预留
        │                                        │
        │                                        ▼
        │                              reserved 变负 / sold 虚增 / 订单复活
        ▼
重复回调落进 REFUND_REQUIRED (RC-3) ──► 缺补偿事件，财务与库存同时不一致
        │
        └─ 退款重放(RC-4)/上限错(RC-5)/登记过早(RC-6)/回写错支付(RC-7) ──► 财务不一致
所有环节均无锁 (RC-8) ──► 上述问题只在“重复 + 交错”时偶发，无法稳定复现
```

## 5. 修改方案

| 根因 | 修改 | 位置 |
| --- | --- | --- |
| RC-1 | 引入 `ReservationState { RESERVED, RELEASED, COMMITTED }`；`release/commit` 仅在 `RESERVED` 时生效并返回 `boolean`，迁移后不可再改库存 | `ReservationState.java`（新）、`Reservation.java`、`InventoryService.java` |
| RC-1 | 桶运算收敛到 `InventoryRecord.reserve/release/commit`，越界直接抛异常，保证三桶非负且守恒 | `InventoryRecord.java` |
| RC-2 | `PaymentService` 先查幂等记录再决定：已存在 → 重放结果；订单已取消/过期 → 记 `REFUND_REQUIRED` + 发 `PAYMENT_REFUND_REQUIRED`，**不碰库存** | `PaymentService.java` |
| RC-2 | 支付迁移只允许从 `PENDING_PAYMENT` 发生，用 `Order` 上的状态谓词集中表达 | `OrderService.completePayment` |
| RC-3 | 重放按记录状态返回 `DUPLICATE`（成功支付）或 `REFUND_REQUIRED`（补偿记录） | `PaymentService.replayOf` |
| RC-4/RC-5/RC-6 | 退款：先判空 → 再判 `refundId` 重放/冲突 → 再判可退状态 → 再判剩余可退额 → **最后**登记并落账 | `RefundService.java` |
| RC-7 | `CommerceStore.capturedPaymentFor` 明确取“非 `REFUND_REQUIRED` 的那笔”支付 | `CommerceStore.java` |
| RC-8 | `CommerceStore.transaction(...)` 统一提交边界；`CommerceSystem` 每个公开方法都经此执行（锁可重入，服务间可互调） | `CommerceStore.java`、`CommerceSystem.java` |
| RC-9 | 金额校验通过后才写 `payments`，不符则完全不落痕 | `PaymentService.success` |
| — | 过期扫描复用取消迁移 `OrderService.expire(order, now)`，把“判到期 + 迁移”放进同一事务 | `ExpirationJob.java` |
| — | 事件名收敛到 `EventTypes`，消除魔法字符串 | `EventTypes.java`（新） |

**考虑过但没有采用的方案**：

- *把所有逻辑塞进一个 `CommerceManager` 大方法*：能过测试，但违背题目对职责划分和可维护性的要求，也让后续不变量更难定位。
- *用 `ReentrantLock` / 细粒度锁按 SKU 分割*：单进程内存模型下收益极低，反而引入锁顺序风险；一个可重入临界区已经能满足“状态检查与副作用同一提交边界”。
- *在 `CommerceSystem` 里手写 `synchronized (store.mutex)` 包裹每个方法*：能用，但把同一件事复制 14 遍；改为一个 `transaction(...)` 收敛。
- *为退款状态引入独立的 `RefundStatus` 枚举*：`PaymentStatus` 已覆盖语义，再加一层是空抽象。
- *删除 `Reservation.state` 字符串、改由 `Order.status` 推断*：订单与预留生命周期并不一一对应（如创建失败回滚），保留显式状态更清楚。

## 6. 功能保护

刻意保持不变的行为：

- `CommerceSystem` 的全部公开方法与签名、`OrderStatus` / `CouponStatus` / `CallbackResult` / `RefundResult` 语义均未改动。
- 创建订单的 all-or-nothing：先预留优惠券再预留库存，库存不足时释放优惠券并抛 `IllegalStateException`；金额 `Math.addExact` 溢出保护保留。
- `payable = subtotal - discount`，优惠券折扣上限为 `min(discount, subtotal)`。
- 已支付订单不能走未支付取消路径；退款不动库存；已用优惠券不因退款复活。
- 过期边界仍是 `now >= expiresAt`；`ttlMillis` 溢出保护保留。
- 事件仍按单调 `sequence` 追加，`events()` 仍返回不可变快照。
- 未新增任何第三方依赖、未联网、未引入框架，`src/main` 仍为 25 个包内类。

验证方式：原 7 个公开测试全部保留且未放宽断言，逐条覆盖上述规则；另加 19 个测试覆盖多 SKU、优惠券争用、部分/全额退款、迟到支付、重复回调、边界与并发竞争。

## 7. 测试与验证

**基线**（未修改代码，`run-tests.ps1`）：

```
PASS checkoutAndPaymentHappyPath
PASS manualCancellationReleasesAllLinesAndCoupon
FAIL latePaymentMustNotResurrectCancelledOrder: expected <REFUND_REQUIRED> but was <ACCEPTED>
FAIL duplicatePaymentCallbackMustBeIdempotent: expected <DUPLICATE> but was <REFUND_REQUIRED>
FAIL refundReplayAndCumulativeLimit: refund total remains 600 expected <600> but was <1200>
PASS expirationCancelsOnlyDueOrders
PASS failedCreationMustNotStrandCoupon
RESULT: 4 passed, 3 failed
```

**最终**（仓库根目录，`run-tests.ps1`）：

```
RESULT: 26 passed, 0 failed      (exit code 0)
```

新增测试（19 个）：

| 类别 | 测试 |
| --- | --- |
| 重复迁移 | `repeatedCancellationDoesNotMoveStockTwice`、`expiryIsIdempotentAndReleasesExactlyOnce`、`paidOrderCannotBeCancelledOrExpired` |
| 支付回调 | `latePaymentAfterExpiryNeedsRefundWithoutStockChange`、`duplicateCallbackOnCancelledOrderIsIdempotent`、`secondDistinctPaymentNeedsRefund`、`paymentIdReuseConflictLeavesBothOrdersUntouched`、`amountMismatchLeavesNoTraceAndAllowsRetry`、`rejectedCallbackCreatesNoPaymentRecord` |
| 库存 / 优惠券 | `multiSkuReservationIsAllOrNothing`、`couponCannotBeReservedByTwoLiveOrders`、`inventoryBucketsStayBalanced` |
| 退款 | `fullRefundMovesToRefundedAndKeepsCouponUsed`、`refundIdReuseConflictDoesNotMoveMoney`、`refundsRejectInvalidRequestsAndUnpaidOrders` |
| 并发 | `concurrentCancelAndTimeoutReleaseOnce`、`concurrentPaymentAndExpiryCommitOrReleaseOnce`、`concurrentDuplicateCallbacksCommitOnce`、`concurrentDuplicateRefundsApplyOnce` |

并发测试的设计：

- 使用注入的 `ManualTime` 控制时间，**没有 `Thread.sleep`**。
- 用 `CyclicBarrier` 让 2~3 个线程在同一栅栏上同时放行，确保真正重叠而不是顺序执行。
- 每个竞争场景重复 50 轮；断言的是“不变量”而不是“谁赢”：取消/过期竞争后必须 `available=total, reserved=0, sold=0` 且只有 1 个 `ORDER_CANCELLED`；支付/过期竞争后订单要么 `PAID`（`sold=2, available=0`，1 个 `PAYMENT_SUCCEEDED`）要么 `CANCELLED`（`available=2, sold=0`，1 个 `PAYMENT_REFUND_REQUIRED`），且三桶始终守恒。
- 另有 `assertBuckets` 在多个用例里显式校验 `available + reserved + sold == total` 且非负。

其他验证：

- `javac --release 17 -Xlint:all -Werror` 编译通过，**0 警告**。
- 连续 5 次运行完整测试，均为 `26 passed, 0 failed`。
- `git diff --check` 无空白错误。
- 构建产物：`run-tests.ps1` 生成的 `out/` 已被 `.gitignore` 忽略，最终未纳入提交；临时 `out-check/` 已删除。

**重复/并发运行**：见上，5 次全绿；并发用例每轮 50 次 × 4 个场景 = 200 次竞争执行，全部通过。另从 GitHub 重新克隆 `antigravity/round-2-legacy-commerce` 分支（SSH，HEAD `df02504`）后独立运行 `run-tests.ps1`，结果同为 `26 passed, 0 failed`（exit 0），确认提交内容完整可用。

**未执行的验证**：`run-tests.sh`（bash 版本）未在本机执行——当前环境无 Git Bash/WSL 可用，仅核对了它与 `run-tests.ps1` 使用相同的编译参数与入口。这是唯一未实际运行的脚本。

**人工干预**：0 次（无提示、无外部输入、无人工修正）。

## 8. 代码质量审查

改善项：

- **消除重复状态判断**：`Order` 上的 `isPendingPayment()` / `isRefundable()` / `remainingRefundable()` 取代了散落在 `OrderService`、`PaymentService`、`RefundService` 里各自拼写的 `status == X || status == Y` 列表。此前 `markPaid` 的排除列表与 `RefundService` 的可退列表是两份独立实现，正是 RC-2 的来源。
- **集中不变量**：`available + reserved + sold` 的守恒与非负约束只在 `InventoryRecord` 的三个方法里维护，调用方不再直接改字段。
- **明确状态迁移**：新增 `ReservationState` 枚举替代裸字符串 `"RESERVED" / "RELEASED" / "COMMITTED"`，迁移的合法性由枚举状态表达。
- **统一提交边界**：`CommerceStore.transaction(...)` 是唯一加锁入口，替代 14 处重复的 `synchronized` 块；`ExpirationJob` 不再自己实现一套“取消”。
- **消除魔法字符串**：事件名收敛在 `EventTypes`，退款原因码收敛为 `RefundService` 内常量。
- **命名表达业务含义**：`Orders.completePayment` / `expire` / `cancelPending`、`recordRefundRequired`、`applyToCapturedPayment`、`replayOf`。
- **方法聚焦**：原 `RefundService.refund` 一个方法里混合了参数校验、重放、上限、登记、订单迁移、支付回写六件事，现拆为 `refund` + `applyToCapturedPayment`；`CancellationPending` 分支抽为 `cancelPending` 供取消与过期共用。
- **删除死代码与含糊注释**：移除了 `// Historical code assumes a caller will only release a live reservation.`、`// Replayed requests used to be treated as another financial operation.`、`// The gateway retries callbacks; this path historically replays the business transition too.` 等只描述“以前是错的”却无约束价值的注释；删除了未被使用的 `hasCapturedPayment`。
- **无空 catch、无异常吞噬、无调试输出**：异常只在真正表示“不变量被破坏”时抛出（如预留不存在却要 commit）。

明确**没有**做的不必要重构：

- 没有改包结构、没有引入接口层/工厂/DTO 映射。
- 没有重写 `Coupon`、`PaymentRecord`、`RefundRecord`、`OrderView`、`DomainEvent` 等原本就清晰的值对象。
- 没有格式化无关文件；改动集中在 10 个类 + 2 个新文件 + 测试文件。
- 没有为了“更面向对象”把 `CommerceSystem` 改成服务定位器或事件总线。

改动范围：`git diff --stat` 为 12 文件、667 增 / 104 删（含测试 400 行新增）。

## 9. 剩余风险

- **单进程假设**：所有一致性依赖 JVM 内的可重入锁。若将来拆成多实例或持久化存储，需换成数据库行锁/乐观版本号，当前设计不覆盖跨进程并发。这是 README 明确的内存单进程模型，属于已知边界。
- **`REFUND_REQUIRED` 无自动补偿**：迟到/重复支付只记录状态与审计事件，真正的退款动作依赖外部网关（README 明确服务无法撤销外部扣款）。本实现只保证“不重复占用库存、不重复卖出、留下可追溯记录”。
- **`CommerceSystem.events()` 返回全量快照**：长期运行时事件无限增长，内存会累积。原有行为如此，未改动。
- **`RefundService.applyToCapturedPayment` 在不变量被破坏时抛 `IllegalStateException`**：这是有意的“失败要响”，但如果历史数据里存在“已支付但无支付记录”的订单，退款会抛异常而非静默少记一笔。当前模型下该状态不可达。
- **未验证 bash 脚本**：见第 7 节。

未发现其他未解决问题。

## 10. 最终文件清单与 Git 状态

新增：

- `src/main/java/bench/commerce/EventTypes.java`
- `src/main/java/bench/commerce/ReservationState.java`
- `AGENT_REPORT.md`

修改（`src/main`）：

- `CommerceStore.java`（事务入口 + 支付定位）
- `CommerceSystem.java`（公开方法统一走事务）
- `OrderService.java`（状态机 + 共用取消/过期迁移 + 支付落账）
- `PaymentService.java`（幂等、金额校验顺序、迟到补偿）
- `RefundService.java`（顺序、上限、回写目标）
- `InventoryService.java`（预留状态、all-or-nothing）
- `InventoryRecord.java`（桶运算与不变量）
- `Order.java`（状态谓词）
- `Reservation.java`（状态枚举）
- `ExpirationJob.java`（复用取消迁移）

修改（测试）：`src/test/java/bench/commerce/CommerceTest.java`（7 → 26 个用例，原 7 个未改动）

未修改：`CallbackResult`、`Coupon`、`CouponService`、`CouponStatus`、`DomainEvent`、`OrderItem`、`OrderStatus`、`OrderView`、`PaymentRecord`、`PaymentStatus`、`RefundRecord`、`RefundResult`、`TimeSource`、`README.md`、`run-tests.ps1`、`run-tests.sh`、`.gitignore`。

构建产物与临时文件：

- `out/`（测试脚本生成）已被 `.gitignore` 忽略，未提交。
- 临时编译目录 `out-check/` 已删除。
- 工作区无未跟踪的临时文件（除交付用的 `Agent_Package/` 输入材料，见下）。

Git：

- 分支：`antigravity/round-2-legacy-commerce`，基于 benchmark 夹具提交 `a29f00d`。
- 提交内容：上述源码、测试与 `AGENT_REPORT.md`；`Agent_Package/`（题目输入材料，含上游 `.git` 目录）不纳入版本控制。
- `git diff --check` 通过，无尾随空白、无 CRLF 混入。