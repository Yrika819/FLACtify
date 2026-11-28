package com.flactify.viewmodel

import org.json.JSONObject

/** Codec for SharedPreferences. Portable backup JSON is owned by LibraryBackupCodec. */
internal object LibraryStatsCodec {
    data class ReadResult(
        val stats: Map<String, LibraryManager.PlayStats>,
        /** False means some stored bytes could not be represented without guessing. */
        val canRewrite: Boolean
    )

    fun encode(stats: Map<String, LibraryManager.PlayStats>): String = JSONObject().apply {
        stats.forEach { (uri, value) ->
            require(uri.isNotEmpty())
            require(value.playCount >= 0 && value.skipCount >= 0 && value.lastPlayed >= 0L)
            put(uri, JSONObject()
                .put("pc", value.playCount)
                .put("sc", value.skipCount)
                .put("lp", value.lastPlayed))
        }
    }.toString()

    /** Reads v2.5.2 internal rows and recovers the accidental backup-shaped rows. */
    fun decode(json: String): ReadResult = runCatching {
        val root = JSONObject(json)
        var canRewrite = true
        val stats = buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val uri = keys.next()
                val value = runCatching { decodeRow(root.getJSONObject(uri)) }.getOrNull()
                if (value == null) canRewrite = false else put(uri, value)
            }
        }
        ReadResult(stats, canRewrite)
    }.getOrElse { ReadResult(emptyMap(), canRewrite = false) }

    private fun decodeRow(row: JSONObject): LibraryManager.PlayStats? {
        val internalKeys = setOf("pc", "sc", "lp")
        val backupKeys = setOf("playCount", "skipCount", "lastPlayed")
        val keys = row.keys().asSequence().toSet()
        val hasInternal = internalKeys.all(keys::contains)
        val hasBackup = backupKeys.all(keys::contains)
        if (!hasInternal && !hasBackup) return null
        if (keys != (if (hasInternal && hasBackup) internalKeys + backupKeys else if (hasInternal) internalKeys else backupKeys)) {
            return null
        }

        val internal = if (hasInternal) readTriple(row, "pc", "sc", "lp") else null
        val backup = if (hasBackup) readTriple(row, "playCount", "skipCount", "lastPlayed") else null
        if (hasInternal && internal == null || hasBackup && backup == null) return null
        if (internal != null && backup != null && internal != backup) return null
        val resolved = internal ?: backup ?: return null
        return LibraryManager.PlayStats(resolved.first, resolved.second, resolved.third)
    }

    private fun readTriple(row: JSONObject, playsKey: String, skipsKey: String, lastKey: String): Triple<Int, Int, Long>? {
        val plays = nonNegativeLong(row, playsKey)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        val skips = nonNegativeLong(row, skipsKey)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        val last = nonNegativeLong(row, lastKey) ?: return null
        return Triple(plays, skips, last)
    }

    private fun nonNegativeLong(row: JSONObject, key: String): Long? {
        val raw = row.opt(key) as? Number ?: return null
        val value = raw.toLong()
        if (value < 0L || raw.toDouble() != value.toDouble()) return null
        return value
    }
}
