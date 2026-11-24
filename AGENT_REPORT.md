# Agent Benchmark · Round 2 执行报告

## 1. 执行时间与环境

| 项目 | 值 |
|---|---|
| 收到任务提示词 / 开始执行时间 | 2026-11-24T20:15:55+08:00 |
| 首次完整通过公开测试时间点 | 2026-11-24T20:17:24+08:00 |
| 开始到首次公开测试全绿耗时 | 89 秒 |
| 最终验证完成时间 | 2026-11-24T20:18:49+08:00 |
| 总耗时（到最终验证） | 174 秒（2 分 54 秒） |
| 修改后测试失败 / 返工次数 | 3 次（1 次支付状态误判、1 次编译签名、1 次新增断言方向错误） |
| 人工干预次数 | 0 |

时间说明：上述时间由 `date --iso-8601=seconds` 实测记录；最终报告落盘时间晚于“最终验证完成时间”，因此该时间表示代码与测试验证完成点，而非报告文件写入瞬时时间。

- Java：`openjdk 17.0.20.1` / `javac 17.0.20.1`（Temurin）
- OS：Windows 10 Pro 10.0.19045，Git Bash MINGW64_NT-10.0-19045
- 实际命令：
  - `bash run-tests.sh`（基线、修复后、最终）
  - `java -ea -cp out bench.commerce.CommerceTest`（重复 5 次）
  - `git diff --check`、`git status --short`、`date --iso-8601=seconds`

## 2. 问题理解

用户反馈的“偶发”问题来自三个确定性缺陷的交叉触发：

1. 支付平台重复投递同一回调时，旧的 `PaymentService.success` 在写幂等记录后仍再次执行 `markPaid`，导致重复成功事件与重复销售。
2. 订单取消/超时后迟到的支付回调会把订单状态改回 `PAID`，并释放/重新提交库存；金钱已被外部网关捕获，系统应记录补偿要求而不是复活订单。
3. 退款重放未检查累计金额，`refundId` 相同且金额相同会再次累加，造成财务数据翻倍。
4. 上述入口均未在同一 `store.mutex` 边界内执行状态判断与库存、优惠券、支付、事件副作用，支付与超时并发时可产生撕裂状态。

## 3. 项目与依赖关系

- `CommerceSystem`：公共门面，所有公开方法现在统一持有 `store.mutex`。
- `OrderService`：订单状态机与副作用提交边界；负责创建、取消、过期、支付迁移。
- `PaymentService`：支付幂等键与冲突检测。
- `RefundService`：退款幂等、累计限额与订单/支付状态同步。
- `ExpirationJob`：扫描到期订单，逐单在锁内重新校验状态与时间。
- `InventoryService` / `CouponService` / `CommerceStore`：共享内存状态、预留状态与事件日志。

依赖方向：`CommerceSystem → {OrderService, PaymentService, RefundService, ExpirationJob} → CommerceStore + InventoryService + CouponService`。根因正是跨这些服务的状态检查与副作用不在同一提交边界。

## 4. 根因和故障链

| 根因 | 位置（修复前） | 复现证据 | 故障链 |
|---|---|---|---|
| 迟到支付可复活已取消订单 | `OrderService.markPaid` 第 54–70 行（旧版只排除 PAID/REFUND 类状态） | 基线 `latePaymentMustNotResurrectCancelledOrder` 得到 `ACCEPTED` | 取消 → 库存已释放 → 迟到回调 → 再次 commit 库存/消耗优惠券 → 订单 PAID 但库存账实不符 |
| 重复回调重复执行业务 | `PaymentService.success` 第 20–29 行（旧版先写记录再无条件 markPaid） | 基线 `duplicatePaymentCallbackMustBeIdempotent` 得到 `REFUND_REQUIRED` 而非 `DUPLICATE` | 首次 ACCEPTED → 重放命中已支付 → 误报需退款；若状态仍 PENDING 则重复卖货 |
| 退款重放重复累加 | `RefundService.refund` 第 21–34 行（旧版发现 previous 后继续累加） | 基线 `refundReplayAndCumulativeLimit` 得到 1200 而非 600 | 同一退款请求重放 → 累计退款超过捕获金额 → 财务对账错误 |
| 状态检查与副作用非原子 | 多个公开方法无统一锁；`Reservation.state` 用字符串且未检查 | 新增并发测试 `paymentExpirationRaceCommitsExactlyOnce` / `concurrentDuplicateCallbacksCommitOnce` | 支付与超时/重复回调交错 → 同一预留被 release 与 commit 同时处理 → 库存桶失衡 |
| 金额不匹配占用支付幂等键 | 旧 `PaymentService.success` 先写 `payments` 再检查金额 | 新增测试 `amountMismatchDoesNotConsumePaymentId` | 错误金额非幂等占用 paymentId → 后续正确金额回调被误判 CONFLICT |

## 5. 修改方案

1. **统一提交边界**：`CommerceSystem` 所有公开方法及 `PaymentService`/`OrderService`/`RefundService` 的变更方法都在 `store.mutex` 内完成状态判断和全部副作用；`ExpirationJob` 扫描后逐单在锁内重检 `PENDING_PAYMENT` 与 `now >= expiresAt`。
2. **集中订单状态机**：`OrderService` 用 `markPaid` 返回 `CallbackTransition`，把支付结果分为 ACCEPTED / DUPLICATE / REJECTED / REFUND_REQUIRED；只有 `PENDING_PAYMENT` 才允许提交库存和消耗优惠券，其他状态一律记录 `PAYMENT_REFUND_REQUIRED` 补偿事件且不改变库存/优惠券。
3. **支付幂等语义**：新增 `PaymentStatus.PENDING`。新回调先登记 PENDING，验证通过后在锁内转 CAPTURED；重复 CAPTURED 返回 DUPLICATE；支付 ID 复用但订单或金额不同返回 CONFLICT；金额不匹配直接 REJECTED 且不占用幂等键。
4. **退款累计约束**：重放同 ID 同金额直接返回 `duplicate=true` 且不累加；重放不同详情返回冲突；新增金额先检查 `amount <= paid - refunded`，成功后再写退款记录、更新订单与支付状态、发事件。
5. **预留状态防重入**：`Reservation.state` 改为枚举 `RESERVED/RELEASED/COMMITTED`，`release`/`commit` 只处理 RESERVED；新增 `wasCommitted` 便于审计但不依赖它做业务判断。
6. **测试补充**：新增 9 个测试覆盖金额不匹配重试、paymentId 冲突、第二笔支付补偿、超时后迟到支付、refundId 冲突、累计精确退款、优惠券互斥、支付/超时竞态、8 线程重复回调。

考虑过但未采用：引入框架/DB 事务（超出范围且无外部依赖）；把全部逻辑塞进一个 Manager（违反“避免万能管理器”要求）；仅靠 `synchronized` 方法而不统一锁对象（`CommerceStore.mutex` 已是共享锁，逐方法同步易遗漏读路径）。

## 6. 功能保护

- 保持 `CommerceSystem` 全部公开方法签名与 `CallbackResult`/`RefundResult`/`OrderView` 记录类型不变。
- 保留正常下单、支付、取消、超时、失败回滚与优惠券规则；7 个原有测试全部保留并通过。
- 退款不回滚库存，优惠券支付后 USED 不因退款恢复，迟到支付不重新预留库存。
- 确定性时间源不变，所有新并发测试使用 `CountDownLatch` 而非 `Thread.sleep`。

## 7. 测试与验证

- 基线：`4 passed, 3 failed`，失败项见第 4 节。
- 修复后公开测试：`7 passed, 0 failed`（2026-11-24T20:17:24+08:00）。
- 完整测试（含 9 个新增）：`16 passed, 0 failed`。
- 重复运行：编译一次后连续 5 次 `java -ea -cp out bench.commerce.CommerceTest`，无失败。
- `git diff --check`：通过，无空白错误。
- 编译：使用 `--release 17 -Xlint:all`，无警告输出。
- 未执行：无联网、无第三方依赖、无生产环境数据验证。

## 8. 代码质量审查

- 消除重复业务判断：支付状态合法性集中在 `OrderService.markPaid`，取消/过期统一走 `cancelPending`。
- 状态变化与副作用边界清晰：每个公开变更入口都在同一 `mutex` 内完成。
- 魔法字符串清理：`Reservation.state` 由字符串改为枚举；退款失败原因用简短字面量集中在 `rejected()`。
- 方法聚焦：`OrderService` 拆出 `cancelPending` 与 `requireRefund`；`RefundService` 拆出 `paymentFor` 与 `rejected`；测试拆出 `await`/`join`/`race` 辅助方法。
- 保留原有简洁模型，没有做大架构迁移、没有新增抽象层、没有格式化无关文件。
- 编译无警告；无调试输出、无空 catch、无异常吞噬；并发测试捕获并报告 worker 异常。

## 9. 剩余风险

未发现已知未解决问题。适用边界：实现仍是单进程内存服务；`store.mutex` 保证本进程内一致性，不提供跨进程持久化或分布式事务语义。

## 10. 最终文件清单与 Git 状态

修改文件：

- `src/main/java/bench/commerce/CommerceSystem.java`
- `src/main/java/bench/commerce/ExpirationJob.java`
- `src/main/java/bench/commerce/InventoryService.java`
- `src/main/java/bench/commerce/OrderService.java`
- `src/main/java/bench/commerce/PaymentService.java`
- `src/main/java/bench/commerce/PaymentStatus.java`
- `src/main/java/bench/commerce/RefundService.java`
- `src/main/java/bench/commerce/Reservation.java`
- `src/test/java/bench/commerce/CommerceTest.java`
- `AGENT_REPORT.md`（本报告）

清理：`out/` 由 `run-tests.sh` 每次重建，包含在 `.gitignore` 中，不作为源码修改；无临时文件残留。仓库配置 `core.filemode=false` 以消除 Windows/Git Bash 对 `run-tests.sh` 的假阳性 mode 变化，该文件内容未修改。
