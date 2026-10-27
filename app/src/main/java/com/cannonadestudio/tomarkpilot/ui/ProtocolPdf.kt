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
import com.cannonadestudio.tomarkpilot.data.Protocol
import com.cannonadestudio.tomarkpilot.data.Repo
import java.io.File

/** Inspection protocol (finished checklist) as an A4 PDF – shared, printed and signed by the mechanic. */
object ProtocolPdf {
    private const val NAVY = 0xFF0B2A5B.toInt()

    fun pdf(ctx: Context, a: Aircraft, p: Protocol, s: AppState): File {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 40f
        fun paint(size: Float, bold: Boolean = false, color: Int = 0xFF1B2430.toInt()) = Paint().apply {
            isAntiAlias = true; textSize = size; this.color = color
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val title = paint(17f, true, NAVY); val big = paint(12f, true, NAVY)
        val key = paint(9f, true); val body = paint(9f); val muted = paint(7.5f, color = 0xFF55606F.toInt())
        val ok = paint(10f, true, 0xFF2E7D32.toInt()); val bad = paint(9f, true, 0xFFB26A00.toInt())
        val line = Paint().apply { color = 0xFFE3E7ED.toInt(); strokeWidth = 0.7f }
        val bar = Paint().apply { color = NAVY; strokeWidth = 2.5f }
        val headBg = Paint().apply { color = 0xFFE6EEF8.toInt() }
        fun wrap(text: String, pt: Paint, width: Float): List<String> {
            val out = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var cur = ""
                para.split(' ').forEach { w ->
                    val t = if (cur.isEmpty()) w else "$cur $w"
                    if (pt.measureText(t) <= width) cur = t else { if (cur.isNotEmpty()) out += cur; cur = w }
                }
                out += cur
            }
            return out
        }
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        var y = 0f
        val cNo = M; val cItem = M + 22f; val cRes = M + 290f; val cMark = W - M - 40f
        fun tableHead() {
            page.canvas.drawRect(M, y - 10f, W - M, y + 5f, headBg)
            page.canvas.drawText("#", cNo + 2f, y, key)
            page.canvas.drawText(ctx.getString(R.string.chk_item), cItem, y, key)
            page.canvas.drawText(ctx.getString(R.string.chk_result), cRes, y, key)
            y += 16f
        }
        fun newPage() {
            if (pageNo > 0) { page.canvas.drawText("AeroHangar · ${a.registration} · $pageNo", M, H - 20f, muted); doc.finishPage(page) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M + 10f
        }
        fun need(h: Float, head: Boolean = false) { if (y + h > H - 50f) { newPage(); if (head) tableHead() } }

        newPage()
        val type = Repo.type(a.typeId)
        val eng = Repo.engineOf(a)
        page.canvas.drawText(ctx.getString(R.string.chk_pdf_title), M, y + 8f, title)
        page.canvas.drawText(a.registration, W - M - big.measureText(a.registration), y + 8f, big)
        y += 24f
        val left = listOfNotNull(p.taskTitle + (p.dueAtHours?.let { " (" + ctx.getString(R.string.chk_at, fmtH(it)) + ")" } ?: ""))
        val right = listOfNotNull(listOfNotNull(type?.name, eng.name).joinToString(" · "),
            a.serial.takeIf { it.isNotBlank() }?.let { ctx.getString(R.string.serial_number) + " " + it },
            a.engineSerial.takeIf { it.isNotBlank() }?.let { ctx.getString(R.string.engine_serial) + " " + it })
        val rows = maxOf(left.size, right.size)
        for (i in 0 until rows) {
            left.getOrNull(i)?.let { page.canvas.drawText(it, M, y, body) }
            right.getOrNull(i)?.let { page.canvas.drawText(it, W - M - body.measureText(it), y, body) }
            y += 12f
        }
        y += 2f
        page.canvas.drawLine(M, y, W - M, y, bar)
        y += 16f
        val info = listOfNotNull(
            p.epochDay?.let { ctx.getString(R.string.done_date) + ": " + fmtEpochDay(it) },
            p.hours?.let { ctx.getString(R.string.done_hours) + ": " + fmtH(it) + " h" },
            p.place.takeIf { it.isNotBlank() }?.let { ctx.getString(R.string.chk_place) + ": " + it },
        )
        page.canvas.drawText(info.joinToString("     "), M, y, key)
        y += 20f
        tableHead()
        p.items.forEachIndexed { i, it ->
            val t = (if (it.section.isNotBlank()) it.section + ": " else "") + it.title
            val vals = it.values.filter { v -> v.isNotBlank() }.joinToString(" / ").takeIf { v -> v.isNotBlank() }?.let { v -> v + (if (it.unit.isNotBlank()) " " + it.unit else "") }
            val res = listOfNotNull(vals, it.note.takeIf { n -> n.isNotBlank() }, if (it.na) ctx.getString(R.string.chk_na_long) else null).joinToString(" · ")
            val tl = wrap(t, body, cRes - cItem - 8f)
            val rl = if (res.isBlank()) emptyList() else wrap(res, body, cMark - cRes - 6f)
            val h = maxOf(tl.size, rl.size, 1) * 11f + 5f
            need(h, head = true)
            page.canvas.drawText("${i + 1}", cNo + 2f, y, body)
            tl.forEachIndexed { k, s2 -> page.canvas.drawText(s2, cItem, y + k * 11f, body) }
            rl.forEachIndexed { k, s2 -> page.canvas.drawText(s2, cRes, y + k * 11f, body) }
            when {
                it.done -> page.canvas.drawText("✓", cMark + 18f, y, ok)
                it.na -> page.canvas.drawText("n/a", cMark + 10f, y, muted)
                else -> page.canvas.drawText("–", cMark + 18f, y, bad)
            }
            y += h - 8f
            page.canvas.drawLine(M, y, W - M, y, line)
            y += 11f
        }
        val notDone = p.items.count { !it.done && !it.na }
        if (notDone > 0) { need(14f); page.canvas.drawText(ctx.getString(R.string.chk_not_done) + ": " + notDone, M, y, bad); y += 14f }
        val open = Repo.openBulletins(a, s).filter { it.mandatory == true || it.kind == "AD" }
        need(30f)
        y += 6f
        page.canvas.drawText(ctx.getString(R.string.chk_bulletins_open) + ": " + (if (open.isEmpty()) "–" else open.size.toString()), M, y, key)
        y += 12f
        open.take(8).forEach { b -> wrap(b.date + " " + b.title, body, W - M * 2).forEach { s2 -> need(11f); page.canvas.drawText(s2, M + 8f, y, body); y += 11f } }
        if (p.notes.isNotBlank()) {
            y += 6f; need(24f)
            page.canvas.drawText(ctx.getString(R.string.chk_remarks) + ":", M, y, key); y += 12f
            wrap(p.notes, body, W - M * 2).forEach { s2 -> need(11f); page.canvas.drawText(s2, M, y, body); y += 11f }
        }
        need(80f)
        y += 34f
        val half = (W - M * 2 - 20f) / 2
        page.canvas.drawLine(M, y, M + half, y, body)
        page.canvas.drawLine(M + half + 20f, y, W - M, y, body)
        y += 12f
        page.canvas.drawText(ctx.getString(R.string.chk_mechanic) + ": " + p.mechanic, M, y, body)
        page.canvas.drawText(ctx.getString(R.string.chk_signature), M + half + 20f, y, body)
        if (p.licence.isNotBlank()) { y += 11f; page.canvas.drawText(ctx.getString(R.string.chk_licence) + ": " + p.licence, M, y, body) }
        y += 22f
        wrap(ctx.getString(R.string.chk_pdf_disclaimer, p.source.ifBlank { "–" }), muted, W - M * 2).forEach { s2 -> need(10f); page.canvas.drawText(s2, M, y, muted); y += 9f }
        page.canvas.drawText("AeroHangar · ${a.registration} · $pageNo", M, H - 20f, muted)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val name = "Protokol_" + a.registration.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "aircraft" } + "_" + (p.epochDay?.let { fmtEpochDay(it).replace('.', '-').replace('/', '-') } ?: "draft") + ".pdf"
        val f = File(dir, name)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, a: Aircraft, p: Protocol, s: AppState) {
        val f = pdf(ctx, a, p, s)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.chk_pdf_title) + " " + a.registration)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
