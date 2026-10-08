package com.fullmetalsonic.dosirak.domain

import java.time.LocalDate

internal object KoreanHolidays {
    // National holidays only; weekends are handled by ScheduleCalculator.
    // Verified 2026-10-09: 2026 KASA almanac + MPM's 2026-04-29 amendment,
    // and the final 2027 almanac in the government's original HWPX attachment.
    // https://www.kasi.re.kr/kor/post/newsMaterial/32031
    // https://astro.kasi.re.kr/kor/life/post/calendarData?search_year=2026
    // https://www.mpm.go.kr/mpm/comm/newsPress/newsPressRelease/?boardId=bbs_0000000000000029&cntId=4250&mode=view
    // https://www.korea.kr/briefing/pressReleaseView.do?newsId=156768474
    // https://www.korea.kr/common/download.do?fileId=198497802&tblKey=GMN
    // Future temporary holidays require a source-verified data update.
    private val dates: Map<LocalDate, String> = mapOf(
        LocalDate.of(2026, 1, 1) to "신정",
        LocalDate.of(2026, 2, 16) to "설날 연휴",
        LocalDate.of(2026, 2, 17) to "설날",
        LocalDate.of(2026, 2, 18) to "설날 연휴",
        LocalDate.of(2026, 3, 1) to "삼일절",
        LocalDate.of(2026, 3, 2) to "대체공휴일(삼일절)",
        LocalDate.of(2026, 5, 1) to "노동절",
        LocalDate.of(2026, 5, 5) to "어린이날",
        LocalDate.of(2026, 5, 24) to "부처님오신날",
        LocalDate.of(2026, 5, 25) to "대체공휴일(부처님오신날)",
        LocalDate.of(2026, 6, 3) to "전국동시지방선거",
        LocalDate.of(2026, 6, 6) to "현충일",
        LocalDate.of(2026, 7, 17) to "제헌절",
        LocalDate.of(2026, 8, 15) to "광복절",
        LocalDate.of(2026, 8, 17) to "대체공휴일(광복절)",
        LocalDate.of(2026, 9, 24) to "추석 연휴",
        LocalDate.of(2026, 9, 25) to "추석",
        LocalDate.of(2026, 9, 26) to "추석 연휴",
        LocalDate.of(2026, 10, 3) to "개천절",
        LocalDate.of(2026, 10, 5) to "대체공휴일(개천절)",
        LocalDate.of(2026, 10, 9) to "한글날",
        LocalDate.of(2026, 12, 25) to "기독탄신일",
        LocalDate.of(2027, 1, 1) to "신정",
        LocalDate.of(2027, 2, 6) to "설날 연휴",
        LocalDate.of(2027, 2, 7) to "설날",
        LocalDate.of(2027, 2, 8) to "설날 연휴",
        LocalDate.of(2027, 2, 9) to "대체공휴일(설날)",
        LocalDate.of(2027, 3, 1) to "삼일절",
        LocalDate.of(2027, 5, 1) to "노동절",
        LocalDate.of(2027, 5, 3) to "대체공휴일(노동절)",
        LocalDate.of(2027, 5, 5) to "어린이날",
        LocalDate.of(2027, 5, 13) to "부처님오신날",
        LocalDate.of(2027, 6, 6) to "현충일",
        LocalDate.of(2027, 7, 17) to "제헌절",
        LocalDate.of(2027, 7, 19) to "대체공휴일(제헌절)",
        LocalDate.of(2027, 8, 15) to "광복절",
        LocalDate.of(2027, 8, 16) to "대체공휴일(광복절)",
        LocalDate.of(2027, 9, 14) to "추석 연휴",
        LocalDate.of(2027, 9, 15) to "추석",
        LocalDate.of(2027, 9, 16) to "추석 연휴",
        LocalDate.of(2027, 10, 3) to "개천절",
        LocalDate.of(2027, 10, 4) to "대체공휴일(개천절)",
        LocalDate.of(2027, 10, 9) to "한글날",
        LocalDate.of(2027, 10, 11) to "대체공휴일(한글날)",
        LocalDate.of(2027, 12, 25) to "기독탄신일",
        LocalDate.of(2027, 12, 27) to "대체공휴일(기독탄신일)"
    )

    fun nameOn(date: LocalDate): String? = dates[date]

    fun knownYear(year: Int): Boolean = year == 2026 || year == 2027
}
