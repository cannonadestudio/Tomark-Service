package com.cannonadestudio.tomarkpilot.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads flight time per registration from a logbook export (CSV / TSV / semicolon or JSON).
 * Works with any logbook whose export has a registration column and a flight-time column –
 * each user points the app at their own export file.
 */
object Logbook {
    private val REG_HEADERS = listOf("registration", "aircraftregistration", "aircraftreg", "acreg", "reg", "tail", "tailnumber",
        "znaki", "znakirejestracyjne", "rejestracja", "znakrejestracyjny", "immatriculation", "kennzeichen",
        "imatrikulace", "registracia", "aircraft", "samolot", "statekpowietrzny", "plane")
    private val TIME_HEADERS = listOf("totaltime", "totalflighttime", "flighttime", "czaslotu", "czascalkowity", "czascałkowity",
        "blocktime", "duration", "durationminutes", "minutes", "total", "czas", "time", "tempsdevol", "flugzeit", "doba", "cas")

    /** Error codes stored in AppState.logbookError (translated in the UI). */
    const val ERR_BACKUP = "@backup"
    const val ERR_FIELDS = "@fields"
    const val ERR_EMPTY = "@empty"
    class LogbookException(val code: String) : Exception(code)

    data class Result(val hoursByReg: Map<String, Double>, val flights: Int)

    fun normReg(r: String) = r.uppercase().filter { it.isLetterOrDigit() }

    /** "1:25" → 1.4167 h, "1.5"/"1,5" → 1.5 h, minutes when [minutes] is true. */
    fun parseTime(raw: String, minutes: Boolean = false): Double? {
        val s = raw.trim().trim('"')
        if (s.isEmpty()) return null
        Regex("""^(\d+):(\d{1,2})(?::\d{1,2})?$""").matchEntire(s)?.let {
            return it.groupValues[1].toDouble() + it.groupValues[2].toDouble() / 60.0
        }
        Regex("""^(\d+)\s*h\s*(\d{1,2})\s*(min|m)?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            return it.groupValues[1].toDouble() + it.groupValues[2].toDouble() / 60.0
        }
        val n = s.replace(',', '.').toDoubleOrNull() ?: return null
        return if (minutes) n / 60.0 else n
    }

    fun parse(text: String): Result {
        val t = text.trimStart('﻿').trim()
        return if (t.startsWith("[") || t.startsWith("{")) parseJson(t) else parseCsv(t)
    }

    private fun norm(k: String) = k.trim().trim('"').lowercase().filter { it.isLetterOrDigit() }

    private fun pick(headers: List<String>, wanted: List<String>): Int {
        val h = headers.map(::norm)
        for (w in wanted) {
            val i = h.indexOfFirst { it == w }
            if (i >= 0) return i
        }
        // Partial match, but never clock times or dates ("departureTime" is not the flight time).
        val skip = listOf("dep", "arr", "start", "end", "takeoff", "landing", "offblock", "onblock", "date", "utc", "local", "night", "ifr", "pic", "dual", "instr", "type", "model")
        for (w in wanted) {
            val i = h.indexOfFirst { it.contains(w) && skip.none { x -> it.contains(x) && !w.contains(x) } }
            if (i >= 0) return i
        }
        return -1
    }

    private fun splitLine(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var q = false
        for (c in line) {
            when {
                c == '"' -> q = !q
                c == sep && !q -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    fun parseCsv(text: String): Result {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw LogbookException(ERR_EMPTY)
        val first = lines.first()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> first.count { it == c } } ?: ','
        val head = splitLine(first, sep)
        val ri = pick(head, REG_HEADERS)
        val ti = pick(head, TIME_HEADERS)
        if (ri < 0 || ti < 0) throw LogbookException(ERR_FIELDS)
        val minutes = head[ti].lowercase().contains("min")
        val map = HashMap<String, Double>()
        var n = 0
        lines.drop(1).forEach { line ->
            val cols = splitLine(line, sep)
            val reg = cols.getOrNull(ri)?.let(::normReg).orEmpty()
            val h = cols.getOrNull(ti)?.let { parseTime(it, minutes) }
            if (reg.isNotEmpty() && h != null) {
                map[reg] = (map[reg] ?: 0.0) + h
                n++
            }
        }
        return Result(map, n)
    }

    /** Flattens nested objects: {"aircraft":{"registration":"SP-X"}} → "aircraft.registration". */
    private fun flatten(o: JsonObject, prefix: String = "", out: MutableMap<String, JsonPrimitive> = LinkedHashMap()): Map<String, JsonPrimitive> {
        for ((k, v) in o) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            when (v) {
                is JsonPrimitive -> out[key] = v
                is JsonObject -> flatten(v, key, out)
                else -> Unit
            }
        }
        return out
    }

    /** All arrays of objects anywhere in the file (the flights list may be nested, e.g. {"data":{"flights":[…]}}). */
    private fun objectArrays(e: JsonElement, out: MutableList<List<JsonObject>> = mutableListOf()): List<List<JsonObject>> {
        when (e) {
            is JsonArray -> {
                val objs = e.filterIsInstance<JsonObject>()
                if (objs.isNotEmpty()) out += objs
                e.forEach { objectArrays(it, out) }
            }
            is JsonObject -> e.values.forEach { objectArrays(it, out) }
            else -> Unit
        }
        return out
    }

    private fun parseJson(text: String): Result {
        val root = Json.parseToJsonElement(text)
        // The AeroHangar backup is not a logbook – a common mix-up when both files sit in the same folder.
        if (root is JsonObject && "aircraft" in root && ("completions" in root || "bulletinStatus" in root)) throw LogbookException(ERR_BACKUP)
        var best: Result? = null
        for (arr in objectArrays(root)) {
            val map = HashMap<String, Double>()
            var n = 0
            arr.forEach { o ->
                val f = flatten(o)
                val keys = f.keys.toList()
                val rk = keys.getOrNull(pick(keys.map { it.substringAfterLast('.') }, REG_HEADERS).takeIf { it >= 0 } ?: pick(keys, REG_HEADERS))
                val tk = keys.getOrNull(pick(keys.map { it.substringAfterLast('.') }, TIME_HEADERS).takeIf { it >= 0 } ?: pick(keys, TIME_HEADERS))
                if (rk == null || tk == null) return@forEach
                val reg = f[rk]?.contentOrNull?.let(::normReg).orEmpty()
                val h = f[tk]?.contentOrNull?.let { parseTime(it, norm(tk).contains("min")) }
                if (reg.isNotEmpty() && h != null) {
                    map[reg] = (map[reg] ?: 0.0) + h
                    n++
                }
            }
            if (n > (best?.flights ?: 0)) best = Result(map, n)
        }
        return best ?: throw LogbookException(if (objectArrays(root).isEmpty()) ERR_EMPTY else ERR_FIELDS)
    }
}
