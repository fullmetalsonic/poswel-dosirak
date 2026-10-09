package com.fullmetalsonic.dosirak.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ActivationRulesTest {
    @Test fun orderTimeBoundariesApplyEvenWhenAmountLimitsAreDisabled() {
        val cases = listOf(
            LocalTime.of(5, 59, 59) to false,
            LocalTime.of(6, 0, 0) to true,
            LocalTime.of(7, 59, 59) to true,
            LocalTime.of(8, 0, 0) to false,
            LocalTime.of(12, 0, 0) to false
        )
        cases.forEach { (time, allowed) ->
            val value = AppSettings(orderTime = time, limitEnabled = false)
            assertEquals(time.toString(), allowed, ReservationLimits.orderTimeAllowed(time))
            assertEquals(time.toString(), if (allowed) null else ReservationLimits.ORDER_TIME_ERROR,
                ReservationLimits.validate(value))
            if (allowed) {
                assertEquals(time, ActivationRules.settingsForSave(value, value, value.generation).orderTime)
            } else {
                assertThrows(ActivationRejected::class.java) {
                    ActivationRules.settingsForSave(value, value, value.generation)
                }
            }
        }
    }

    private val date = LocalDate.of(2026, 10, 9)
    private val now = Instant.parse("2026-10-08T20:59:00Z")
    private val current = AppSettings(generation = 12, accountGeneration = 4,
        verifiedLiveAccountGeneration = 4, liveScope = LiveScope.SINGLE_DATE,
        liveTestDate = date.plusDays(7), displayPriceRiskAccepted = true)
    private val draft = AppSettings(dayAutoEnabled = true, shiftType = ShiftType.REGULAR,
        patternAnchor = date.minusDays(20), patternConfirmed = true,
        weekdays = setOf(DayOfWeek.FRIDAY), defaultQuantity = 3, orderTime = LocalTime.of(6, 15),
        unitLimit = 8_000, orderLimit = 24_000, retryCount = 2, retryIntervalSeconds = 11,
        preparationAlert = true, useStoredCredentials = false, mediaAlarmEnabled = true,
        mediaVolumePercent = 35, mediaDurationSeconds = 7, manufacturerSettingsConfirmed = true,
        generation = 999, accountGeneration = 999, verifiedLiveAccountGeneration = 999)
    private fun manual(day: LocalDate = date, time: LocalTime = LocalTime.of(6, 0)) =
        DateOverride(day, DatePolicy.MANUAL, 2, time, ReservationReason.SUPPORT)
    private fun activate(base: AppSettings = current, edited: AppSettings = draft,
        expectedGeneration: Long = current.generation, accountGeneration: Long = current.accountGeneration,
        accountLabel: String = "ab****", userId: String? = "abcdef", consent: Boolean = true,
        overrides: Map<LocalDate, DateOverride> = mapOf(date to manual()),
        records: List<ExecutionRecord> = emptyList(), instant: Instant = now) =
        ActivationRules.recurringActivation(base, edited, expectedGeneration, accountGeneration,
            accountLabel, userId, consent, overrides, records, instant)

    @Test fun activationCopiesConfirmedDraftAndPreservesInternalAccountMetadata() {
        val enabled = activate()
        val expected = draft.copy(masterEnabled = true, liveScope = LiveScope.RECURRING,
            liveTestDate = null, displayPriceRiskAccepted = true, accountGeneration = 4,
            verifiedLiveAccountGeneration = 4, liveBlockedReason = null, generation = 13,
            mediaAlarmEnabled = false, preparationAlert = current.preparationAlert,
            notifications = draft.effectiveNotifications())
        assertEquals(expected, enabled)
        assertFalse(current.masterEnabled)
        assertEquals(12L, current.generation)
    }

    @Test fun explicitConsentIsRequiredWithoutAnyVerifiedLiveOrderPrerequisite() {
        assertThrows(ActivationRejected::class.java) { activate(consent = false) }
        val enabled = activate(base = current.copy(verifiedLiveAccountGeneration = null))
        assertTrue(enabled.masterEnabled)
        assertNull(enabled.verifiedLiveAccountGeneration)
    }

    @Test fun changedGenerationRejectsSnapshot() {
        assertThrows(ActivationRejected::class.java) { activate(base = current.copy(generation = 13)) }
    }

    @Test fun changedAccountGenerationRejectsEvenWhenMaskedLabelsCollide() {
        assertThrows(ActivationRejected::class.java) {
            activate(base = current.copy(accountGeneration = 5), userId = "abxxxx")
        }
    }

    @Test fun differentOrMissingAccountRejectsConfirmation() {
        assertThrows(ActivationRejected::class.java) { activate(accountLabel = "zz****") }
        assertThrows(ActivationRejected::class.java) { activate(accountLabel = "") }
        assertThrows(ActivationRejected::class.java) { activate(userId = null) }
    }

    @Test fun invalidLimitsAndQuantityCannotBeSavedAndArmed() {
        assertThrows(ActivationRejected::class.java) { activate(edited = draft.copy(unitLimit = null)) }
        assertThrows(ActivationRejected::class.java) { activate(edited = draft.copy(orderLimit = 0)) }
        assertThrows(ActivationRejected::class.java) { activate(edited = draft.copy(defaultQuantity = 6)) }
        assertThrows(ActivationRejected::class.java) { activate(edited = draft.copy(retryIntervalSeconds = 0)) }
    }

    @Test fun existingBlockCannotBeClearedByCleanDraft() {
        assertThrows(ActivationRejected::class.java) {
            activate(base = current.copy(liveBlockedReason = "PRICE_MISMATCH: review needed"))
        }
    }

    @Test fun ordinarySaveRequiresExpectedGenerationAndPreservesAuthorization() {
        assertThrows(ActivationRejected::class.java) {
            ActivationRules.settingsForSave(current, draft, expectedGeneration = 11)
        }
        val saved = ActivationRules.settingsForSave(current, draft, expectedGeneration = 12)
        assertFalse(saved.masterEnabled)
        assertEquals(current.liveScope, saved.liveScope)
        assertEquals(current.liveTestDate, saved.liveTestDate)
        assertEquals(current.accountGeneration, saved.accountGeneration)
        assertEquals(current.verifiedLiveAccountGeneration, saved.verifiedLiveAccountGeneration)
        assertEquals(current.displayPriceRiskAccepted, saved.displayPriceRiskAccepted)
        assertEquals(draft.weekdays, saved.weekdays)
        assertFalse(saved.mediaAlarmEnabled)
        assertEquals(13L, saved.generation)
    }

    @Test fun ordinarySaveCannotGrantMissingPurchaseConsent() {
        assertThrows(ActivationRejected::class.java) {
            ActivationRules.settingsForSave(current.copy(liveScope = LiveScope.NONE),
                draft.copy(masterEnabled = true), 12)
        }
    }

    @Test fun futurePlanUsesEditedWeekdaysAndConfirmedRoster() {
        val enabled = activate(overrides = emptyMap())
        assertNotNull(ScheduleCalculator.holidayName(date))
        assertEquals(date.plusWeeks(1), ActivationRules.nextExecutablePlan(enabled, emptyMap(), emptyList(), now)?.date)
        assertEquals(3, ActivationRules.nextExecutablePlan(enabled, emptyMap(), emptyList(), now)?.quantity)
        assertThrows(ActivationRejected::class.java) {
            activate(edited = draft.copy(patternConfirmed = false), overrides = emptyMap())
        }
    }

    @Test fun disabledAutomaticRuleRequiresExplicitFutureManualPlan() {
        assertThrows(ActivationRejected::class.java) {
            activate(edited = draft.copy(dayAutoEnabled = false), overrides = emptyMap())
        }
        assertTrue(activate(edited = draft.copy(dayAutoEnabled = false)).masterEnabled)
    }

    @Test fun passedOrCurrentlyDuePlansAreNeverReactivated() {
        val manualOnly = draft.copy(dayAutoEnabled = false)
        assertThrows(ActivationRejected::class.java) {
            activate(edited = manualOnly, instant = Instant.parse("2026-10-08T21:00:00Z"))
        }
        assertThrows(ActivationRejected::class.java) {
            activate(edited = manualOnly, overrides = mapOf(date.minusDays(1) to manual(date.minusDays(1))))
        }
    }

    @Test fun completionUncertaintyAndPossibleSubmissionExcludeTheirDates() {
        val next = date.plusDays(1)
        val overrides = mapOf(date to manual(), next to manual(next))
        val manualOnly = draft.copy(dayAutoEnabled = false)
        val excludedRecords = listOf(
            ExecutionRecord(date, 2, ExecutionStatus.COMPLETED, "test", "test"),
            ExecutionRecord(date, 2, ExecutionStatus.NEEDS_CHECK, "test", "test"),
            ExecutionRecord(date, 2, ExecutionStatus.FAILED, "test", "test", submissionPossible = true)
        )
        excludedRecords.forEach { record ->
            val enabled = activate(edited = manualOnly, overrides = overrides, records = listOf(record))
            assertEquals(next, ActivationRules.nextExecutablePlan(enabled, overrides, listOf(record), now)?.date)
            assertThrows(ActivationRejected::class.java) {
                activate(edited = manualOnly, records = listOf(record))
            }
        }
    }

    @Test fun executionWindowIncludesSixButExcludesEight() {
        val manualOnly = draft.copy(dayAutoEnabled = false)
        assertTrue(activate(edited = manualOnly).masterEnabled)
        listOf(LocalTime.of(5, 59, 59), LocalTime.of(8, 0), LocalTime.of(12, 0)).forEach { time ->
            assertThrows(ActivationRejected::class.java) {
                activate(edited = manualOnly, overrides = mapOf(date.plusDays(1) to manual(date.plusDays(1), time)))
            }
        }
    }

    @Test fun automaticStopChangesOnlyMasterAndGeneration() {
        val armed = activate()
        val stopped = ActivationRules.stoppedSettings(armed)
        assertEquals(armed.copy(masterEnabled = false, generation = armed.generation + 1), stopped)
        assertTrue(stopped.dayAutoEnabled)
        assertEquals(LiveScope.RECURRING, stopped.liveScope)
        assertTrue(stopped.displayPriceRiskAccepted)
    }
}
