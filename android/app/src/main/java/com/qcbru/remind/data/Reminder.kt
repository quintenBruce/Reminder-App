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

data class Proposal(
    val id: String,
    val action: String,
    val readback: String,
    val preview: List<Long>,
    val targetId: String?,
    val baseVersion: Long?,
    val priority: Boolean = false,
) {
    companion object {
        fun fromMap(map: Map<*, *>): Proposal = Proposal(
            priority = map["priority"] as? Boolean ?: false,
            id = map["id"] as String,
            action = map["action"] as String,
            readback = map["readback"] as String,
            preview = (map["preview"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList(),
            targetId = map["targetId"] as? String,
            baseVersion = (map["baseVersion"] as? Number)?.toLong(),
        )
    }
}
