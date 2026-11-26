package com.signhub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 手机端凭据小工具：
 *  - WorkBuddy：用 refreshToken 自动续期 accessToken；签到失败(401/403)时自动换一次再重试
 *  - Trae：生成 16 位数字设备号
 */
object WbAuth {
    private const val CN_CHAT = "https://copilot.tencent.com"
    private const val CN_BILLING = "https://www.codebuddy.cn"
    private const val GLOBAL = "https://www.workbuddy.ai"
    private const val UA = "CLI/2.63.2 CodeBuddy/2.63.2"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // N3（BUGS-v2.1）：显式 callTimeout，见 WbMulti.kt 同处注释。
    // 不加时线程会被 OkHttp 的 connect+read 自然超时占住，f.cancel(true) 无效。
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    /** 用 refreshToken 换新 accessToken；成功返回 (accessToken, refreshToken)。 */
    suspend fun refresh(refreshToken: String, global: Boolean): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (refreshToken.isBlank()) return@withContext null
            val base = if (global) GLOBAL else CN_CHAT
            val ref = if (global) GLOBAL else CN_BILLING
            try {
                val req = Request.Builder()
                    .url("$base/v2/plugin/auth/token/refresh")
                    .post("{}".toRequestBody(JSON))
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Origin", ref)
                    .addHeader("Referer", "$ref/")
                    .addHeader("User-Agent", UA)
                    .addHeader("X-Refresh-Token", refreshToken)
                    .addHeader("X-Auth-Refresh-Source", "workbuddy")
                    .build()
                client.newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    val env = try { JSONObject(txt) } catch (e: Exception) { return@use null }
                    // 兼容两种信封：{code:0,data:{...}} 或直接 {...}
                    val d = env.optJSONObject("data") ?: env
                    val code = env.optInt("code", 0)
                    if (env.has("code") && code != 0) return@use null
                    val at = d.optString("accessToken", d.optString("access_token", ""))
                    val rt = d.optString("refreshToken", d.optString("refresh_token", refreshToken))
                    if (at.isBlank()) null else Pair(at, rt.ifBlank { refreshToken })
                }
            } catch (e: Exception) { null }
        }

    /**
     * 带续期的 WorkBuddy 签到：凭据被拒且可续期时自动刷新并重试一次，成功后把新 token 写回设置。
     */
    suspend fun runCheckin(ctx: Context, s: AppSettings): CheckinResult {
        var res = LocalCheckin.workbuddy(s.wbToken, s.wbUid, s.wbEnterpriseId, s.wbDomain, s.wbGlobal)
        val rejected = res.status == "fail" &&
            (res.message.contains("凭据被拒绝") || res.message.contains("401") || res.message.contains("403"))
        if (rejected && s.wbRefreshToken.isNotBlank()) {
            val fresh = refresh(s.wbRefreshToken, s.wbGlobal)
            if (fresh != null) {
                try {
                    SettingsStore.save(ctx, s.copy(wbToken = fresh.first, wbRefreshToken = fresh.second))
                } catch (_: Exception) {}
                res = LocalCheckin.workbuddy(fresh.first, s.wbUid, s.wbEnterpriseId, s.wbDomain, s.wbGlobal)
            }
        }
        return res
    }
}

/**
 * Trae 的 `x-device-id` 工具。
 *
 * 关于 9074 的真实语义（v2.6 更正，此前三个版本都判错了）：
 *   · 上游文案「当前参与用户太多，请稍后再试」是**误导性文案**；
 *   · 它实际含义是「**这个 x-device-id 在该账号下没有注册记录**」——
 *     拿它当限流去「等几分钟再试」永远等不到；
 *   · 由此推出两条硬规则：
 *       ① 同一个设备号**只能给一个账号用**。第二个账号拿它去签，服务端认的是
 *          第一个账号的注册记录 → 直接 9074（这就是「单账号正常、加多账号全失败」）；
 *       ② 设备号必须**永久稳定**。每次重新生成 = 每天换一台新设备，永远处于
 *          「未注册」状态 → 永远 9074（v2.4 的随机号、v2.5 的 9074 自动换号都栽在这）。
 *
 * 所以设备号不再随机生成，而是由账号凭据**确定性派生**：同一账号无论重装、换手机、
 * 过多久，算出来都是同一串数字，一次注册长期有效。
 */
object DeviceId {
    /** 随机 16 位数字（仅在既无凭据可派生、用户也没手填时兜底）。 */
    fun generate(): String {
        val t = System.currentTimeMillis()
        val rnd = (100000L..999999L).random()
        val s = (t.toString() + rnd.toString())
        // 取后 16 位纯数字
        return s.filter { it.isDigit() }.padStart(16, '0').takeLast(16)
    }

    /** 是否是「看起来合法」的设备号：纯数字且不短于 6 位。 */
    fun isValid(v: String): Boolean {
        val d = v.trim()
        return d.length >= 6 && d.all { it.isDigit() }
    }

    /**
     * 由账号凭据确定性派生 16 位数字设备号。
     *
     * 同一 seed 永远得到同一结果（SHA-256 前 8 字节 → 取数字补齐 16 位），
     * 因此「重装 App」「换设备」「隔几个月再签」都不会让服务端看到一台新设备。
     *
     * @param seed 账号标识（token / session / uid 都行，只要该账号唯一且稳定）
     */
    fun derive(seed: String): String {
        val src = seed.trim()
        if (src.isBlank()) return generate()
        return try {
            val h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("signhub-tre-dev:" + src).toByteArray(Charsets.UTF_8))
            // 取前 8 字节转十进制数字串，截/补到 16 位
            val num = h.take(8).joinToString("") { "%02d".format(it.toInt() and 0xFF) }
            num.padStart(16, '0').takeLast(16)
        } catch (e: Exception) {
            generate()
        }
    }
}
