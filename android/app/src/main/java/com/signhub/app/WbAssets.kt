package com.signhub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * WorkBuddy 积分资产：余额明细 + 「将过期积分」预警。
 *
 * 接口（与官方「积分明细」页对齐，域名同签到，路径带 /v2）：
 *   余额     POST {base}/v2/billing/meter/get-user-resource
 *            响应 data.Response.Data.Accounts[]：
 *              CapacityRemainPrecise / CycleCapacitySizePrecise /
 *              CycleCapacityRemainPrecise / CapacityUsedPrecise /
 *              CapacitySizePrecise / PackageName / PackageCode
 *   赠送包   POST {base}/v2/billing/meter/get-user-resource-free-packages
 *            body {"PackageCodes":[..],"PageNumber":n,"PageSize":200}
 *            每项另有 CycleEndTime（"%Y-%m-%d %H:%M:%S"，UTC+8）
 *
 * 口径与网页一致：可用 = min(总量剩余, 本期剩余)（本期字段缺失时退回总量）。
 * 过期统计只算「剩余 > 0 且未到期」的赠送包；查询失败不写 0，卡片直接不显示该行。
 */
object WbAssets {

    // N3（BUGS-v2.1）：显式 callTimeout，见 WbMulti.kt 同处注释。
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val PREF = "signhub_assets"
    private val TZ = ZoneId.of("Asia/Shanghai")

    private fun base(acc: WbAccount): String {
        if (WbApi.isAi(acc.variant)) return WbApi.EP_AI
        val d = acc.domain.lowercase()
        if (d == "workbuddy.cn" || d == "www.workbuddy.cn") return "https://www.workbuddy.cn"
        return WbApi.EP_CN
    }

    private fun call(url: String, acc: WbAccount, body: String): Pair<Int, String> = try {
        val b = Request.Builder().url(url)
        b.addHeader("User-Agent", WbApi.UA)
        b.addHeader("Content-Type", "application/json")
        b.addHeader("Accept", "application/json, text/plain, */*")
        b.addHeader("Authorization", "Bearer " + acc.accessToken)
        if (acc.uid.isNotBlank()) b.addHeader("X-User-Id", acc.uid)
        if (acc.enterpriseId.isNotBlank()) {
            b.addHeader("X-Enterprise-Id", acc.enterpriseId)
            b.addHeader("X-Tenant-Id", acc.enterpriseId)
        }
        if (acc.domain.isNotBlank()) b.addHeader("X-Domain", acc.domain)
        b.post(body.toRequestBody(JSON))
        client.newCall(b.build()).execute().use { r -> Pair(r.code, r.body?.string() ?: "") }
    } catch (e: Exception) {
        Pair(-1, """{"err":${JSONObject.quote(e.message ?: "network")}}""")
    }

    private fun obj(t: String): JSONObject = try {
        JSONObject(t)
    } catch (e: Exception) {
        JSONObject().put("code", -1).put("msg", t.take(300))
    }

    /** 余额 + 包明细。失败抛异常，由调用方决定怎么退化。 */
    suspend fun balance(acc: WbAccount): JSONObject = withContext(Dispatchers.IO) {
        val (code, text) = call(base(acc) + "/v2/billing/meter/get-user-resource", acc, "{}")
        if (code != 200) throw RuntimeException("查询余额 HTTP $code")
        val o = obj(text)
        if (o.optInt("code", 0) != 0 && o.has("code")) {
            val c = o.optInt("code", -1)
            if (c != 0) throw RuntimeException("查询余额失败：${o.optString("msg", "code=$c")}")
        }
        val accounts = o.optJSONObject("data")
            ?.optJSONObject("Response")
            ?.optJSONObject("Data")
            ?.optJSONArray("Accounts") ?: JSONArray()

        var total = 0.0
        var usedSum = 0.0
        var capSum = 0.0
        val codes = mutableListOf<String>()
        val pkgs = JSONArray()
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val rem = a.optDouble("CapacityRemainPrecise", 0.0)
            val cycSize = a.optDouble("CycleCapacitySizePrecise", 0.0)
            val cycRaw = a.opt("CycleCapacityRemainPrecise")
            val cyc: Double? = when (cycRaw) {
                is Number -> cycRaw.toDouble()
                is String -> cycRaw.toDoubleOrNull()
                else -> null
            }
            val avail = if (cyc != null && cycSize > 0) minOf(rem, cyc) else rem
            total += avail
            usedSum += a.optDouble("CapacityUsedPrecise", 0.0)
            capSum += a.optDouble("CapacitySizePrecise", 0.0)
            val pc = a.optString("PackageCode", "")
            if (pc.isNotBlank()) codes.add(pc)
            pkgs.put(JSONObject().apply {
                put("name", a.optString("PackageName", ""))
                put("code", pc)
                put("remain", round2(avail))
                put("cycle_remain", cyc?.let { round2(it) } ?: JSONObject.NULL)
                put("total_remain", round2(rem))
                put("used", round2(a.optDouble("CapacityUsedPrecise", 0.0)))
                put("capacity", round2(a.optDouble("CapacitySizePrecise", 0.0)))
            })
        }
        JSONObject().apply {
            put("remaining", round2(total))
            put("used_total", round2(usedSum))
            put("capacity_total", round2(capSum))
            put("packages", pkgs)
            put("codes", JSONArray(codes))
        }
    }

    /**
     * 按到期日汇总赠送包剩余积分。
     * 返回 {"next":{"amount","date"},"d7","d30","buckets":[...]}；无数据返回 null。
     */
    suspend fun expiring(acc: WbAccount, codes: List<String>): JSONObject? = withContext(Dispatchers.IO) {
        val list = codes.filter { it.isNotBlank() }.distinct().sorted()
        if (list.isEmpty()) return@withContext null
        val accounts = JSONArray()
        var page = 1
        // 实测：get-user-resource 走 /v2/billing/meter，但赠送包接口只认不带 /v2 的
        // /billing/meter（带 /v2 会 404 Route Not Found）。先用不带 /v2 的，失败再退回带 /v2。
        val urls = listOf(
            base(acc) + "/billing/meter/get-user-resource-free-packages",
            base(acc) + "/v2/billing/meter/get-user-resource-free-packages"
        )
        var good: String? = null
        while (page <= 10) {
            val body = JSONObject().apply {
                put("PackageCodes", JSONArray(list))
                put("PageNumber", page)
                put("PageSize", 200)
            }.toString()
            var resp: Pair<Int, String>? = null
            for (u in (good?.let { listOf(it) } ?: urls)) {
                val r = call(u, acc, body)
                if (r.first == 200) { good = u; resp = r; break }
                resp = r
            }
            val code = resp?.first ?: -1
            val text = resp?.second ?: ""
            if (code != 200) throw RuntimeException("查询赠送包 HTTP $code")
            val o = obj(text)
            if (o.has("code") && o.optInt("code", -1) != 0) {
                throw RuntimeException("查询赠送包失败：${o.optString("msg", "未知错误")}")
            }
            val data = o.optJSONObject("data") ?: JSONObject()
            val rows = data.optJSONArray("Accounts") ?: JSONArray()
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { accounts.put(it) }
            val total = data.optInt("TotalCount", 0)
            if (rows.length() == 0 || (total > 0 && accounts.length() >= total)) break
            page++
        }

        val now = LocalDateTime.now(TZ)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val buckets = mutableMapOf<java.time.LocalDate, Double>()
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val remRaw = a.opt("CycleCapacityRemainPrecise")
                ?: a.opt("CapacityRemainPrecise")
            val rem = when (remRaw) {
                is Number -> remRaw.toDouble()
                is String -> remRaw.toDoubleOrNull() ?: continue
                else -> continue
            }
            if (rem <= 0) continue
            val ts = a.optString("CycleEndTime", "").trim()
            if (ts.isBlank()) continue
            val end = try { LocalDateTime.parse(ts, fmt) } catch (e: Exception) { continue }
            if (end.isBefore(now)) continue
            val d = end.toLocalDate()
            buckets[d] = (buckets[d] ?: 0.0) + rem
        }
        if (buckets.isEmpty()) return@withContext null

        val today = now.toLocalDate()
        val first = buckets.keys.min()
        fun within(maxDays: Long): Double =
            buckets.entries.filter { java.time.temporal.ChronoUnit.DAYS.between(today, it.key) <= maxDays }
                .sumOf { it.value }

        val arr = JSONArray()
        buckets.entries.sortedBy { it.key }.forEach { (d, v) ->
            arr.put(JSONObject().put("date", d.toString()).put("amount", round2(v)))
        }
        JSONObject().apply {
            put("next", JSONObject().apply {
                put("amount", round2(buckets[first] ?: 0.0))
                put("date", first.format(DateTimeFormatter.ofPattern("MM-dd")))
            })
            put("d7", round2(within(7)))
            put("d30", round2(within(30)))
            put("buckets", arr)
        }
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    // ── 本地缓存（供 /api/status 直接读，避免每次刷新都打接口） ─────────
    fun saveCache(ctx: Context, key: String, j: JSONObject, ttlMs: Long = 300_000L) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("json_$key", j.toString())
            .putLong("ts_$key", System.currentTimeMillis())
            .putLong("ttl_$key", ttlMs)
            .apply()
    }

    fun loadCache(ctx: Context, key: String): JSONObject? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = p.getString("json_$key", null) ?: return null
        val ts = p.getLong("ts_$key", 0)
        val ttl = p.getLong("ttl_$key", 300_000L)
        if (System.currentTimeMillis() - ts > ttl) return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    /**
     * 官方汇总口径（get-user-resource-summary）：TotalCredits 是官方承认的「当前可用」，
     * CreditGroups 按 plan_base/plan_bonus/addon/benefit 分组。
     * 比逐包累加更权威 —— 逐包会把已用尽的订阅档也算进来。
     *
     * 地址坑（实测）：这个接口只认不带 /v2 的 /billing/meter/...，带 /v2 会 404。
     * 失败抛异常，由 card() 兜住。
     */
    suspend fun summary(acc: WbAccount): JSONObject = withContext(Dispatchers.IO) {
        var code = 0
        var text = ""
        for (u in listOf(
            base(acc) + "/billing/meter/get-user-resource-summary",
            base(acc) + "/v2/billing/meter/get-user-resource-summary",
        )) {
            val r = call(u, acc, "{}")
            code = r.first
            text = r.second
            if (code == 200) break
        }
        if (code != 200) throw RuntimeException("查询积分汇总 HTTP $code")
        val o = obj(text)
        if (o.has("code") && o.optInt("code", 0) != 0) {
            throw RuntimeException("查询积分汇总失败：${o.optString("msg", "code=${o.optInt("code", -1)}")}")
        }
        val d = o.optJSONObject("data") ?: JSONObject()
        val groups = JSONArray()
        d.optJSONArray("CreditGroups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                groups.put(JSONObject().apply {
                    put("kind", g.optString("Kind", ""))
                    put("name", g.optString("Name", ""))
                    put("remain", round2(g.optDouble("CycleRemainCapacity", 0.0)))
                    put("used", round2(g.optDouble("CycleUsedCapacity", 0.0)))
                    put("capacity", round2(g.optDouble("CycleTotalCapacity", 0.0)))
                    put("count", g.optInt("TotalCount", 0))
                })
            }
        }
        val pkgs = JSONArray()
        d.optJSONArray("Packages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                pkgs.put(JSONObject().apply {
                    put("code", p.optString("PackageCode", ""))
                    put("remain", round2(p.optDouble("CycleRemainCapacity", 0.0)))
                    put("used", round2(p.optDouble("CycleUsedCapacity", 0.0)))
                    put("capacity", round2(p.optDouble("CycleTotalCapacity", 0.0)))
                    put("count", p.optInt("TotalCount", 0))
                })
            }
        }
        JSONObject().apply {
            put("total_credits", round2(d.optDouble("TotalCredits", 0.0)))
            put("plan_name", d.optString("SubscriptionPackageName", ""))
            put("is_paid", d.optBoolean("IsPaidUser", false))
            put("groups", groups)
            put("packages", pkgs)
        }
    }

    /**
     * 给卡片用的合并视图：余额 + 过期预警。任一失败都只在对应字段说明，不影响另一个。
     *
     * balance / summary / expiring 是三次互不依赖的上游请求，原来串行 400+400+500ms。
     * 这里并行发出，总耗时 ≈ 最慢那一次。expiring 需要 balance 的 PackageCodes，
     * 有上次的缓存就提前发，没有就等 balance 回来再补一次。
     */
    suspend fun card(acc: WbAccount, useCache: Boolean = true, ctx: Context? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (useCache && ctx != null) {
                loadCache(ctx, "card_${acc.uid}")?.let { return@withContext it }
            }
            val out = JSONObject()
            val knownCodes = cachedCodes[acc.uid]
            val fBal = async { runCatching { balance(acc) }.getOrNull() }
            val fSum = async { runCatching { summary(acc) }.getOrNull() }
            val fExp = knownCodes?.let { codes -> async { runCatching { expiring(acc, codes) }.getOrNull() } }

            val bal = fBal.await()
            if (bal != null) {
                out.put("remaining", bal.optDouble("remaining", 0.0))
                out.put("used_total", bal.optDouble("used_total", 0.0))
                out.put("capacity_total", bal.optDouble("capacity_total", 0.0))
                out.put("packages", bal.optJSONArray("packages") ?: JSONArray())
                val codes = mutableListOf<String>()
                bal.optJSONArray("codes")?.let { arr ->
                    for (i in 0 until arr.length()) codes.add(arr.optString(i, ""))
                }
                if (codes.isNotEmpty()) cachedCodes[acc.uid] = codes
            } else {
                out.put("error", "余额查询失败")
                out.put("packages", JSONArray())
            }

            // 官方汇总：TotalCredits 更权威，还带四项分组。拿不到就不写，绝不编数字。
            val sm = fSum.await()
            if (sm != null) {
                out.put("official_total", sm.optDouble("total_credits", 0.0))
                out.put("plan_name", sm.optString("plan_name", ""))
                out.put("is_paid", sm.optBoolean("is_paid", false))
                out.put("groups", sm.optJSONArray("groups") ?: JSONArray())
                out.put("remaining", sm.optDouble("total_credits", out.optDouble("remaining", 0.0)))
                out.remove("error")
            }

            // 过期预警：优先用并行那一次的结果；没有（首次、无缓存 codes）就现算
            val ex = fExp?.await() ?: run {
                val codes = bal?.optJSONArray("codes")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it, "") }
                } ?: emptyList()
                runCatching { expiring(acc, codes) }.getOrNull()
            }
            if (ex != null) out.put("expiring", ex)

            out.put("ok", !out.has("error"))
            out.put("updated_at", System.currentTimeMillis())
            if (ctx != null && bal != null) saveCache(ctx, "card_${acc.uid}", out)
            out
        }

    /** 上一次 balance 返回的 PackageCodes，让 expiring 能提前并行（只存内存，不落盘）。 */
    private val cachedCodes = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
}
