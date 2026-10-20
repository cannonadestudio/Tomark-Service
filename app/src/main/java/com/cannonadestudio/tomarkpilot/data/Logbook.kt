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
    private val REG_HEADERS = listOf("registration", "aircraft reg", "reg", "znaki", "rejestracja", "znak rejestracyjny",
        "immatriculation", "kennzeichen", "aircraft", "samolot", "statek powietrzny")
    private val TIME_HEADERS = listOf("total time", "total flight time", "flight time", "czas lotu", "czas całkowity", "czas",
        "total", "duration", "block time", "flighttime", "totaltime", "time", "temps de vol", "flugzeit")

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

    private fun pick(headers: List<String>, wanted: List<String>): Int {
        val h = headers.map { it.trim().trim('"').lowercase() }
        for (w in wanted) {
            val i = h.indexOfFirst { it == w }
            if (i >= 0) return i
        }
        for (w in wanted) {
            val i = h.indexOfFirst { it.contains(w) }
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
        if (lines.isEmpty()) return Result(emptyMap(), 0)
        val first = lines.first()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> first.count { it == c } } ?: ','
        val head = splitLine(first, sep)
        val ri = pick(head, REG_HEADERS)
        val ti = pick(head, TIME_HEADERS)
        if (ri < 0 || ti < 0) error("no registration/time column")
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

    private fun parseJson(text: String): Result {
        val root = Json.parseToJsonElement(text)
        val arr: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> root.values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(emptyList())
            else -> emptyList()
        }
        val map = HashMap<String, Double>()
        var n = 0
        arr.filterIsInstance<JsonObject>().forEach { o ->
            val keys = o.keys.toList()
            val rk = keys.getOrNull(pick(keys, REG_HEADERS))
            val tk = keys.getOrNull(pick(keys, TIME_HEADERS))
            if (rk == null || tk == null) return@forEach
            val reg = (o[rk] as? JsonPrimitive)?.contentOrNull?.let(::normReg).orEmpty()
            val h = (o[tk] as? JsonPrimitive)?.contentOrNull?.let { parseTime(it, tk.lowercase().contains("min")) }
            if (reg.isNotEmpty() && h != null) {
                map[reg] = (map[reg] ?: 0.0) + h
                n++
            }
        }
        if (n == 0 && arr.isNotEmpty()) error("no registration/time fields")
        return Result(map, n)
    }
}
