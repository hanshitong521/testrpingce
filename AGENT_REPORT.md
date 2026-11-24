# Agent Report — Legacy Commerce Recovery (Cursor)

## 1. 执行时间与环境

- 收到完整任务提示词后、读取或修改项目文件前记录的时间：`2026-11-24T20:32:21+08:00`。
- 开始执行时间：按提示词要求，以该时间作为可核实开始时间。
- 基线测试完成时间：约 `2026-11-24T20:34:xx+08:00`，结果 `4 passed, 3 failed`，退出码 `1`。精确秒数未单独记录，不编造。
- 首次公开测试完整通过时间：`2026-11-24T20:40:50+08:00`，从开始起算 `8 分 29 秒`。
- 扩展测试全部通过后，连续重复运行 3 次 `15 passed, 0 failed`；最终复验也通过。
- 最终完成时间：`2026-11-24T20:45:05+08:00`（最后一次完整测试、`git diff --check` 和状态核对完成时刻）。
- 总耗时：`12 分 44 秒`，按 `20:32:21` 至 `20:45:05` 计算。
- 修改后测试失败、返工和重新验证次数：修改后共运行 8 次测试；首次公开修复后 7/7 通过，扩展测试首次 14/15（部分退款后的回调补偿断言不符），修订单后曾出现 2 次回归（重复回调误判、补偿事件缺失），修正后连续 3 次 15/15 通过，最终再次通过。
- 人工干预次数：`0`。
- Java：`openjdk 17.0.20.1`，`javac 17.0.20.1`。
- 操作系统：`Microsoft Windows NT 10.0.19045.0`。
- 实际主要命令：`powershell -ExecutionPolicy Bypass -File .\run-tests.ps1`、`git diff --check`、`git status --short`。

## 2. 问题理解

用户反馈的不是普通下单失败，而是订单、库存、支付回调、取消/超时和退款交错时的状态不一致：已取消/过期订单被迟到支付“复活”、重复回调重复提交库存、重复退款累计超过支付金额，以及退款后回调重复处理造成财务事件不一致。

## 3. 项目与依赖关系

- `OrderService` 管订单状态与创建、取消、支付提交。
- `InventoryService` 管 `available/reserved/sold` 和每个订单的 `Reservation`。
- `CouponService` 管优惠券 `AVAILABLE/RESERVED/USED`。
- `PaymentService` 处理网关回调和 paymentId 幂等。
- `RefundService` 处理 refundId 幂等、退款累计和 PaymentRecord 状态。
- `ExpirationJob` 扫描到期订单并调用取消。
- `CommerceStore` 的 `mutex` 现在作为所有这些写操作的共同提交边界。

## 4. 根因和故障链

1. **支付幂等记录早于业务提交**：旧 `PaymentService.success` 先写 `PaymentRecord`，再调用 `markPaid`；金额不符或业务失败时仍可能占用 paymentId，导致同 ID 后续合法重试被阻塞。
2. **迟到支付未进入补偿路径**：旧 `markPaid` 只拒绝已支付/已退款订单，取消/过期订单因仍是 `PENDING_PAYMENT` 之外的状态被简单拒绝，未按 README 规定返回 `REFUND_REQUIRED` 并记录 `PAYMENT_REFUND_REQUIRED`。
3. **重复回调重复应用业务副作用**：paymentId 重复时仍会重新走业务迁移；库存与优惠券没有状态保护时可能再次 commit，或成功事件重复。
4. **库存预留/释放/提交无终态保护**：`Reservation.state` 是字符串，release/commit 不检查当前状态；重复取消、重复超时或交错调用可能重复移动 `reserved/available/sold`。
5. **退款幂等键先写后校验、且只检查单次金额**：旧 `RefundService` 先记录 refundId 再检查 `amount > order.paid`，重复请求会把 `refunded` 累加两次；新的不同 refundId 如果 `amount <= order.paid` 也可能突破累计退款上限。
6. **取消与支付、超时扫描与支付没有共同原子边界**：各模块在 `store.mutex` 外检查状态后分别改订单、库存、优惠券和事件，竞争时可能留下“已支付但库存已释放”或“已取消但库存已提交”。

基线失败证据：`latePaymentMustNotResurrectCancelledOrder`、`duplicatePaymentCallbackMustBeIdempotent`、`refundReplayAndCumulativeLimit` 三个公开测试失败，分别显示迟到支付返回 `ACCEPTED`、重复回调返回 `REFUND_REQUIRED` 而非 `DUPLICATE`、退款总额从 `600` 变成 `1200`。

## 5. 修改方案

- 将下单、取消、支付提交、退款、库存读取、优惠券读取、过期扫描统一包在 `store.mutex` 中，使状态检查与副作用处于同一提交边界。
- 新增 `EventTypes` 集中审计事件名；新增 `ReservationState` 用枚举表达的 `RESERVED/COMMITTED/RELEASED` 生命周期，防止重复释放/提交。
- `Order` 增加 `capturingPaymentId`，`PaymentRecord` 增加 `sameRequest/capturesOrder/refundable/applyRefund`，`RefundRecord` 增加 `sameRequest`，把分散判断收拢到数据对象。
- `PaymentService` 改为：先查幂等记录；金额不符不写幂等记录；待支付订单一次提交支付与库存/优惠券；非待支付但金额匹配时记录 `REFUND_REQUIRED` 与审计事件；已退款订单重复回调也进入补偿路径。
- `RefundService` 改为：先校验幂等、订单可退款状态与剩余可退金额，全部通过后才写退款记录、更新订单和 PaymentRecord、发事件；重复 refundId 返回 `duplicate=true` 且不再累加。
- `ExpirationJob` 在锁内读取注入的 `TimeSource` 并判断 `now >= expiresAt`，随后调用同样受锁保护的取消。
- 考虑过但没采用：大范围领域事件溯源/消息队列、引入框架或第三方依赖、把全部业务塞进一个“万能管理器”。这些对当前单进程无依赖基准项目收益低且风险高。

## 6. 功能保护

- 保留 `CommerceSystem` 全部公开方法和参数/返回类型不变。
- 保留多 SKU 下单、优惠券预留/使用、付款提交库存、手动取消、到期取消、部分/全额退款等正常路径。
- 退款不回补库存、不释放已使用优惠券；迟到支付不重新占用库存。
- 原有 7 个公开测试全部保留且通过，未删除或放宽断言。

## 7. 测试与验证

- 基线：7 个公开测试，4 通过 3 失败。
- 修复后公开测试：7/7 通过。
- 新增 8 个测试，总计 15 个：覆盖金额不符后重试、paymentId 跨订单冲突、过期后迟到支付、部分退款后回调补偿与事件幂等、全额退款不回补库存/不释放优惠券、refundId 冲突无副作用、重复过期只释放一次、支付/重复回调/超时/取消的并发竞争。
- 并发测试使用 `CountDownLatch` 和固定线程池，不使用 `Thread.sleep`。
- 最终连续 4 次运行均为 `15 passed, 0 failed`（其中一次为最终复验）。
- `git diff --check` 无输出、退出码 `0`；`javac --release 17 -Xlint:all` 无编译警告。
- 未执行：外部隐藏测试未运行；未引入联网或第三方依赖。

## 8. 代码质量审查

- 把事件名从魔法字符串替换为 `EventTypes` 常量。
- 把 `Reservation.state` 从字符串替换为枚举，并让库存副作用只在 `RESERVED` 状态执行一次。
- 把订单状态判断、退款剩余额、支付记录幂等/退款规则下沉到对应领域对象，减少调用方重复判断。
- `PaymentService` 和 `RefundService` 拆出私有方法后主流程更短，冲突/重复/补偿路径更容易审查。
- 删除了未使用的 `PaymentApplication` 枚举，避免死代码。
- 没做大规模格式化、架构迁移、框架引入或无关文件重写。

## 9. 剩余风险

- 这是单进程内存实现，锁边界只在当前 JVM 内有效；多实例部署需要数据库事务或分布式锁。
- 外部网关实际退款动作不在本项目内；`REFUND_REQUIRED` 只记录补偿要求与审计事件。
- 退款仍不处理实物退货/回补库存，这是 README 明确要求的边界。
- 未发现其他未解决的一致性问题。

## 10. 最终文件清单与 Git 状态

- 修改：`CommerceSystem.java`、`ExpirationJob.java`、`InventoryService.java`、`Order.java`、`OrderService.java`、`PaymentRecord.java`、`PaymentService.java`、`RefundRecord.java`、`RefundService.java`、`Reservation.java`、`CommerceTest.java`。
- 新增：`EventTypes.java`、`ReservationState.java`、`AGENT_REPORT.md`。
- 时间标记文件：`.agent_task_start`、`.first_green`、`.final_time` 为本地时间证据，已随本次提交纳入版本控制。
- 构建产物：`out/` 由测试脚本生成，已被 `.gitignore` 忽略；未删除该本地目录，未提交任何 `.class`。
- 临时文件：无额外临时源码；未跟踪文件仅上述时间标记与报告/新增源码。
