package com.qcbru.remind.data

import com.google.firebase.firestore.DocumentSnapshot
import org.json.JSONObject

data class Reminder(
    val id: String,
    val title: String,
    val notes: String,
    val rule: JSONObject,
    val status: String,
    val nextDueAt: Long?,
    val currentScheduledAt: Long?,
    val lastScheduledAt: Long?,
    val lastSentAt: Long?,
    val lastAcknowledgedScheduledAt: Long?,
    val repeatUntilDoneMinutes: Int?,
    val version: Long,
    val updatedAt: Long,
    val sharedId: String? = null,
    val sharedVersion: Long? = null,
    val participantNames: List<String> = emptyList(),
    val priority: Boolean = false,
    val priorityOverride: Boolean? = null,
) {
    val active get() = status == "active" && nextDueAt != null
    val currentAlert get() = lastScheduledAt != null && lastSentAt != null &&
        lastAcknowledgedScheduledAt != lastScheduledAt
    val occurrenceId get() = lastScheduledAt?.let { "${id}_$it" }

    fun asJson(): JSONObject = JSONObject().apply {
        put("sharedId", sharedId); put("sharedVersion", sharedVersion); put("participantNames", org.json.JSONArray(participantNames)); put("priority", priority); put("priorityOverride", priorityOverride)
        put("id", id); put("title", title); put("notes", notes); put("rule", rule)
        put("status", status); put("nextDueAt", nextDueAt); put("currentScheduledAt", currentScheduledAt)
        put("lastScheduledAt", lastScheduledAt); put("lastSentAt", lastSentAt)
        put("lastAcknowledgedScheduledAt", lastAcknowledgedScheduledAt)
        put("repeatUntilDoneMinutes", repeatUntilDoneMinutes); put("version", version); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromDocument(doc: DocumentSnapshot): Reminder = fromJson(
            JSONObject(doc.data ?: emptyMap<String, Any>()).put("id", doc.id)
        )

        fun fromJson(json: JSONObject) = Reminder(
            id = json.getString("id"), title = json.optString("title"), notes = json.optString("notes"),
            rule = json.getJSONObject("rule"), status = json.optString("status"),
            nextDueAt = json.longOrNull("nextDueAt"), currentScheduledAt = json.longOrNull("currentScheduledAt"),
            lastScheduledAt = json.longOrNull("lastScheduledAt"), lastSentAt = json.longOrNull("lastSentAt"),
            lastAcknowledgedScheduledAt = json.longOrNull("lastAcknowledgedScheduledAt"),
            repeatUntilDoneMinutes = json.intOrNull("repeatUntilDoneMinutes"), version = json.optLong("version"),
            updatedAt = json.optLong("updatedAt"),
            sharedId = if (json.isNull("sharedId")) null else json.optString("sharedId").takeIf { it.isNotBlank() },
            sharedVersion = json.longOrNull("sharedVersion"),
            participantNames = json.optJSONArray("participantNames")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            priority = json.optBoolean("priority"),
            priorityOverride = if (json.isNull("priorityOverride")) null else json.getBoolean("priorityOverride"),
        )
    }
}

fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

data class PendingAction(
    val requestId: String,
    val uid: String,
    val reminderId: String,
    val occurrenceId: String,
    val action: String,
    val snoozeMinutes: Int?,
    val snoozeAt: Long?,
    val localDelivery: Boolean,
)

data class ProposalReview(
    val title: String?,
    val notes: String?,
    val recurrence: String?,
    val endsAt: Long?,
    val recipients: List<String>,
    val actionDetail: String?,
    val whenAt: Long? = null,
    val startsAt: Long? = null,
    val repeatTime: String? = null,
    val scheduleZone: String? = null,
    val followUpMinutes: Int? = null,
) {
    companion object {
        internal fun fromMap(map: Map<*, *>) = ProposalReview(
            title = map["title"] as? String,
            notes = map["notes"] as? String,
            recurrence = map["recurrence"] as? String,
            endsAt = (map["endsAt"] as? Number)?.toLong(),
            recipients = (map["recipients"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            actionDetail = map["actionDetail"] as? String,
            whenAt = (map["whenAt"] as? Number)?.toLong(),
            startsAt = (map["startsAt"] as? Number)?.toLong(),
            repeatTime = map["repeatTime"] as? String,
            scheduleZone = map["scheduleZone"] as? String,
            followUpMinutes = (map["followUpMinutes"] as? Number)?.toInt(),
        )
    }
}

data class Proposal(
    val id: String,
    val action: String,
    val readback: String,
    val preview: List<Long>,
    val targetId: String?,
    val baseVersion: Long?,
    val priority: Boolean = false,
    val review: ProposalReview? = null,
) {
    companion object {
        fun fromMap(map: Map<*, *>): Proposal = Proposal(
            review = readReview(map),
            priority = map["priority"] as? Boolean ?: false,
            id = map["id"] as String,
            action = map["action"] as String,
            readback = map["readback"] as String,
            preview = (map["preview"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList(),
            targetId = map["targetId"] as? String,
            baseVersion = (map["baseVersion"] as? Number)?.toLong(),
        )

        private fun readReview(map: Map<*, *>): ProposalReview? {
            val review = (map["review"] as? Map<*, *>)?.let { ProposalReview.fromMap(it) }
            if (map["action"] !in listOf("create", "edit")) return review
            val rule = map["rule"] as? Map<*, *>
            val kind = rule?.get("kind")
            val recurrence = when (kind) {
                "interval" -> (rule["everyMinutes"] as? Number)?.let { "Every ${it.toInt()} minutes" }
                "daily" -> "Daily"
                "weekly" -> "Weekly"
                "monthly", "monthly_weekday", "monthly_weekly", "monthly_range" -> "Monthly"
                "yearly" -> "Yearly"
                else -> null
            }
            val recipients = (map["readback"] as? String)?.substringAfter(". Confirm? Shared with ", "")
                ?.substringBefore(". Everyone can edit;")?.takeIf { it.isNotBlank() }
            val base = review ?: ProposalReview(map["title"] as? String ?: return null,
                map["notes"] as? String, recurrence, parseDate(rule?.get("endAt")), listOfNotNull(recipients), null)
            val firstPreview = ((map["preview"] as? List<*>)?.firstOrNull() as? Number)?.toLong()
            val start = parseDate(rule?.get("startAt"))
            val zone = rule?.get("timeZone") as? String
            val hour = (rule?.get("hour") as? Number)?.toInt()
            val minute = (rule?.get("minute") as? Number)?.toInt()
            val centralRepeatTime = if (zone == "America/Chicago" && hour != null && minute != null) {
                val displayHour = (hour % 12).takeUnless { it == 0 } ?: 12
                "$displayHour:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"} Central"
            } else null
            return base.copy(
                whenAt = base.whenAt ?: if (kind == "once" || base.recurrence == "Once") parseDate(rule?.get("at")) ?: firstPreview else null,
                startsAt = base.startsAt ?: start?.let {
                    runCatching { Schedule.next(JSONObject(rule.orEmpty()), it - 1) }.getOrNull()
                },
                repeatTime = base.repeatTime ?: centralRepeatTime,
                scheduleZone = base.scheduleZone ?: zone?.takeUnless { it == "America/Chicago" },
                followUpMinutes = base.followUpMinutes ?: (map["repeatUntilDoneMinutes"] as? Number)?.toInt(),
            )
        }

        private fun parseDate(value: Any?): Long? = (value as? String)?.let {
            runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}
