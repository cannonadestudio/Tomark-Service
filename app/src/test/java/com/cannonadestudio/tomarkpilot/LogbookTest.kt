package com.cannonadestudio.tomarkpilot

import com.cannonadestudio.tomarkpilot.data.Logbook
import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookTest {
    @Test fun csvSemicolonHoursMinutes() {
        val csv = "Data;Znaki;Typ;Czas lotu\n2026-05-01;SP-ABC;Viper;1:30\n2026-05-02;sp-abc;Viper;0:45\n2026-05-03;OM-M123;Skyper;2:00\n"
        val r = Logbook.parse(csv)
        assertEquals(2.25, r.hoursByReg["SPABC"]!!, 0.001)
        assertEquals(2.0, r.hoursByReg["OMM123"]!!, 0.001)
        assertEquals(3, r.flights)
    }

    @Test fun csvCommaDecimalAndQuotes() {
        val csv = "\"Date\",\"Registration\",\"Total time\"\n\"2026-01-01\",\"D-MABC\",\"1.5\"\n"
        assertEquals(1.5, Logbook.parse(csv).hoursByReg["DMABC"]!!, 0.001)
    }

    @Test fun minutesColumn() {
        val csv = "reg,duration (min)\nSP-XYZ,90\n"
        assertEquals(1.5, Logbook.parse(csv).hoursByReg["SPXYZ"]!!, 0.001)
    }

    @Test fun json() {
        val js = """{"flights":[{"registration":"SP-ABC","flightTime":"1:10"},{"registration":"SP-ABC","flightTime":"0:50"}]}"""
        assertEquals(2.0, Logbook.parse(js).hoursByReg["SPABC"]!!, 0.001)
    }

    @Test fun backupFileIsRecognised() {
        val js = """{"aircraft":[{"id":"1","registration":"SP-ABC","hours":12.0}],"completions":[],"bulletinStatus":[]}"""
        val e = runCatching { Logbook.parse(js) }.exceptionOrNull()
        assertEquals(Logbook.ERR_BACKUP, (e as Logbook.LogbookException).code)
    }

    @Test fun nestedJsonWithClockTimes() {
        val js = """{"version":2,"data":{"pilot":{"name":"X"},"flights":[
            {"date":"2026-05-01","departureTime":"10:00","arrivalTime":"11:30","aircraft":{"registration":"SP-ABC","type":"Viper"},"totalTime":"1:30"},
            {"date":"2026-05-02","departureTime":"09:00","arrivalTime":"09:45","aircraft":{"registration":"SP-ABC","type":"Viper"},"totalTime":"0:45"}]}}"""
        val r = Logbook.parse(js)
        assertEquals(2.25, r.hoursByReg["SPABC"]!!, 0.001)
        assertEquals(2, r.flights)
    }

    @Test fun jsonMinutesNumber() {
        val js = """[{"tail_number":"OM-M123","duration_minutes":75}]"""
        assertEquals(1.25, Logbook.parse(js).hoursByReg["OMM123"]!!, 0.001)
    }

    @Test fun parseTimeVariants() {
        assertEquals(1.25, Logbook.parseTime("1:15")!!, 0.001)
        assertEquals(1.5, Logbook.parseTime("1,5")!!, 0.001)
        assertEquals(2.5, Logbook.parseTime("2h 30min")!!, 0.001)
    }
}
