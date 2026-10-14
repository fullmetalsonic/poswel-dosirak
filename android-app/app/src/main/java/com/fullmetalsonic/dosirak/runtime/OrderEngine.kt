package com.fullmetalsonic.dosirak.runtime

import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.data.CredentialVault
import com.fullmetalsonic.dosirak.domain.ExecutionRecord
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import com.fullmetalsonic.dosirak.diagnostics.*
import com.fullmetalsonic.dosirak.platform.OrderNotifier
import com.fullmetalsonic.dosirak.site.SiteGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Clock
import java.time.ZoneId

class OrderEngine(
    store: AppStore,
    vault: CredentialVault,
    gateway: SiteGateway,
    private val notifier: OrderNotifier,
    private val onChanged: () -> Unit = {},
    clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE
) {
    private val activeLock = Any()
    private var activeCount = 0
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    private val coordinator = PurchaseCoordinator(object : PurchaseStorage {
        override fun settings() = store.loadSettings()
        override fun overrides() = store.loadOverrides()
        override fun records() = store.loadRecords()
        override fun saveSettings(settings: com.fullmetalsonic.dosirak.domain.AppSettings) = store.saveSettings(settings)
        override fun updateSettings(change: (com.fullmetalsonic.dosirak.domain.AppSettings) -> com.fullmetalsonic.dosirak.domain.AppSettings) = store.updateSettings(change)
        override fun record(record: ExecutionRecord) = store.record(record)
        override fun credentials() = vault.load()
    }, gateway, clock, onChanged = { record ->
        onChanged()
        if (record.status == ExecutionStatus.RUNNING) notifier.progress(record.date, record.message)
        else notifier.result(record)
    }, diagnostics = diagnostics)

    suspend fun execute(date: LocalDate, manual: Boolean = false, acceptedPriceRisk: Boolean = false,
        expectedGeneration: Long? = null, expectedAccountGeneration: Long? = null): ExecutionRecord =
        withContext(Dispatchers.IO) {
            observeRun(date, DiagnosticStage.CHECKOUT) {
                whileActive { coordinator.execute(date, manual, acceptedPriceRisk, expectedGeneration, expectedAccountGeneration) }
            }
        }

    suspend fun refreshOrders(date: LocalDate): ExecutionRecord = withContext(Dispatchers.IO) {
        observeRun(date, DiagnosticStage.HISTORY) { whileActive { coordinator.refreshOrders(date) } }
    }

    suspend fun prepareAndExecute(date: LocalDate, expectedGeneration: Long): ExecutionRecord = withContext(Dispatchers.IO) {
        observeRun(date, DiagnosticStage.PREPARATION) { whileActive {
            notifier.progress(date, "사이트 시각을 확인하고 신청 시각을 기다리고 있습니다.")
            coordinator.prepareAndExecute(date, expectedGeneration)
        } }
    }

    private suspend fun observeRun(date: LocalDate, stage: DiagnosticStage, action: suspend () -> ExecutionRecord): ExecutionRecord =
        withContext(DiagnosticRun.contextElement(DiagnosticRun.currentId() ?: DiagnosticRun.newId())) {
            diagnostics.safeRecord(DiagnosticEvent(DiagnosticEventType.RUN_STARTED, stage, DiagnosticResult.STARTED, date = date))
            try {
                action().also { record ->
                    diagnostics.safeRecord(DiagnosticEvent(DiagnosticEventType.RUN_FINISHED, stage,
                        when (record.status) {
                            ExecutionStatus.COMPLETED -> DiagnosticResult.SUCCEEDED
                            ExecutionStatus.FAILED -> DiagnosticResult.FAILED
                            ExecutionStatus.NEEDS_CHECK -> DiagnosticResult.UNKNOWN
                            ExecutionStatus.SKIPPED -> DiagnosticResult.SKIPPED
                            else -> DiagnosticResult.IN_FLIGHT
                        }, DiagnosticCode.fromSiteCode(record.stage), date = date, quantity = record.quantity,
                        amount = record.amount, generation = record.generation, accountGeneration = record.accountGeneration,
                        submissionPossible = record.submissionPossible))
                }
            } catch (error: Exception) {
                diagnostics.safeRecord(DiagnosticEvent(DiagnosticEventType.RUN_FINISHED, stage,
                    if (error is kotlinx.coroutines.CancellationException) DiagnosticResult.CANCELLED else DiagnosticResult.FAILED,
                    date = date))
                throw error
            }
        }

    private suspend fun <T> whileActive(action: suspend () -> T): T {
        synchronized(activeLock) {
            activeCount++
            mutableActive.value = true
        }
        runCatching { onChanged() }
        return try { action() } finally {
            synchronized(activeLock) {
                activeCount--
                mutableActive.value = activeCount > 0
                if (activeCount == 0) runCatching { notifier.clearProgress() }
            }
            runCatching { onChanged() }
        }
    }
}
