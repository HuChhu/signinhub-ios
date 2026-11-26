package com.signhub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 签到闸门：同一账号的签到同一时刻只允许一个在跑，刚签成功的短时间内直接复用结果。
 *
 * 为什么需要它：
 *  - 手机端有四条入口会真的打上游接口 —— 首页「一键全部签到」、设置页「测试签到」、
 *    多账号管理页的单签/全签、后台 WorkManager 的 AutoCheckinWorker。
 *    前三条由 WebUiActivity 的 apiExecutor 串行驱动，但 WorkManager 跑在另一条线程上，
 *    定时任务与手动点击撞在一起时，**同一个账号会被打两遍上游**。
 *    上游会把重复请求当异常流量，轻则报错、重则触发风控封禁。
 *  - 重复打还会让 HistoryStore 的同一行 (provider, day, account_key) 被并发写。
 *
 * 粒度是「provider|accountKey」而不是 provider：
 * 同一家的两个不同账号并行是安全的（互不相干），只有同账号重复才是问题。
 * 这样多账号并行签到不会被这把锁拖慢。
 *
 * ★ 为什么是 Semaphore 而不是 ReentrantLock / synchronized（V2.45 修复）：
 *   `ReentrantLock` 的锁是**线程私有**的 —— 谁 lock 就必须谁 unlock。
 *   而 [runSuspend] 必须在持锁状态下 await 网络请求，`LocalCheckin.trae()` /
 *   `minimax()` / `HwCheckin.run()` 内部都有 `withContext(Dispatchers.IO)`，
 *   协程恢复执行时**已经换到另一条线程**，于是 `finally { lk.unlock() }` 由
 *   非持有线程执行 → 抛 `IllegalMonitorStateException`，而且这把锁再也不会被释放。
 *
 *   实机表现（用户 m24705 的推送通知）：
 *     `Trae Solo：签到异常：IllegalMonitorStateException`
 *     `MiniMax Code：签到异常：IllegalMonitorStateException`
 *   紧接着主页点签到卡片一直转圈（等一把永远不放的锁），清后台重开才恢复正常
 *   —— 因为进程重启把锁表一起清掉了。
 *
 *   **为什么只有 Trae / MiniMax 中招**：它们的 `LocalCheckin.trae()` /
 *   `minimax()` 是 suspend 函数、内部有 `withContext(Dispatchers.IO)`；
 *   而 WorkBuddy 走的 `WbAuth.runCheckin()` 内部没有切线程（同步 OkHttp），
 *   所以 unlock 恰好还在原线程上 —— 这也是为什么同一批里 WorkBuddy 与 Qoder
 *   正常、只有那两家报错。
 *
 *   `Semaphore` 不绑定线程，acquire/release 可以跨线程，天然适配协程挂起/恢复。
 *
 * ★ 为什么还要 tryAcquire 超时：
 *   即使锁本身不再泄漏，任何一次上游 hang 住也会让同账号的后续请求无限等待。
 *   超时后返回一条可行动的失败结果（而不是永远转圈），用户能直接看到「卡住了」。
 */
object CheckinGate {

    /** 刚成功的结果在这么久内复用，避免「手动点完立刻又被定时任务打一次」 */
    private const val RECENT_TTL_MS = 8_000L

    /**
     * 失败结果的短期缓存窗口。
     *
     * P2-6（BUGS-v1.63）：原来「失败不缓存」，于是 A 线程持锁跑 3 秒后失败、
     * B 线程立刻拿到锁、cached() 未命中 → **再打一次上游**。定时任务与手动点击
     * 同时触发且第一次失败时，同一账号会被连打两次 —— 正是注释里说的
     * 「重复请求触发风控」那种场景。
     *
     * 现在失败也记 3 秒：3 秒内的第二次调用直接拿到同一个失败结果，
     * 不会再打上游；3 秒后允许重试（用户手点重试通常远慢于 3 秒，不影响体验）。
     */
    private const val FAIL_TTL_MS = 3_000L

    /**
     * 等闸门上限。超过就判定「同一账号上一次签到还挂着」，返回失败而不是无限等待。
     *
     * 取 55 秒：正常一次签到 1~5 秒，Trae 换 token + 设备号重试最坏也就 20 秒左右；
     * 55 秒还没拿到闸门只可能是上游 hang 住或线程被占死。
     * **必须小于 WebUiActivity shim 的 75000ms 超时** —— 否则前端先超时，
     * 用户看到的是无信息的「请求超时」，而不是下面这句能指导下一步的文案。
     */
    private const val LOCK_WAIT_MS = 55_000L

    /**
     * 普通线程路径（[run]）的等闸门上限，比 [LOCK_WAIT_MS] 短得多。
     *
     * [run] 全部由 WebUiActivity 的 **apiExecutor 单线程**驱动 —— 在这里阻塞多久，
     * 整个 App 的本地接口就有多久完全不响应（首页刷新、状态轮询全部排队）。
     * 实测过一次 55 秒阻塞的表现就是「连不上电脑端」，比签到失败本身更糟。
     *
     * 12 秒足够覆盖「上一次正常签到（1~5 秒）刚好收尾」的情况；
     * 真的还在跑（上游 hang 住）就立刻返回可行动的失败，让用户自己决定要不要等。
     */
    private const val LOCK_WAIT_SYNC_MS = 12_000L

    /** gates / recent 的条目上限，防止账号反复增删后 map 无界增长（P3-1、P3-2）。 */
    private const val MAX_ENTRIES = 64

    private val gates = HashMap<String, Semaphore>()

    /**
     * 复用表：key -> (时间戳, 日期, 结果)。
     *
     * 失败结果也进表（status 不是 success/already 时只活 FAIL_TTL_MS），
     * 成功结果活 RECENT_TTL_MS。日期对不上直接作废（跨零点保护）。
     */
    private val recent = HashMap<String, Triple<Long, String, CheckinResult>>()

    private fun gateOf(key: String): Semaphore = synchronized(gates) {
        if (gates.size >= MAX_ENTRIES && !gates.containsKey(key)) {
            // 简单清理：只删「当前没人持有」的闸门（availablePermits 满 = 空闲）。
            // 持有中的绝不能删，否则同一账号会拿到两个闸门 = 等于没有互斥。
            gates.entries.removeAll { it.value.availablePermits() >= 1 }
        }
        gates.getOrPut(key) { Semaphore(1, true) }
    }

    fun gateKey(provider: String, accountKey: String): String =
        provider + "|" + accountKey.ifBlank { HistoryStore.DEFAULT_ACCOUNT }

    private fun isOk(status: String): Boolean = status == "success" || status == "already"

    private fun cached(key: String): CheckinResult? {
        val hit = synchronized(recent) { recent[key] } ?: return null
        // 跨日必须失效：23:59:55 签成功、00:00:02 再点，8 秒内会命中旧结果，
        // 于是新的一天被误判成「已复用」→ 当天其实没签。日期对不上就直接丢掉。
        if (hit.second != LocalApi.todayStatic()) return null
        val age = System.currentTimeMillis() - hit.first
        val ttl = if (isOk(hit.third.status)) RECENT_TTL_MS else FAIL_TTL_MS
        if (age > ttl) return null
        val r = hit.third
        val tail = if (isOk(r.status)) "（同一账号刚刚签过，已复用结果）" else "（同一账号刚刚失败过，3 秒内不重复请求）"
        return r.copy(message = r.message + tail)
    }

    private fun remember(key: String, res: CheckinResult) {
        synchronized(recent) {
            if (recent.size >= MAX_ENTRIES && !recent.containsKey(key)) {
                val now = System.currentTimeMillis()
                recent.entries.removeAll { now - it.value.first > RECENT_TTL_MS }
                if (recent.size >= MAX_ENTRIES) recent.clear()
            }
            // 成功与失败都记：失败只活 FAIL_TTL_MS（见 cached()），
            // 这样「A 失败后 B 立刻重打上游」不会发生，同时用户手点重试仍能生效。
            recent[key] = Triple(System.currentTimeMillis(), LocalApi.todayStatic(), res)
        }
    }

    /** 等不到闸门时给用户看的话。带上超时秒数，避免「又转圈了」这种无信息状态。 */
    private fun busyResult(provider: String, waitMs: Long = LOCK_WAIT_MS): CheckinResult = CheckinResult(
        provider, "fail",
        "同一账号的上一次签到还在进行（等了 ${waitMs / 1000} 秒仍未结束）。" +
            "请稍等几秒再点，或到「记录」页看上一次的结果。",
        ""
    )

    /**
     * 在闸门内执行一次签到（普通线程版）。
     *
     * @param reuse 是否复用刚刚成功的结果。**「测试签到」必须传 false** ——
     *              测试的目的是验证凭据此刻是否有效，复用旧结果会骗人。
     *              同时传 false 也不会把结果写进复用表，否则紧接着的真实签到会被
     *              去重掉，变成漏签。
     */
    fun run(provider: String, accountKey: String, reuse: Boolean = true,
            block: () -> CheckinResult): CheckinResult {
        val key = gateKey(provider, accountKey)
        val gate = gateOf(key)
        // ★ 必须分片轮询，不能一次 tryAcquire(LOCK_WAIT_SYNC_MS)：
        //   这条路径由 WebUiActivity 的 apiExecutor（**单线程**）驱动，
        //   一次阻塞多少秒就等于整个 App 的 /api/* 停多少秒。
        //   分片等待时每 200ms 让出一次，虽然仍占着这条线程，
        //   但至少不会在「闸门其实早就释放了」的情况下继续空等。
        val deadline = System.currentTimeMillis() + LOCK_WAIT_SYNC_MS
        var got = false
        while (System.currentTimeMillis() < deadline) {
            try {
                if (gate.tryAcquire(200, TimeUnit.MILLISECONDS)) { got = true; break }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!got) return busyResult(provider, LOCK_WAIT_SYNC_MS)
        try {
            if (reuse) cached(key)?.let { return it }
            val res = block()
            if (reuse) remember(key, res)
            return res
        } finally {
            gate.release()
        }
    }

    /**
     * 协程版：给 suspend 调用点（AutoCheckinWorker.doWork）用。
     *
     * 与 [run] 共用同一个闸门，所以后台定时任务与前台点击也会互相排斥。
     * 持闸时长就是一次网络请求（1~5 秒），只影响「同一账号的重复签到」，
     * 不会拖住其它账号（闸门按 provider|accountKey 分）。
     *
     * ★ 必须用 [Semaphore] 而不是 ReentrantLock：block 内部会 `withContext(IO)`
     *   换线程，ReentrantLock 的 unlock 只能由持有线程调用，跨线程会抛
     *   IllegalMonitorStateException 并让锁永久泄漏（V2.45 修的就是这个）。
     */
    suspend fun runSuspend(provider: String, accountKey: String, reuse: Boolean = true,
                           block: suspend () -> CheckinResult): CheckinResult {
        val key = gateKey(provider, accountKey)
        val gate = gateOf(key)
        // 分片轮询而不是一次阻塞 55 秒：一次阻塞会占住一条线程，而 NET_POOL 只有 8 条，
        // 几个账号同时等闸门就可能把池子占满。200ms 一次的 tryAcquire 对闸门释放
        // 已经足够及时；整个等待过程都在 IO 线程上，不阻塞调用方（可能是 Main）。
        val got = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (gate.tryAcquire(200, TimeUnit.MILLISECONDS)) { ok = true; break }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            ok
        }
        if (!got) return busyResult(provider)
        try {
            if (reuse) cached(key)?.let { return it }
            val res = block()
            if (reuse) remember(key, res)
            return res
        } finally {
            // release 不校验线程归属，这里即使已经换过线程也安全
            gate.release()
        }
    }
}
