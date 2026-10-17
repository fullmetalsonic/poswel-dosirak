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

class AppStore(context: Context, databaseName: String = "reservations.db") : SQLiteOpenHelper(context.applicationContext, databaseName, null, 2) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE exceptions (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE executions (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
        createRebookingTables(db)
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRebookingTables(db)
    }
    private fun createRebookingTables(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS execution_archive (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, json TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rebook_state (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
    }

    @Synchronized fun hasSavedData(): Boolean = listOf("config", "exceptions", "executions").any { table ->
        readableDatabase.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
    }

    @Synchronized fun loadSettings(): AppSettings = readableDatabase.rawQuery("SELECT json FROM config WHERE id=1", null).use {
        if (it.moveToFirst()) WorkTypes.normalized(AppJson.gson.fromJson(it.getString(0), AppSettings::class.java)).normalizedFailureSound()
        else AppSettings(notifications = NotificationPreferences())
    }
    @Synchronized fun saveSettings(value: AppSettings) {
        require(ReservationLimits.orderTimeAllowed(value.orderTime)) { ReservationLimits.ORDER_TIME_ERROR }
        require(WorkTypes.validate(value.workTypes) == null) { WorkTypes.validate(value.workTypes).orEmpty() }
        val cv = ContentValues().apply { put("id", 1); put("json", AppJson.gson.toJson(value.normalizedFailureSound())) }
        check(writableDatabase.insertWithOnConflict("config", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "설정 저장 실패" }
    }
    @Synchronized fun updateSettings(change: (AppSettings) -> AppSettings) {
        saveSettings(change(loadSettings()))
    }
    @Synchronized fun loadOverrides(): Map<LocalDate, DateOverride> = readableDatabase.rawQuery("SELECT json FROM exceptions", null).use { cursor ->
        buildMap { while (cursor.moveToNext()) { val v = AppJson.gson.fromJson(cursor.getString(0), DateOverride::class.java); put(v.date, v) } }
    }
    @Synchronized fun saveOverride(value: DateOverride) {
        require(value.time == null || ReservationLimits.orderTimeAllowed(value.time)) { ReservationLimits.ORDER_TIME_ERROR }
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

    @Synchronized fun saveDateEdits(
        edits: Map<LocalDate, DateOverride?>,
        expectedGeneration: Long,
        expectedOriginals: Map<LocalDate, DateOverride?>,
        learnedMealDefaults: Map<String, Boolean> = emptyMap()
    ): Boolean {
        require(edits.size <= 5000 && edits.keys == expectedOriginals.keys) { "날짜 선택을 다시 확인해 주세요." }
        val database = writableDatabase
        database.beginTransaction()
        try {
            val latest = loadSettings()
            val originals = loadOverrides()
            if (latest.generation != expectedGeneration || edits.keys.any { originals[it] != expectedOriginals[it] }) throw CalendarEditConflict()
            edits.forEach { (date, value) ->
                require(value == null || value.date == date) { "날짜가 일치하지 않습니다." }
                if (value != null) {
                    require(value.quantity == null || value.quantity in 1..5) { "수량은 1~5개입니다." }
                    require(value.time == null || ReservationLimits.orderTimeAllowed(value.time)) { ReservationLimits.ORDER_TIME_ERROR }
                    val workId = value.workTypeId
                    require(workId == null || value.policy != DatePolicy.AUTO || value == originals[date]) { "근무를 변경할 때 도시락 예약 여부를 선택해 주세요." }
                    if (workId != null && workId != originals[date]?.workTypeId) {
                        val definition = WorkTypes.definition(workId, latest)
                        require(definition != null && !definition.archived) { "사용할 수 없는 근무입니다. 다시 선택해 주세요." }
                    }
                }
            }
            require(learnedMealDefaults.keys.all { id -> latest.workTypes.any { it.id == id && !it.archived } }) { "근무 종류가 변경되었습니다." }
            val types = latest.workTypes.map { definition ->
                learnedMealDefaults[definition.id]?.let { definition.copy(mealDefault = it) } ?: definition
            }
            val changedDates = edits.filter { (date, value) -> originals[date] != value }
            if (changedDates.isEmpty() && types == latest.workTypes) return false
            changedDates.forEach { (date, value) -> if (value == null) deleteOverride(date) else saveOverride(value) }
            saveSettings(latest.copy(workTypes = types, generation = latest.generation + 1))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized fun saveWorkTypes(types: List<WorkTypeDefinition>, expectedGeneration: Long): Boolean {
        require(WorkTypes.validate(types) == null) { WorkTypes.validate(types).orEmpty() }
        val database = writableDatabase
        database.beginTransaction()
        try {
            val latest = loadSettings()
            if (latest.generation != expectedGeneration) throw CalendarEditConflict()
            val removed = latest.workTypes.map { it.id }.toSet() - types.map { it.id }.toSet()
            require(loadOverrides().values.none { it.workTypeId in removed }) { "사용한 근무는 삭제 대신 보관해 주세요." }
            if (latest.workTypes == types) return false
            saveSettings(latest.copy(workTypes = types, generation = latest.generation + 1))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
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
    private fun storedRecords(): List<ExecutionRecord> = readableDatabase.rawQuery("SELECT json FROM executions ORDER BY date DESC", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(AppJson.gson.fromJson(cursor.getString(0), ExecutionRecord::class.java)) }
    }
    @Synchronized fun loadRecords(): List<ExecutionRecord> {
        val settings = loadSettings()
        return storedRecords().map { record ->
            if (record.status == ExecutionStatus.PLANNED && record.stage == "REBOOK_ARMED" &&
                (record.generation != settings.generation || record.accountGeneration != settings.accountGeneration)) {
                record.copy(status = ExecutionStatus.NEEDS_CHECK, stage = "REBOOK_STALE",
                    message = "설정이 변경되어 다시 예약을 확인해야 합니다.")
            } else record
        }
    }
    @Synchronized fun record(value: ExecutionRecord) {
        val previous = storedRecords().firstOrNull { it.date == value.date }
        if (previous != null && previous != value && (previous.submissionPossible || previous.status == ExecutionStatus.COMPLETED)) archive(previous)
        val cv = ContentValues().apply { put("date", value.date.toString()); put("json", AppJson.gson.toJson(value)) }
        check(writableDatabase.insertWithOnConflict("executions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "실행 의도 저장 실패" }
    }

    private fun archive(value: ExecutionRecord) {
        val json = AppJson.gson.toJson(value)
        val cv = ContentValues().apply { put("date", value.date.toString()); put("json", json) }
        val inserted = writableDatabase.insertWithOnConflict("execution_archive", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L) {
            val exactDuplicate = readableDatabase.rawQuery("SELECT 1 FROM execution_archive WHERE date=? AND json=? LIMIT 1",
                arrayOf(value.date.toString(), json)).use { it.moveToFirst() }
            check(exactDuplicate) { "이전 실행 기록을 보존하지 못해 변경을 중단했습니다." }
        }
    }
    @Synchronized fun loadArchivedRecords(): List<ExecutionRecord> = readableDatabase.rawQuery("SELECT json FROM execution_archive ORDER BY id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(AppJson.gson.fromJson(cursor.getString(0), ExecutionRecord::class.java)) }
    }
    @Synchronized fun rebookingState(date: LocalDate): RebookingState? = readableDatabase.rawQuery("SELECT json FROM rebook_state WHERE date=?", arrayOf(date.toString())).use {
        if (it.moveToFirst()) AppJson.gson.fromJson(it.getString(0), RebookingState::class.java) else null
    }
    @Synchronized fun saveRebookingState(value: RebookingState) {
        val previous = rebookingState(value.date)
        if (previous != null && previous.baseline.serverOrderId != value.baseline.serverOrderId) archive(previous.baseline)
        val cv = ContentValues().apply { put("date", value.date.toString()); put("json", AppJson.gson.toJson(value)) }
        check(writableDatabase.insertWithOnConflict("rebook_state", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    @Synchronized fun armRebooking(expected: RebookingState, replacement: RebookingState, override: DateOverride,
        record: ExecutionRecord, expectedGeneration: Long, expectedAccountGeneration: Long) {
        val permit = requireNotNull(replacement.permit)
        require(expectedGeneration < Long.MAX_VALUE && replacement.date == expected.date && replacement.account == expected.account &&
            replacement.accountGeneration == expected.accountGeneration && replacement.baseline == expected.baseline &&
            replacement.cancelled == expected.cancelled && replacement.proof == expected.proof && replacement.proof != null &&
            !permit.consumed && permit.id.isNotBlank() && permit.generation == expectedGeneration + 1 &&
            override.date == expected.date && override.policy == DatePolicy.MANUAL && override.quantity == permit.quantity &&
            override.time == permit.time && permit.quantity in 1..5 && ReservationLimits.orderTimeAllowed(permit.time) &&
            record.date == expected.date && record.quantity == permit.quantity && record.generation == permit.generation &&
            record.accountGeneration == expectedAccountGeneration && !record.submissionPossible &&
            record.status == ExecutionStatus.PLANNED && record.stage == "REBOOK_ARMED") { "다시 예약 정보가 일치하지 않습니다." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val settings = loadSettings()
            check(settings.generation == expectedGeneration && settings.accountGeneration == expectedAccountGeneration && rebookingState(expected.date) == expected) { "예약 또는 계정이 변경되었습니다. 다시 조회해 주세요." }
            check(expected.proof != null && expected.proofAt != null && expected.permit == null) { "이미 다시 예약했거나 취소 확인이 필요합니다." }
            check(Rebooking.ledgerAllowsArm(loadRecords().firstOrNull { it.date == expected.date }, expected.date, settings.accountGeneration)) {
                "이전 신청 결과가 불명확하여 다시 예약할 수 없습니다."
            }
            loadRecords().firstOrNull { it.date == expected.date }?.let(::archive)
            archive(expected.baseline)
            saveOverride(override)
            saveSettings(settings.copy(generation = expectedGeneration + 1))
            saveRebookingState(replacement)
            record(record)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun consumeRebookingAndRecord(date: LocalDate, record: ExecutionRecord): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val state = rebookingState(date) ?: return false
            val settings = loadSettings()
            val plan = ScheduleCalculator.planFor(date, settings, loadOverrides()[date])
            if (!Rebooking.permitMatches(state, settings, plan, state.account) || record.stage != "TEMP_INTENT" ||
                record.generation != settings.generation || record.accountGeneration != settings.accountGeneration) return false
            saveRebookingState(state.copy(permit = requireNotNull(state.permit).copy(consumed = true)))
            record(record)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun exportPlanBackup(): String {
        val root = JsonObject().apply {
            addProperty("schema", 2)
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
        val schema = root.get("schema")?.asInt
        require(schema == 1 || schema == 2) { "지원하지 않는 백업 형식입니다." }
        val config = root.getAsJsonObject("settings")
        if (schema == 1) config.remove("workTypes")
        if (schema == 2) {
            require(config.get("workTypes")?.isJsonArray == true) { "근무 종류가 없는 백업입니다." }
            val definitions = config.getAsJsonArray("workTypes")
            require(definitions.size() <= WorkTypes.builtIns.size + WorkTypes.MAX_CUSTOM_TYPES) { "근무 종류가 너무 많습니다." }
            definitions.forEach { element ->
                require(element.isJsonObject) { "근무 종류가 올바르지 않습니다." }
                val definition = element.asJsonObject
                listOf("id", "name", "calendarLabel").forEach { key ->
                    require(definition.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true) { "근무 종류가 올바르지 않습니다." }
                }
                listOf("favorite", "archived").forEach { key ->
                    require(definition.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true) { "근무 종류가 올바르지 않습니다." }
                }
                listOf("color", "sortOrder").forEach { key ->
                    require(definition.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } == true) { "근무 종류가 올바르지 않습니다." }
                    val number = definition.get(key).asBigDecimal.toBigIntegerExact()
                    val minimum = java.math.BigInteger.valueOf(if (key == "color") 0xFF000000L else 0L)
                    val maximum = java.math.BigInteger.valueOf(if (key == "color") 0xFFFFFFFFL else 10000L)
                    require(number >= minimum && number <= maximum) { "근무 종류가 올바르지 않습니다." }
                }
                require(definition.get("mealDefault").let {
                    it == null || it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
                }) { "도시락 기본값이 올바르지 않습니다." }
            }
        }
        require(config.get("shiftType")?.asString in ShiftType.entries.map { it.name })
        require(config.get("orderTime") != null && !config.get("orderTime").isJsonNull)
        require(config.get("patternAnchor") != null && !config.get("patternAnchor").isJsonNull)
        LocalTime.parse(config.get("orderTime").asString)
        LocalDate.parse(config.get("patternAnchor").asString)
        require(config.get("weekdays")?.isJsonArray == true)
        config.getAsJsonArray("weekdays").forEach { java.time.DayOfWeek.valueOf(it.asString) }
        val parsed = AppJson.gson.fromJson(root.get("settings"), AppSettings::class.java)
        val s = if (schema == 1) WorkTypes.normalized(parsed) else parsed
        require(WorkTypes.validate(s.workTypes) == null) { "근무 종류가 올바르지 않습니다." }
        require(s.defaultQuantity in 1..5 && s.retryCount in 0..10 && s.retryIntervalSeconds in 1..300)
        require(ReservationLimits.orderTimeAllowed(s.orderTime)) { ReservationLimits.ORDER_TIME_ERROR }
        val values = root.getAsJsonArray("overrides").map {
            val item = it.asJsonObject
            if (schema == 1) item.remove("workTypeId")
            if (schema == 2 && item.has("workTypeId")) require(item.get("workTypeId").let {
                it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isString)
            }) { "근무 종류가 올바르지 않습니다." }
            LocalDate.parse(item.get("date").asString)
            require(item.get("policy")?.asString in DatePolicy.entries.map { policy -> policy.name })
            require(item.get("reason")?.asString in ReservationReason.entries.map { reason -> reason.name })
            AppJson.gson.fromJson(it, DateOverride::class.java)
        }
        require(values.size <= 5000 && values.map { it.date }.distinct().size == values.size)
        require(values.all { it.quantity == null || it.quantity in 1..5 })
        require(values.all { it.time == null || ReservationLimits.orderTimeAllowed(it.time) }) { ReservationLimits.ORDER_TIME_ERROR }
        require(values.all { it.workTypeId == null || s.workTypes.any { definition -> definition.id == it.workTypeId } }) { "알 수 없는 근무가 포함되어 있습니다." }
        require(values.all { it.workTypeId == null || it.policy != DatePolicy.AUTO }) { "근무와 도시락 예약 설정이 일치하지 않습니다." }
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
