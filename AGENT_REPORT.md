# Agent Benchmark Round 2 — Legacy Commerce Recovery 执行报告

## 1. 执行时间与环境

- 收到完整任务提示词后、读取或修改任何项目文件前记录的时间：`2026-11-24T20:15:23+08:00`。首次可见 `Agent_Package` 的目录修改时间为 `2026-11-24T17:33:04+08:00`，无法从现有证据确认提示词更早的送达时刻，因此不把它当作开始时间。
- 开始执行时间：按提示词要求，以 `2026-11-24T20:15:23+08:00` 作为可核实开始时间。
- 基线测试完成时间：`2026-11-24T20:16:04+08:00` 左右，结果 `4 passed, 3 failed`，退出码 `1`。
- 首次公开测试完整通过时间：`2026-11-24T20:18:57+08:00`，从开始起算 `3 分 34 秒`。
- 加入扩展测试后完整通过时间：`2026-11-24T20:19:46+08:00`。
- 最终完成时间：`2026-11-24T20:20:41+08:00`（最后一次完整测试、`git diff --check` 和状态核对完成时刻）。
- 总耗时：`5 分 18 秒`，按 `20:15:23` 至 `20:20:41` 计算。
- 修改后测试失败、返工和重新验证次数：修改后共运行 6 次公开/完整测试；其中 3 次失败（迟到支付被误拒 2 次、新增测试断言写错 2 项集中在 1 次运行），3 次通过；最终连续重复运行 2 次均通过。
- 人工干预次数：`0`。
- Java：`openjdk 17.0.20 / Temurin-17.0.20+8`，`javac 17.0.20`。
- 操作系统：`Microsoft Windows NT 10.0.19045.0`。
- 实际使用命令：
  - `powershell -ExecutionPolicy Bypass -File .\run-tests.ps1`
  - `git diff --check`
  - `git status --short`
  - `git switch -c codex/round-2-legacy-commerce`
  - `git add ...`、`git commit ...`、`git push -u origin codex/round-2-legacy-commerce`

## 2. 问题理解

用户反馈不是单点偶发错误，而是支付回调、用户取消、超时取消交错时，订单状态与库存/优惠券/支付/退款/事件没有在同一提交边界内一致变化。重复回调会产生第二次库存确认或错误结果，迟到支付会复活已取消订单，重复退款会重复记账。

## 3. 项目与依赖关系

- `OrderService` 负责订单状态、库存预留提交/释放和优惠券占用/释放。
- `PaymentService` 负责支付幂等记录和回调结果；`ExpirationJob` 负责到期取消。
- `RefundService` 依赖订单与支付记录；退款不会回补库存，符合 README 契约。
- `CommerceStore.mutex` 现在是下单、取消、过期、支付、退款共同的同步边界；`TimeSource` 保持注入式确定性时间。

## 4. 根因和故障链

1. `PaymentService.success` 先把 `paymentId` 标记为已捕获，再调用 `orders.markPaid`；同一回调重放时库存/事件已提交但返回 `REFUND_REQUIRED`。修复：同一互斥区内先判定幂等记录，再一次性完成状态、库存、优惠券、支付记录和事件。
2. `OrderService.markPaid` 允许已取消订单进入支付流程，迟到回调可释放库存后再次确认。修复：支付前检查订单是否仍可承接支付；已支付/退款/取消订单返回补偿要求或拒绝，并记录 `PAYMENT_REFUND_REQUIRED`。
3. `PaymentService` 对金额不符返回 `REJECTED`，但此前 `OrderService` 的失败判定与支付记录写入顺序不一致。修复：金额不符不写任何支付幂等记录，允许同 `paymentId` 后续用正确金额重试。
4. `RefundService.refund` 在校验累计上限前写入 `RefundRecord`；重复 `refundId` 的注释性跳过又仍执行退款副作用。修复：先完成幂等/冲突/额度校验，再统一提交订单、支付记录、退款记录和事件。
5. `InventoryService.release/commit` 不检查 `Reservation.state`，重复过期、取消或回放可能重复移动库存。修复：使用 `ReservationState` 枚举，只有 `RESERVED` 可迁移一次。
6. 下单、取消、过期、支付、退款原本没有共享提交边界。修复：公开写操作统一使用 `store.mutex`，并发测试验证 8 个同时支付只有一个 `ACCEPTED`、库存只确认一次。

基线证据：`latePaymentMustNotResurrectCancelledOrder`、`duplicatePaymentCallbackMustBeIdempotent`、`refundReplayAndCumulativeLimit` 失败，其余公开测试通过。

## 5. 修改方案

- 新增 `PaymentApplication`、`ReservationState`、`EventTypes`，减少裸字符串和分散的状态判断。
- `Order` 集中 `pendingPayment()`、`paidOrRefunded()`、`remainingRefundable()`。
- `PaymentService` 与 `RefundService` 拆出锁内私有方法，防止校验和提交跨临界区。
- `OrderService.applyPayment` 集中支付状态迁移；取消和过期继续复用同一 `cancel` 路径。
- 考虑过把支付、退款、订单全部合并成一个“万能管理器”，但会扩大公开入口改动和测试面，未采用；本方案只增加最小协作类型和锁边界。

## 6. 功能保护

- 保留 `CommerceSystem` 公开方法和 `OrderView`、`CallbackResult`、`RefundResult` 等公开类型不变。
- 保留多 SKU 预留/提交/释放、优惠券占用/释放、超时点边界、部分退款及累计上限。
- 已支付订单不进入普通取消路径；已取消订单不会被迟到支付复活；退款不回补库存。
- 正常下单支付和重复回调通过 7 个原始公开测试与 11 个新增测试验证。

## 7. 测试与验证

- 基线：`4 passed, 3 failed`，退出码 `1`。
- 修复后首次完整公开测试：`7 passed, 0 failed`，退出码 `0`。
- 扩展后完整测试：`18 passed, 0 failed`，退出码 `0`；重复一次仍为 `18 passed, 0 failed`。
- 新增测试覆盖：金额不符可重试、`paymentId` 冲突、第二笔支付补偿、迟到支付事件、超时边界、重复取消/过期、优惠券竞争、退款 ID 冲突、全额退款不退货、非正金额退款、并发支付幂等。
- `git diff --check` 通过，退出码 `0`；Git 仅提示工作区 LF 在后续检出时转 CRLF，不属于空白错误。
- 编译使用脚本的 `javac --release 17 -Xlint:all`，未出现警告。
- 未执行：外部隐藏测试未提供；未使用网络或第三方依赖。

## 8. 代码质量审查

- 去重：状态判断集中到 `Order`，事件名集中到 `EventTypes`，库存状态改为枚举。
- 职责：支付/退款各自负责幂等记录与业务提交的完整边界；订单服务负责状态迁移和库存/优惠券副作用。
- 命名：用 `PaymentApplication`、`ReservationState` 替代含糊布尔和魔法字符串。
- 方法复杂度：长方法拆为 `applyUnderLock`、`findRefundablePayment` 等 focused helper。
- 未做不必要重构：未迁移架构、未引入框架/依赖、未大规模格式化无关文件。

## 9. 剩余风险

未发现已知未修复问题。适用范围限于单进程内存服务；并发保证依赖共享 `CommerceStore.mutex`，若未来拆分为多进程或持久化事务，需要重新设计跨进程幂等与提交边界。

## 10. 最终文件清单与 Git 状态

- 修改：`CommerceSystem.java`、`ExpirationJob.java`、`InventoryService.java`、`Order.java`、`OrderService.java`、`PaymentRecord.java`、`PaymentService.java`、`RefundRecord.java`、`RefundService.java`、`Reservation.java`、`CommerceTest.java`。
- 新增：`EventTypes.java`、`PaymentApplication.java`、`ReservationState.java`、`AGENT_REPORT.md`。
- 构建产物：`out/` 由测试脚本生成并已被 `.gitignore` 忽略；没有临时文件进入提交。
- Git 分支：`codex/round-2-legacy-commerce`；提交和推送状态以最终 Git 输出为准。
