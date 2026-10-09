package com.fullmetalsonic.dosirak.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import com.fullmetalsonic.dosirak.domain.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object AppJson {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { v, _, _ -> JsonPrimitive(v.toString()) })
        .registerTypeAdapter(LocalDate::class.java, JsonDeserializer<LocalDate> { v, _, _ -> LocalDate.parse(v.asString) })
        .registerTypeAdapter(LocalTime::class.java, JsonSerializer<LocalTime> { v, _, _ -> JsonPrimitive(v.toString()) })
        .registerTypeAdapter(LocalTime::class.java, JsonDeserializer<LocalTime> { v, _, _ -> LocalTime.parse(v.asString) })
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { v, _, _ -> JsonPrimitive(v.toString()) })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer<Instant> { v, _, _ -> Instant.parse(v.asString) })
        .create()
}

class AppStore(context: Context, databaseName: String = "reservations.db") : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE exceptions (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE executions (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun loadSettings(): AppSettings = readableDatabase.rawQuery("SELECT json FROM config WHERE id=1", null).use {
        if (it.moveToFirst()) AppJson.gson.fromJson(it.getString(0), AppSettings::class.java) else AppSettings()
    }
    @Synchronized fun saveSettings(value: AppSettings) {
        val cv = ContentValues().apply { put("id", 1); put("json", AppJson.gson.toJson(value)) }
        check(writableDatabase.insertWithOnConflict("config", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "설정 저장 실패" }
    }
    @Synchronized fun updateSettings(change: (AppSettings) -> AppSettings) {
        saveSettings(change(loadSettings()))
    }
    @Synchronized fun loadOverrides(): Map<LocalDate, DateOverride> = readableDatabase.rawQuery("SELECT json FROM exceptions", null).use { cursor ->
        buildMap { while (cursor.moveToNext()) { val v = AppJson.gson.fromJson(cursor.getString(0), DateOverride::class.java); put(v.date, v) } }
    }
    @Synchronized fun saveOverride(value: DateOverride) {
        val cv = ContentValues().apply { put("date", value.date.toString()); put("json", AppJson.gson.toJson(value)) }
        check(writableDatabase.insertWithOnConflict("exceptions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "날짜 저장 실패" }
    }
    @Synchronized fun deleteOverride(date: LocalDate) { writableDatabase.delete("exceptions", "date=?", arrayOf(date.toString())) }
    @Synchronized fun saveOverrideAndAdvanceGeneration(value: DateOverride) {
        changeOverrideAndAdvanceGeneration { saveOverride(value) }
    }
    @Synchronized fun deleteOverrideAndAdvanceGeneration(date: LocalDate) {
        changeOverrideAndAdvanceGeneration { deleteOverride(date) }
    }
    @Synchronized fun excludeOverrideAndAdvanceGeneration(value: DateOverride) {
        changeOverrideAndAdvanceGeneration {
            val latest = loadOverrides()[value.date] ?: value
            saveOverride(latest.copy(policy = DatePolicy.EXCLUDE))
        }
    }
    private fun changeOverrideAndAdvanceGeneration(change: () -> Unit) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            change()
            val latest = loadSettings()
            saveSettings(latest.copy(generation = latest.generation + 1))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    @Synchronized fun loadRecords(): List<ExecutionRecord> = readableDatabase.rawQuery("SELECT json FROM executions ORDER BY date DESC", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(AppJson.gson.fromJson(cursor.getString(0), ExecutionRecord::class.java)) }
    }
    @Synchronized fun record(value: ExecutionRecord) {
        val cv = ContentValues().apply { put("date", value.date.toString()); put("json", AppJson.gson.toJson(value)) }
        check(writableDatabase.insertWithOnConflict("executions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "실행 의도 저장 실패" }
    }

    @Synchronized fun exportPlanBackup(): String {
        val root = JsonObject().apply {
            addProperty("schema", 1)
            // Credentials, cookies, live authorization and execution history never leave CE storage.
            add("settings", AppJson.gson.toJsonTree(loadSettings().copy(masterEnabled = false, liveScope = LiveScope.NONE,
                liveTestDate = null, displayPriceRiskAccepted = false, patternConfirmed = false,
                accountGeneration = 0, verifiedLiveAccountGeneration = null, liveBlockedReason = null, generation = 0)))
            add("overrides", AppJson.gson.toJsonTree(loadOverrides().values.toList()))
        }
        return AppJson.gson.toJson(root)
    }
    @Synchronized fun importPlanBackup(text: String) {
        require(text.length <= 1_000_000) { "백업 파일이 너무 큽니다." }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("schema")?.asInt == 1) { "지원하지 않는 백업 형식입니다." }
        val config = root.getAsJsonObject("settings")
        require(config.get("shiftType")?.asString in ShiftType.entries.map { it.name })
        require(config.get("orderTime") != null && !config.get("orderTime").isJsonNull)
        require(config.get("patternAnchor") != null && !config.get("patternAnchor").isJsonNull)
        LocalTime.parse(config.get("orderTime").asString)
        LocalDate.parse(config.get("patternAnchor").asString)
        require(config.get("weekdays")?.isJsonArray == true)
        config.getAsJsonArray("weekdays").forEach { java.time.DayOfWeek.valueOf(it.asString) }
        val s = AppJson.gson.fromJson(root.get("settings"), AppSettings::class.java)
        require(s.defaultQuantity in 1..5 && s.retryCount in 0..10 && s.retryIntervalSeconds in 1..300)
        val values = root.getAsJsonArray("overrides").map {
            val item = it.asJsonObject
            LocalDate.parse(item.get("date").asString)
            require(item.get("policy")?.asString in DatePolicy.entries.map { policy -> policy.name })
            require(item.get("reason")?.asString in ReservationReason.entries.map { reason -> reason.name })
            AppJson.gson.fromJson(it, DateOverride::class.java)
        }
        require(values.size <= 5000 && values.map { it.date }.distinct().size == values.size)
        require(values.all { it.quantity == null || it.quantity in 1..5 })
        writableDatabase.beginTransaction()
        try {
            val previous = loadSettings()
            saveSettings(s.copy(masterEnabled = false, liveScope = LiveScope.NONE, liveTestDate = null,
                displayPriceRiskAccepted = false, patternConfirmed = false,
                accountGeneration = previous.accountGeneration, verifiedLiveAccountGeneration = null,
                liveBlockedReason = previous.liveBlockedReason, generation = previous.generation + 1))
            writableDatabase.delete("exceptions", null, null)
            values.forEach(::saveOverride)
            // Preserve the local submission ledger so a restored plan cannot replay an old request.
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}
