package com.cannonadestudio.tomarkpilot.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.DueStatus
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Repo
import java.io.File
import java.time.LocalDate

/**
 * Everything the app knows about one aircraft, as a PDF (for a sale ad, the buyer or a mechanic)
 * or as plain text to paste. Built from the owner's own entries.
 */
object AircraftExport {

    private data class Section(val title: String, val lines: List<Pair<String, String>>)

    private fun sections(ctx: Context, a: Aircraft, s: AppState): List<Section> {
        val lg = java.util.Locale.getDefault().language
        val t = Repo.type(a.typeId)
        fun g(id: Int, vararg args: Any): String = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
        val out = mutableListOf<Section>()

        out += Section(g(R.string.export_aircraft), listOfNotNull(
            g(R.string.registration) to a.registration,
            g(R.string.aircraft_type) to listOfNotNull(t?.manufacturer?.takeIf { it.isNotBlank() && t.name.startsWith(it).not() }, t?.name ?: a.typeId).joinToString(" "),
            g(R.string.export_category) to (if (t?.category == "EASA") "EASA" + (t.tcds?.let { " · TCDS $it" } ?: "") else "UL / LSA"),
            a.serial.takeIf { it.isNotBlank() }?.let { g(R.string.serial_number) to it },
            a.yearBuilt?.let { g(R.string.year_built) to it.toString() },
            a.engineSerial.takeIf { it.isNotBlank() }?.let { g(R.string.engine_serial) to it },
            a.gearboxSerial.takeIf { it.isNotBlank() }?.let { g(R.string.gearbox_serial).substringBefore(" (") to it },
            g(R.string.total_hours).substringBefore(" (") to (fmtH(a.hours) + " h" + (a.hoursUpdatedEpochDay?.let { " (" + fmtEpochDay(it) + ")" } ?: "")),
            a.notes.takeIf { it.isNotBlank() }?.let { g(R.string.export_notes) to it },
        ))

        val due = Maintenance.compute(a, s.completions)
            .filter { it.status != DueStatus.NO_INTERVAL && it.status != DueStatus.DONE }
            .sortedWith(compareBy({ it.remainingHours ?: Double.MAX_VALUE }, { it.remainingDays ?: Long.MAX_VALUE }))
        out += Section(g(R.string.export_next), due.map { d ->
            val parts = listOfNotNull(
                d.dueHours?.let { g(R.string.export_at_h, fmtH(it)) + (d.remainingHours?.let { r -> " (" + (if (r >= 0) g(R.string.export_left_h, fmtH(r)) else g(R.string.next_insp_over, fmtH(-r))) + ")" } ?: "") },
                d.dueDate?.let { g(R.string.export_by_date, fmtDate(it)) },
            )
            d.task.title.get(lg) to (parts.joinToString(" · ").ifBlank { "–" } + " – " + g(statusLook(d.status).label))
        })

        val titles = Maintenance.tasks(a).associate { it.first.id to it.first.title.get(lg) }
        val hist = s.completions.filter { it.aircraftId == a.id }.sortedWith(compareByDescending<com.cannonadestudio.tomarkpilot.data.CompletionRecord> { it.epochDay }.thenByDescending { it.hours })
        out += Section(g(R.string.export_history), hist.map { r ->
            "${fmtEpochDay(r.epochDay)} · ${fmtH(r.hours)} h" to ((titles[r.taskId] ?: r.taskId) + (r.note.takeIf { it.isNotBlank() }?.let { " – $it" } ?: ""))
        })

        val feed = Repo.feed.value.bulletins.associateBy { it.id }
        val st = s.bulletinStatus.filter { it.aircraftId == a.id }
        out += Section(g(R.string.export_bulletins_done), st.filter { it.status == "DONE" }.sortedByDescending { it.epochDay }.map { b ->
            b.bulletinId to listOfNotNull(feed[b.bulletinId]?.title, fmtEpochDay(b.epochDay) + (b.hours?.let { " · ${fmtH(it)} h" } ?: ""), b.note.takeIf { it.isNotBlank() }).joinToString(" – ")
        })
        val na = st.filter { it.status == "NA" }
        if (na.isNotEmpty()) out += Section(g(R.string.export_bulletins_na), na.map { b -> b.bulletinId to (feed[b.bulletinId]?.title ?: "") })
        val openMandatory = Repo.openBulletins(a, s).filter { it.mandatory == true }
        out += Section(g(R.string.export_bulletins_open), openMandatory.map { it.id to (it.date + " – " + it.title) })
        return out
    }

    fun text(ctx: Context, a: Aircraft, s: AppState): String = buildString {
        appendLine(ctx.getString(R.string.export_doc_title, a.registration))
        appendLine(ctx.getString(R.string.export_generated, fmtDate(LocalDate.now())))
        sections(ctx, a, s).forEach { sec ->
            appendLine(); appendLine(sec.title.uppercase())
            if (sec.lines.isEmpty()) appendLine("– " + ctx.getString(R.string.export_none))
            sec.lines.forEach { (k, v) -> appendLine("• $k: $v") }
        }
        appendLine(); appendLine(ctx.getString(R.string.export_disclaimer))
    }

    /** A4 PDF in the app's cache (shared through the FileProvider). */
    fun pdf(ctx: Context, a: Aircraft, s: AppState): File {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 40f
        val title = Paint().apply { isAntiAlias = true; textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = android.graphics.Color.WHITE }
        val sub = Paint().apply { isAntiAlias = true; textSize = 10f; color = android.graphics.Color.WHITE }
        val head = Paint().apply { isAntiAlias = true; textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF7A3E9D.toInt() }
        val key = Paint().apply { isAntiAlias = true; textSize = 9.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF2B2A33.toInt() }
        val body = Paint().apply { isAntiAlias = true; textSize = 9.5f; color = 0xFF2B2A33.toInt() }
        val muted = Paint().apply { isAntiAlias = true; textSize = 8f; color = 0xFF6B6676.toInt() }
        val band = Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, W.toFloat(), 0f, intArrayOf(0xFF7A3E9D.toInt(), 0xFF2E8FC4.toInt(), 0xFF0FB3A6.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        }
        val line = Paint().apply { color = 0xFFE7E2EE.toInt(); strokeWidth = 0.7f }

        var pageNo = 0
        lateinit var page: PdfDocument.Page
        var y = 0f
        fun footer() {
            page.canvas.drawText("AeroHangar · ${a.registration} · ${pageNo}", M, H - 20f, muted)
        }
        fun newPage(first: Boolean) {
            if (pageNo > 0) { footer(); doc.finishPage(page) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            if (first) {
                page.canvas.drawRect(0f, 0f, W.toFloat(), 78f, band)
                page.canvas.drawText(ctx.getString(R.string.export_doc_title, a.registration), M, 40f, title)
                page.canvas.drawText(ctx.getString(R.string.export_generated, fmtDate(LocalDate.now())), M, 60f, sub)
                y = 104f
            } else y = M + 10f
        }
        fun wrap(text: String, p: Paint, width: Float): List<String> {
            val out = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var cur = ""
                para.split(' ').forEach { w ->
                    val t = if (cur.isEmpty()) w else "$cur $w"
                    if (p.measureText(t) <= width) cur = t else { if (cur.isNotEmpty()) out += cur; cur = w }
                }
                out += cur
            }
            return out
        }
        fun need(h: Float) { if (y + h > H - 44f) newPage(false) }

        newPage(true)
        val keyW = 160f
        sections(ctx, a, s).forEach { sec ->
            need(40f)
            y += 6f
            page.canvas.drawText(sec.title, M, y, head)
            y += 6f
            page.canvas.drawLine(M, y, W - M, y, line)
            y += 14f
            if (sec.lines.isEmpty()) { page.canvas.drawText("– " + ctx.getString(R.string.export_none), M, y, muted); y += 14f }
            sec.lines.forEach { (k, v) ->
                val kl = wrap(k, key, keyW - 8f)
                val vl = wrap(v, body, W - M * 2 - keyW)
                val h = maxOf(kl.size, vl.size) * 12.5f + 3f
                need(h)
                kl.forEachIndexed { i, t -> page.canvas.drawText(t, M, y + i * 12.5f, key) }
                vl.forEachIndexed { i, t -> page.canvas.drawText(t, M + keyW, y + i * 12.5f, body) }
                y += h
            }
            y += 8f
        }
        need(40f)
        wrap(ctx.getString(R.string.export_disclaimer), muted, W - M * 2).forEach { page.canvas.drawText(it, M, y, muted); y += 10f }
        footer()
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "AeroHangar_" + a.registration.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "aircraft" } + ".pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun sharePdf(ctx: Context, a: Aircraft, s: AppState) {
        val f = pdf(ctx, a, s)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.export_doc_title, a.registration))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(ctx: Context, a: Aircraft, s: AppState) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.export_doc_title, a.registration))
            .putExtra(Intent.EXTRA_TEXT, text(ctx, a, s))
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
