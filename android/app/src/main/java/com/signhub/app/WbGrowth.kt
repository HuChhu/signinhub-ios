package com.signhub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkBuddy 成长中心（一键完成任务 + 每日任务全链路）。
 *
 * 接口（与官方 growthSpace 前端对齐）：
 *   列表   GET  {chat}/v2/activity/growth/tasks
 *   资料   GET  {chat}/v2/activity/growth/profile
 *   接受   POST {chat}/v2/activity/growth/tasks/accept   {"task_codes":[...]}
 *   领奖   POST {web}/activity/growth/tasks/<code>/claim  （无 body，头 x-client-platform: web）
 *   上报   POST {chat}/v2/report        （桌面指纹，JSON 数组）
 *          POST {web}/v2/report         （web 指纹）
 *   Buddy  POST {chat}/activity/growth/buddy/agreement {"agree":true}
 *          POST {chat}/activity/growth/buddy/first {}
 *   主题   POST {chat}/v2/user-asset/appearance/set {"themeKey":...}
 *   对话   POST {chat}/v2/chat/completions （stream，glm-5.2）
 *
 * 每日任务段：
 *   GET  {chat}/v2/activity/growth/streak|heatmap|energy|lottery/summary|buddy/quota|buddy/travel/status
 *   POST {chat}/v2/activity/growth/redeem | makeup-cards/use | lottery/draw | buddy/open
 */
object WbGrowth {

    private const val CHAT = "https://copilot.tencent.com"
    private const val WEB = "https://www.workbuddy.cn"
    private const val DESKTOP_UA = "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"

    private const val GROWTH = "/v2/activity/growth"
    private const val METER = "/v2/billing/meter"

    /** 需真实动作，不做自动上报 */
    private val NOT_AUTO = setOf("wb_wechat_oa_subscribe_task", "Expert_Philanthropy")

    /** 每个任务的动作说明（服务端只认真实交互，合成事件不推进进度） */
    private val TASK_HINT = mapOf(
        "wb_wechat_oa_subscribe_task" to "需在微信里真实关注「腾讯 WorkBuddy」官方公众号",
        "Expert_Philanthropy" to "需真实参与公益捐赠（账户级动作）",
        "black_cat" to "夜间限定，仅 23:00–08:00 可完成",
        "expert_5" to "需在客户端真实召唤并对话 5 次专家",
        "Expert_team_use_3" to "需在客户端真实使用 3 次专家团",
        "Expert_lighthouse" to "需在客户端真实召唤「腾讯轻量云」专家",
        "Buddy_App" to "需在客户端打开「发现应用」并体验",
        "Buddy_App_QQ" to "需在客户端打开「企鹅教师助手」应用",
        "template_5" to "需在客户端真实使用 5 个模板",
        "Model_chat_GLM5.2" to "需真实调用 GLM-5.2 模型对话",
        "create_canvas" to "需在设计创意模式里真实创建画布",
        "Library_read" to "需在网页端真实访问资料库文档",
        "Hp_Appearance" to "需在设置里真实应用主题",
        "RichMeow_Chat" to "需与 RichMeow 真实对话",
        "chat_5" to "需与 AI 真实聊天 5 次",
        "first_buddy" to "需真实领取一只 Buddy",
        "skill_1" to "需真实使用热门技能",
        "automation_1" to "需真实创建自动化任务",
        "playbook_prompt" to "需真实使用 Playbook 提示词",
    )
    private const val GEN_HINT = "需在 WorkBuddy 客户端真实使用该功能后才会记功"

    private val STATUS_CN = mapOf(
        "claimed" to "已领取",
        "completed" to "待领取",
        "in_progress" to "进行中",
        "accepted" to "待完成",
        "not_accepted" to "未开始",
    )

    private val TIERS = listOf(
        Triple("7d", 7, 0),
        Triple("14d", 14, 50),
        Triple("28d", 28, 150),
    )
    private const val MAKEUP_MAX_PER_RUN = 1

    // N3（BUGS-v2.1）：显式 callTimeout，见 WbMulti.kt 同处注释。
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ── HTTP ──────────────────────────────────────────────────────────
    private fun call(
        url: String, method: String, acc: WbAccount, body: String? = null,
        extra: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        return try {
            val b = Request.Builder().url(url)
            b.addHeader("Authorization", "Bearer " + acc.accessToken)
            b.addHeader("Accept", "application/json, text/plain, */*")
            b.addHeader("Content-Type", "application/json")
            if (acc.uid.isNotBlank()) b.addHeader("X-User-Id", acc.uid)
            if (acc.domain.isNotBlank()) b.addHeader("X-Domain", acc.domain)
            b.addHeader("User-Agent", DESKTOP_UA)
            extra.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
            if (method == "POST") b.post((body ?: "{}").toRequestBody(JSON)) else b.get()
            client.newCall(b.build()).execute().use { r ->
                Pair(r.code, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Pair(-1, """{"err":${JSONObject.quote(e.message ?: "network")}}""")
        }
    }

    private fun obj(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        JSONObject().put("code", -1).put("msg", text.take(300))
    }

    private fun sha36(uid: String, salt: String): String {
        val d = MessageDigest.getInstance("SHA-256")
            .digest(("$salt:$uid").toByteArray(Charsets.UTF_8))
        val hex = d.joinToString("") { "%02x".format(it) }
        return hex.take(36)
    }

    private fun uuidToken(prefix: String) = "$prefix-${UUID.randomUUID()}"

    // ── 只读 ──────────────────────────────────────────────────────────
    private suspend fun get(acc: WbAccount, path: String): JSONObject =
        withContext(Dispatchers.IO) {
            val (code, text) = call(CHAT + path, "GET", acc)
            val o = obj(text)
            if (code != 200) throw RuntimeException("$path http=$code ${brief(o)}")
            o.optJSONObject("data") ?: JSONObject()
        }

    private suspend fun post(acc: WbAccount, path: String, body: String = "{}"): Pair<Int, JSONObject> =
        withContext(Dispatchers.IO) {
            val (code, text) = call(CHAT + path, "POST", acc, body)
            Pair(code, obj(text))
        }

    private fun brief(o: JSONObject, n: Int = 160): String =
        o.optJSONObject("data")?.toString()?.take(n)
            ?: o.optString("msg", o.optString("message", o.toString().take(n)))

    suspend fun listTasks(acc: WbAccount): JSONArray = withContext(Dispatchers.IO) {
        val (code, text) = call(CHAT + GROWTH + "/tasks", "GET", acc)
        val o = obj(text)
        if (code != 200) throw RuntimeException("tasks http=$code ${brief(o)}")
        o.optJSONObject("data")?.optJSONArray("tasks") ?: JSONArray()
    }

    suspend fun profile(acc: WbAccount): JSONObject = get(acc, GROWTH + "/profile")

    // ── 写操作 ────────────────────────────────────────────────────────
    private suspend fun accept(acc: WbAccount, codes: List<String>) {
        if (codes.isEmpty()) return
        val body = JSONObject().put("task_codes", JSONArray(codes)).toString()
        post(acc, GROWTH + "/tasks/accept", body)
    }

    private data class ClaimRet(val ok: Boolean, val already: Boolean, val credit: Int, val energy: Int, val raw: String)

    private suspend fun claim(acc: WbAccount, code: String): ClaimRet = withContext(Dispatchers.IO) {
        val hdr = mapOf(
            "x-client-platform" to "web",
            "Origin" to WEB,
            "Referer" to "$WEB/profile/growth-center",
            "User-Agent" to WEB_UA,
        )
        val (st, text) = call("$WEB/activity/growth/tasks/$code/claim", "POST", acc, null, hdr)
        val o = obj(text)
        val d = o.optJSONObject("data") ?: JSONObject()
        ClaimRet(
            st == 200,
            d.optBoolean("already_claimed", false),
            d.optInt("credit", 0),
            d.optInt("energy", 0),
            o.optString("msg", o.optString("message", text.take(120)))
        )
    }

    // ── 行为上报 ──────────────────────────────────────────────────────
    private fun deskFp(acc: WbAccount): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().apply {
            put("extName", "workbuddy-desktop")
            put("ideName", "WorkBuddy")
            put("appVersion", "5.5.6")
            put("os", "Win32"); put("arch", "x64"); put("osVersion", "10.0")
            put("userAgent", DESKTOP_UA)
            put("machineId", sha36(acc.uid, "machine"))
            put("userId", acc.uid)
            put("locale", "zh-CN")
            put("timestamp", now)
            put("reportDelay", 0)
        }
    }

    private suspend fun reportDesktop(acc: WbAccount, events: List<JSONObject>): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val fp = deskFp(acc)
            val arr = JSONArray()
            events.forEach { ev ->
                val m = JSONObject(fp.toString())
                ev.keys().forEach { k ->
                    if (ev.opt(k) != null) m.put(k, ev.opt(k))
                }
                arr.put(m)
            }
            call(
                CHAT + "/v2/report", "POST", acc, arr.toString(),
                mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "X-Product" to "SaaS",
                    "X-Request-ID" to sha36(acc.uid, "req") + ((System.currentTimeMillis() * 1000) % 1000000),
                )
            )
        }

    private suspend fun reportWeb(
        acc: WbAccount, eventCode: String, pageUrl: String, elementId: String, elementName: String
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val ev = JSONObject().apply {
            put("eventCode", eventCode)
            put("timestamp", System.currentTimeMillis())
            put("reportDelay", 0)
            put("pageURL", pageUrl)
            put("elementId", elementId)
            put("elementName", elementName)
            put("os", "Win32"); put("arch", ""); put("osVersion", "10.0")
            put("userAgent", WEB_UA)
            put("machineId", sha36(acc.uid, "webmachine"))
            put("userId", acc.uid)
        }
        call(
            WEB + "/v2/report", "POST", acc, JSONArray().put(ev).toString(),
            mapOf(
                "x-client-platform" to "web",
                "Origin" to WEB,
                "Referer" to pageUrl,
                "User-Agent" to WEB_UA,
                "Accept" to "application/json",
            )
        )
    }

    private fun chatEvent(acc: WbAccount, convId: String, modelId: String = "deepseek-v4-flash", modelName: String = "DeepSeek V4 Flash"): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().apply {
            put("eventCode", "chat_request_send")
            put("timestamp", now); put("reportDelay", 0)
            put("mode", "craft"); put("conversationId", convId); put("requestId", convId)
            put("inputLength", 12)
            put("requestModelId", modelId); put("requestModelName", modelName)
            put("isPlan", false); put("isAutoExecuteTerminal", false)
            put("isAutoModify", false); put("codebaseEnable", false)
            put("maxToken", 0); put("maxSteps", 0); put("temperature", 0); put("maxRetries", 0)
            put("mentionContexts", JSONArray()); put("knowledgeId", JSONArray()); put("knowledgeName", JSONArray())
            put("codebaseId", ""); put("mentionContextCount", 0); put("command", "")
            put("expertId", ""); put("recommendId", ""); put("skillId", ""); put("skillCount", 0)
            put("totalCount", 0); put("fileUri", ""); put("presentAt", now); put("traceId", "")
            put("rootRequestId", convId); put("parentConversationID", convId)
            put("agentName", "default"); put("agentType", "conversation")
            put("userId", acc.uid)
        }
    }

    /** 真实对话一次（成长任务「GLM-5.2 对话」与签到前置对话共用）。 */
    suspend fun chatCompletion(acc: WbAccount, modelId: String = "glm-5.2", prompt: String = "你好"): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                put("stream", true)
                put("max_tokens", 32)
            }.toString()
            val (code, _) = call(
                CHAT + "/v2/chat/completions", "POST", acc, body,
                mapOf("Accept" to "text/event-stream", "User-Agent" to DESKTOP_UA)
            )
            code == 200
        }

    // ── 单任务驱动 ────────────────────────────────────────────────────
    private suspend fun runTask(acc: WbAccount, code: String): Pair<Boolean, String> {
        if (code in NOT_AUTO) return false to "需真实动作（${TASK_HINT[code] ?: code}），无法自动化"
        try {
            accept(acc, listOf(code))
            val cid = "signhub-${System.currentTimeMillis()}"
            when (code) {
                "chat_5" -> for (i in 0 until 5) {
                    reportDesktop(acc, listOf(chatEvent(acc, "$cid-$i")))
                    delay(1000)
                }
                "first_buddy" -> {
                    reportDesktop(acc, listOf(chatEvent(acc, cid)))
                    delay(2000)
                    post(acc, "/activity/growth/buddy/agreement", """{"agree":true}""")
                    post(acc, "/activity/growth/buddy/first")
                }
                "template_5" -> {
                    val tps = listOf(
                        "1" to "深度研究", "2" to "周报生成", "3" to "竞品分析",
                        "4" to "活动策划", "5" to "代码评审"
                    )
                    for ((tid, tname) in tps) {
                        val rid = cid + tid
                        reportDesktop(acc, listOf(
                            JSONObject().put("eventCode", "agent_task_created_with_template")
                                .put("templateId", tid).put("templateName", tname)
                                .put("conversationId", cid).put("requestId", rid).put("userId", acc.uid),
                            JSONObject().put("eventCode", "template_used")
                                .put("templateId", tid).put("templateName", tname)
                                .put("conversationId", cid).put("requestId", rid).put("userId", acc.uid)
                        ))
                        delay(800)
                    }
                }
                "create_canvas" -> reportDesktop(acc, listOf(
                    JSONObject().put("eventCode", "wbx_design_canvas_task_create/open")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid)
                ))
                "playbook_prompt" -> reportDesktop(acc, listOf(
                    JSONObject().put("eventCode", "playbook_cta_click")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid),
                    JSONObject().put("eventCode", "playbook_prompt_send")
                        .put("promptId", "pm-gtm-launch-plan")
                        .put("promptName", "新产品上市 GTM 发布计划一页纸")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid)
                ))
                "RichMeow_Chat" -> reportDesktop(acc, listOf(
                    JSONObject().put("eventCode", "agent_task_created")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid)
                        .put("extName", "workbuddy-desktop"),
                    JSONObject().put("eventCode", "chat_message_response")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid)
                        .put("isSuccessful", true)
                ))
                "Library_read" -> reportWeb(
                    acc, "web_element_click",
                    "$WEB/space/d/o0KWYeynteVv06UnAZqIFm",
                    "library_doc_intro_click", "WorkBuddy资料库介绍"
                )
                "Hp_Appearance" -> {
                    val themeKey = "theme-tkmw7j"
                    post(acc, "/v2/user-asset/appearance/set", JSONObject().put("themeKey", themeKey).toString())
                    reportDesktop(acc, listOf(
                        JSONObject().put("eventCode", "appearance_skin_apply")
                            .put("action", "apply").put("source", "settings_close")
                            .put("id", themeKey).put("vipLevel", 0).put("series", "")
                            .put("type", "unknown").put("conversationId", cid)
                            .put("requestId", cid).put("userId", acc.uid)
                    ))
                }
                "Buddy_App", "Buddy_App_QQ" -> reportDesktop(acc, listOf(
                    JSONObject().put("eventCode", "buddy_app_event")
                        .put("stage", "discover").put("appId", "cb_y5Dy46tPQGGWtueMxXbe")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid)
                ))
                "automation_1" -> reportDesktop(acc, listOf(
                    JSONObject().put("eventCode", "automated_task_create_suc")
                        .put("taskName", "signhub 自动化")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid)
                ))
                "Model_chat_GLM5.2" -> {
                    chatCompletion(acc, "glm-5.2", "hi")
                    reportDesktop(acc, listOf(chatEvent(acc, cid, "glm-5.2", "GLM-5.2")))
                }
                "skill_1" -> reportDesktop(acc, listOf(
                    JSONObject().put("eventCode", "chat_message_response")
                        .put("finishReason", "tool_calls")
                        .put("conversationId", cid).put("requestId", cid).put("userId", acc.uid),
                    JSONObject().put("eventCode", "skill_info")
                        .put("id", "润泽小馆·日报撰写")
                        .put("skillId", "skill_2097350077599879168")
                        .put("skillVersion", "1.0.0").put("toolStatus", "success")
                        .put("fileCount", 56).put("source", "workbuddy-desktop")
                        .put("conversationId", cid).put("requestId", cid).put("messageId", cid)
                        .put("requestModelId", "fast-model").put("requestModelName", "fast-model")
                        .put("traceId", cid).put("userId", acc.uid)
                ))
                "expert_5" -> runExpert(acc, cid, "agent", 5)
                "Expert_team_use_3" -> runExpert(acc, cid, "team", 3)
                "Expert_lighthouse" -> runExpert(acc, cid, "lh", 1, "ex_2cvvUZQhDyeJ")
                "black_cat" -> {
                    val h = java.time.LocalTime.now().hour
                    if (!(h >= 23 || h < 8)) return false to "black_cat 仅在 23:00–08:00 夜间窗口可完成"
                    reportDesktop(acc, listOf(chatEvent(acc, cid)))
                }
                else -> return false to "任务 $code 未实现驱动：需在客户端真实使用该功能"
            }
            delay(2000)
            val r = claim(acc, code)
            return when {
                r.ok && r.already -> true to "已领过（幂等）"
                r.ok && (r.credit > 0 || r.energy > 0) -> true to "已领 +${r.credit} 分 / +${r.energy} 能量"
                r.ok -> true to "已领取"
                r.raw.contains("not completed") -> false to "进度未满：需先在客户端真实使用该功能"
                else -> false to "领奖失败 ${r.raw.take(80)}"
            }
        } catch (e: Exception) {
            return false to "异常：${e.message}"
        }
    }

    private suspend fun runExpert(acc: WbAccount, cid: String, kind: String, n: Int, expertId: String? = null) {
        for (i in 0 until n) {
            val rid = "$cid-$i"
            reportDesktop(acc, listOf(
                JSONObject().put("eventCode", "summon_click").put("kind", kind)
                    .put("conversationId", rid).put("requestId", rid).put("userId", acc.uid),
                JSONObject().put("eventCode", "summoned").put("kind", kind)
                    .put("conversationId", rid).put("requestId", rid).put("userId", acc.uid),
                JSONObject().put("eventCode", "agent_task_created").put("has_expert", true)
                    .put("expert_id", expertId ?: "signhub-exp").put("expert_name", "轻量云专家")
                    .put("conversationId", rid).put("requestId", rid).put("userId", acc.uid),
                JSONObject().put("eventCode", "chat_message_response")
                    .put("conversationId", rid).put("requestId", rid).put("userId", acc.uid),
                JSONObject().put("eventCode", "expert_actual_use").put("type", kind)
                    .put("conversationId", rid).put("requestId", rid).put("userId", acc.uid)
            ))
            delay(1000)
        }
    }

    // ── 一次性任务批量 ────────────────────────────────────────────────
    /** 只领已达标奖励，不上报任何事件。 */
    suspend fun claimAll(acc: WbAccount): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        val tasks = try { listTasks(acc) } catch (e: Exception) {
            return@withContext JSONArray().put(errRow("list", e.message ?: "读取任务失败"))
        }
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            val code = t.optString("task_code", "")
            if (code.isBlank()) continue
            val p = t.optJSONObject("progress") ?: JSONObject()
            val cur = p.optInt("current", 0)
            val tgt = p.optInt("target", 0)
            val ast = t.optString("accept_status", "")
            val done = tgt > 0 && cur >= tgt
            if (ast == "claimed") continue
            if (!done && ast != "completed") continue
            val r = claim(acc, code)
            out.put(JSONObject().apply {
                put("code", code)
                put("ok", r.ok)
                put("msg", when {
                    r.ok && r.already -> "已领过"
                    r.ok && r.credit > 0 -> "领取 +${r.credit} 分"
                    r.ok -> "已领取"
                    else -> r.raw.take(80)
                })
                put("skipped", false)
            })
            delay(300)
        }
        out
    }

    /** 一键：accept 全部 → 逐个驱动 → 领奖。 */
    suspend fun runTasks(acc: WbAccount): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        val tasks = try { listTasks(acc) } catch (e: Exception) {
            return@withContext JSONArray().put(errRow("list", e.message ?: "读取任务失败"))
        }
        val codes = mutableListOf<String>()
        for (i in 0 until tasks.length()) {
            tasks.optJSONObject(i)?.optString("task_code", "")?.let { if (it.isNotBlank()) codes.add(it) }
        }
        try { accept(acc, codes) } catch (_: Exception) {}

        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            val code = t.optString("task_code", "")
            if (code.isBlank()) continue
            val p = t.optJSONObject("progress") ?: JSONObject()
            val cur = p.optInt("current", 0)
            val tgt = p.optInt("target", 0)
            val ast = t.optString("accept_status", "")
            if (ast == "claimed" && tgt > 0 && cur >= tgt) {
                out.put(JSONObject().put("code", code).put("ok", true).put("msg", "已完成已领").put("skipped", true))
                continue
            }
            if (code in NOT_AUTO) {
                out.put(JSONObject().put("code", code).put("ok", false)
                    .put("msg", TASK_HINT[code] ?: "需真实动作").put("skipped", true))
                continue
            }
            val (ok, msg) = runTask(acc, code)
            out.put(JSONObject().put("code", code).put("ok", ok).put("msg", msg).put("skipped", false))
            delay(500)
        }
        out
    }

    // ── 每日任务 ──────────────────────────────────────────────────────
    private suspend fun streak(acc: WbAccount) = get(acc, GROWTH + "/streak")
    private suspend fun heatmap(acc: WbAccount) = get(acc, GROWTH + "/heatmap")
    private suspend fun energy(acc: WbAccount) = get(acc, GROWTH + "/energy")
    private suspend fun lottery(acc: WbAccount) = get(acc, GROWTH + "/lottery/summary")
    private suspend fun buddyQuota(acc: WbAccount) = get(acc, GROWTH + "/buddy/quota")
    private suspend fun travelState(acc: WbAccount): String {
        val d = get(acc, GROWTH + "/buddy/travel/status")
        return d.optString("state", "")
    }

    /**
     * 官网连登热力图（近一年）。统计页拿它来画格子，
     * 因为本地 SQLite 只存本机签到过的日子，重装/换机后必然是空的。
     * 返回 {ok, cells:[{date,score}], today:{date,score}}
     */
    suspend fun heatCells(acc: WbAccount): JSONObject = withContext(Dispatchers.IO) {
        try {
            val d = heatmap(acc)
            val cells = d.optJSONArray("cells") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until cells.length()) {
                val c = cells.optJSONObject(i) ?: continue
                val date = c.optString("date", "")
                if (date.isBlank()) continue
                out.put(JSONObject().apply {
                    put("date", date)
                    put("score", c.optInt("score", 0))
                })
            }
            JSONObject().apply {
                put("ok", true)
                put("cells", out)
                put("today", d.optJSONObject("today") ?: JSONObject())
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "heatmap 查询失败")
        }
    }

    private fun makeupBalance(st: JSONObject): Int {
        val o = st.opt("makeup_cards")
        val v: Any? = if (o is JSONObject) o.opt("balance") else o
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    /**
     * 当月真正断登、且尚未补登的日期。
     * /streak 的 makeup_dates 是已补登记录，断点必须从 /heatmap 的 score==0 反推。
     */
    private fun makeupCandidates(st: JSONObject, hm: JSONObject): List<String> {
        return try {
            val todayStr = hm.optJSONObject("today")?.optString("date", "") ?: ""
            val today = java.time.LocalDate.parse(todayStr)
            val launch = java.time.LocalDate.parse(st.optString("launch_date", ""))
            val madeUp = mutableSetOf<java.time.LocalDate>()
            val md = st.optJSONObject("streak")?.optJSONArray("makeup_dates")
            if (md != null) for (i in 0 until md.length()) {
                try { madeUp.add(java.time.LocalDate.parse(md.getString(i))) } catch (_: Exception) {}
            }
            var start = today.withDayOfMonth(1)
            if (launch.isAfter(start)) start = launch
            val floor = java.time.LocalDate.of(2026, 6, 17)
            if (floor.isAfter(start)) start = floor

            val scores = mutableMapOf<java.time.LocalDate, Double>()
            val cells = hm.optJSONArray("cells") ?: return emptyList()
            for (i in 0 until cells.length()) {
                val c = cells.optJSONObject(i) ?: return emptyList()
                val d = java.time.LocalDate.parse(c.optString("date", ""))
                val sc = c.optDouble("score", -1.0)
                if (sc < 0) return emptyList()
                val prev = scores[d]
                if (prev != null && prev != sc) return emptyList()
                scores[d] = sc
            }
            scores.entries
                .filter { !it.key.isBefore(start) && it.key.isBefore(today) && it.value == 0.0 && it.key !in madeUp }
                .map { it.key.toString() }
                .sortedDescending()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 每日任务卡片：当天可做/可领的刷新项。 */
    suspend fun dailyCard(acc: WbAccount): JSONObject = withContext(Dispatchers.IO) {
        try {
            val st = streak(acc)
            val str = st.optJSONObject("streak") ?: JSONObject()
            val rs = st.optJSONObject("redemption_status") ?: JSONObject()
            val en = energy(acc)
            val lo = lottery(acc)
            val bq = buddyQuota(acc)
            val days = str.optInt("days", 0)
            val rows = JSONArray()
            var todo = 0

            // 签到由 /api/status 与签到卡片负责，这里只读连登天数
            // 连登兑换
            for ((tier, need, credit) in TIERS) {
                val status = rs.optString("tier_${tier}_status", "")
                val can = days >= need && status != "claimed"
                if (can) todo++
                rows.put(JSONObject().apply {
                    put("code", "redeem_$tier")
                    put("title", "连登兑换 · $tier（$need 天）")
                    put("reward", "+$credit 积分")
                    put("status", if (status == "claimed") "done" else if (can) "todo" else "locked")
                    put("note", if (status == "claimed") "本月已兑" else if (can) "可兑换" else "连登差 ${need - days} 天")
                })
            }

            // 补登卡
            val cards = makeupBalance(st)
            var gaps: List<String> = emptyList()
            var gapErr: String? = null
            try { gaps = makeupCandidates(st, heatmap(acc)) } catch (e: Exception) { gapErr = e.message }
            if (gapErr != null) {
                rows.put(JSONObject().put("code", "makeup").put("title", "补登卡补断登")
                    .put("reward", "保住连登天数").put("status", "done")
                    .put("note", "活跃日历读取失败，本轮跳过"))
            } else if (gaps.isNotEmpty() && cards > 0) {
                todo++
                rows.put(JSONObject().put("code", "makeup").put("title", "补登卡补断登")
                    .put("reward", "保住连登天数").put("status", "todo")
                    .put("note", "待补 ${gaps.size} 天：${gaps.take(3).joinToString("、") { it.substring(5) }}"))
            } else if (gaps.isNotEmpty()) {
                rows.put(JSONObject().put("code", "makeup").put("title", "补登卡补断登")
                    .put("reward", "保住连登天数").put("status", "done")
                    .put("note", "有 ${gaps.size} 天断登，但补登卡不足（持有 $cards）"))
            } else {
                rows.put(JSONObject().put("code", "makeup").put("title", "补登卡补断登")
                    .put("reward", "保住连登天数").put("status", "done").put("note", "本月无断登"))
            }

            // 抽奖
            val chances = lo.optInt("chances", 0)
            if (chances > 0) todo++
            rows.put(JSONObject().put("code", "lottery").put("title", "每日任务轮盘（抽奖）")
                .put("reward", "10~100 积分 / 周边")
                .put("status", if (chances > 0) "todo" else "done")
                .put("note", if (chances > 0) "剩 $chances 次" else "暂无次数"))

            // Buddy 盲盒
            val aff = bq.optInt("affordable", 0)
            if (aff > 0) todo++
            rows.put(JSONObject().put("code", "buddy").put("title", "Buddy 盲盒")
                .put("reward", "随机 Buddy")
                .put("status", if (aff > 0) "todo" else "done")
                .put("note", if (aff > 0) "可开 $aff 次" else "能量不足"))

            JSONObject().apply {
                put("ok", true)
                put("streak_days", days)
                put("makeup_cards", cards)
                put("energy", en.optInt("balance", 0))
                put("todo", todo)
                put("rows", rows)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "读取失败").put("rows", JSONArray())
        }
    }

    /** 一键做完当天所有动作：签到 → 连登兑换 → 补登 → 抽奖 → 开盒。 */
    suspend fun runDaily(acc: WbAccount): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        fun add(code: String, ok: Boolean, msg: String, skipped: Boolean = false) {
            out.put(JSONObject().put("code", code).put("ok", ok).put("msg", msg).put("skipped", skipped))
        }

        // 1) 连登兑换
        try {
            val st = streak(acc)
            val str = st.optJSONObject("streak") ?: JSONObject()
            val rs = st.optJSONObject("redemption_status") ?: JSONObject()
            val days = str.optInt("days", 0)
            for ((tier, need, _) in TIERS) {
                val status = rs.optString("tier_${tier}_status", "")
                if (status == "claimed") { add("redeem_$tier", true, "本月已兑换过", true); continue }
                if (days < need) { add("redeem_$tier", false, "连登 $days 天 < $need 天，未达标", true); continue }
                val body = JSONObject().put("tier", tier)
                    .put("client_token", uuidToken("redeem-$tier")).toString()
                val (code, o) = post(acc, GROWTH + "/redeem", body)
                add("redeem_$tier", code == 200, if (code == 200) "兑换成功" else "兑换失败 ${brief(o)}")
                delay(400)
            }
        } catch (e: Exception) { add("redeem", false, "异常：${e.message}") }

        // 2) 补登
        try {
            val st2 = streak(acc)
            val cards = makeupBalance(st2)
            val gaps = try { makeupCandidates(st2, heatmap(acc)) } catch (e: Exception) {
                add("makeup", false, "活跃日历读取失败，跳过：${e.message}"); emptyList()
            }
            if (gaps.isEmpty()) {
                add("makeup", true, "本月无断登，无需补", true)
            } else if (cards <= 0) {
                add("makeup", true, "有 ${gaps.size} 天断登，但补登卡不足", true)
            } else {
                var made = 0
                for (d in gaps.take(MAKEUP_MAX_PER_RUN)) {
                    val (code, o) = post(acc, GROWTH + "/makeup-cards/use",
                        JSONObject().put("target_date", d).toString())
                    val msg = o.optString("msg", "")
                    if (code == 200) { made++; add("makeup_$d", true, "补登 $d 成功") }
                    else if (code == 400 && msg.lowercase().contains("no makeup needed")) {
                        made++; add("makeup_$d", true, "$d 已活跃/已补登", true)
                    } else { add("makeup_$d", false, "补登 $d 失败 ${msg.take(100)}"); break }
                    delay(400)
                }
                if (gaps.size > made) add("makeup_pending", true, "另有 ${gaps.size - made} 天可补，下次继续", true)
            }
        } catch (e: Exception) { add("makeup", false, "异常：${e.message}") }

        // 3) 抽奖（上限 10 次防失控）
        try {
            var drew = 0
            for (i in 0 until 10) {
                val lo = lottery(acc)
                if (lo.optInt("chances", 0) <= 0) {
                    if (drew == 0) add("lottery", true, "暂无抽奖次数（可靠连登兑换获得）", true)
                    break
                }
                val (code, o) = post(acc, GROWTH + "/lottery/draw",
                    JSONObject().put("client_token", uuidToken("draw")).toString())
                val prize = o.optJSONObject("data")?.optString("prize_name", "") ?: ""
                add("lottery", code == 200,
                    if (code == 200 && prize.isNotBlank()) "抽中 $prize"
                    else if (code == 200) "抽奖成功" else "抽奖失败 ${brief(o)}")
                if (code != 200) break
                drew++
                delay(400)
            }
        } catch (e: Exception) { add("lottery", false, "异常：${e.message}") }

        // 4) 开盒（先确认旅行 idle，否则会覆盖待领礼物）
        try {
            val st = try { travelState(acc) } catch (e: Exception) { "" }
            if (st != "idle") {
                add("buddy", true, "Buddy 旅行中/礼物待领（state=${st.ifBlank { "unknown" }}），跳过开盒", true)
            } else {
                val bq = buddyQuota(acc)
                val n = minOf(bq.optInt("affordable", 0), 5)
                if (n <= 0) {
                    add("buddy", true,
                        "能量不足（需 ${bq.optInt("cost_per_open", 10)}，现有 ${bq.optInt("balance", 0)}）", true)
                } else {
                    val (code, o) = post(acc, GROWTH + "/buddy/open", JSONObject().put("count", n).toString())
                    add("buddy", code == 200, if (code == 200) "开盒 $n 次成功" else "开盒失败 ${brief(o)}")
                }
            }
        } catch (e: Exception) { add("buddy", false, "异常：${e.message}") }

        out
    }

    // ── 成长中心总览 ──────────────────────────────────────────────────
    suspend fun overview(acc: WbAccount): JSONObject = withContext(Dispatchers.IO) {
        try {
            val prof = profile(acc)
            val tasks = listTasks(acc)
            val rows = JSONArray()
            var claimable = 0
            var doneCount = 0
            for (i in 0 until tasks.length()) {
                val t = tasks.optJSONObject(i) ?: continue
                val code = t.optString("task_code", "")
                val p = t.optJSONObject("progress") ?: JSONObject()
                val cur = p.optInt("current", 0)
                val tgt = p.optInt("target", 0)
                val ast = t.optString("accept_status", "")
                val progDone = tgt > 0 && cur >= tgt
                val status = when {
                    ast == "claimed" -> "claimed"
                    ast == "completed" || progDone -> "completed"
                    ast == "in_progress" -> "in_progress"
                    ast == "accepted" -> "accepted"
                    else -> "not_accepted"
                }
                if (status == "completed") claimable++
                if (status == "claimed") doneCount++
                rows.put(JSONObject().apply {
                    put("code", code)
                    put("title", t.optString("title", code))
                    put("reward", t.optInt("reward_credit", 0))
                    put("energy", t.optInt("reward_energy", 0))
                    put("status", status)
                    put("status_cn", STATUS_CN[status] ?: status)
                    put("current", cur); put("target", tgt)
                    put("can_claim", status == "completed")
                    put("not_auto", code in NOT_AUTO)
                    put("hint", if (status == "claimed" || status == "completed")
                        "" else (TASK_HINT[code] ?: GEN_HINT))
                })
            }
            JSONObject().apply {
                put("ok", true)
                put("level", prof.optInt("level", 0))
                put("completed", prof.optInt("completed", doneCount))
                put("total", prof.optInt("total", tasks.length()))
                put("claimable", claimable)
                put("tasks", rows)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "读取失败").put("tasks", JSONArray())
        }
    }

    private fun errRow(code: String, msg: String): JSONObject =
        JSONObject().put("code", code).put("ok", false).put("msg", msg).put("skipped", false)
}
