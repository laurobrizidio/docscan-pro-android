package br.com.docscan.data

import android.util.AtomicFile
import br.com.docscan.domain.Crop
import br.com.docscan.domain.DocumentQuad
import br.com.docscan.domain.Draft
import br.com.docscan.domain.EditorSession
import br.com.docscan.domain.Filter
import br.com.docscan.domain.Look
import br.com.docscan.domain.MAX_PAGES
import br.com.docscan.domain.Orientation
import br.com.docscan.domain.PagePoint
import br.com.docscan.domain.Paper
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality
import br.com.docscan.domain.ScanPage
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class DraftStore(directory: File) {
    private val metadata = AtomicFile(File(directory, "draft.json"))

    fun read(imageExists: (String) -> Boolean): EditorSession {
        if (!metadata.baseFile.isFile && !File(metadata.baseFile.path + ".bak").isFile) return EditorSession()
        val root = try {
            JSONObject(metadata.readFully().decodeToString())
        } catch (error: JSONException) {
            throw IOException("Rascunho ilegível", error)
        }
        if (root.optInt("version") != 1) throw IOException("Formato de rascunho desconhecido")
        val items = root.optJSONArray("pages") ?: JSONArray()
        val pages = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { readPage(it, imageExists) }
        }.distinctBy { it.id }.take(MAX_PAGES)
        val requested = root.optString("selected")
        val selectedId = pages.firstOrNull { it.id == requested }?.id ?: pages.firstOrNull()?.id
        val options = root.optJSONObject("pdf")
        return EditorSession(
            draft = Draft(pages, selectedId),
            documentId = root.optString("documentId").takeIf { it.matches(idPattern) },
            options = PdfOptions(
                name = options?.optString("name").orEmpty(),
                paper = Paper.entries.firstOrNull { it.name == options?.optString("paper") } ?: Paper.A4,
                orientation = Orientation.entries.firstOrNull { it.name == options?.optString("orientation") }
                    ?: Orientation.AUTO,
                marginMm = options?.optInt("margin", 10)?.coerceIn(0, 20) ?: 10,
                quality = PdfQuality.entries.firstOrNull { it.name == options?.optString("quality") }
                    ?: PdfQuality.BALANCED,
            ),
            dirty = root.optBoolean("dirty", pages.isNotEmpty()),
            pendingDocumentId = root.optString("pendingDocumentId").takeIf { it.matches(idPattern) },
        )
    }

    fun write(session: EditorSession) {
        val draft = session.draft
        val options = session.options
        val json = JSONObject()
            .put("version", 1)
            .put("selected", draft.selectedId)
            .put("pages", JSONArray(draft.pages.map(::writePage)))
            .put("documentId", session.documentId)
            .put("dirty", session.dirty)
            .put("pendingDocumentId", session.pendingDocumentId)
            .put("pdf", JSONObject()
                .put("name", options.name)
                .put("paper", options.paper.name)
                .put("orientation", options.orientation.name)
                .put("margin", options.marginMm)
                .put("quality", options.quality.name))
        val stream = metadata.startWrite()
        try {
            stream.write(json.toString().encodeToByteArray())
            metadata.finishWrite(stream)
        } catch (error: Exception) {
            metadata.failWrite(stream)
            throw error
        }
    }

    private fun readPage(json: JSONObject, imageExists: (String) -> Boolean): ScanPage? {
        val id = json.optString("id")
        if (!id.matches(idPattern) || !imageExists(id)) return null
        val filter = Filter.entries.firstOrNull { it.name == json.optString("filter") } ?: Filter.MAGIC_COLOR
        val crop = json.optJSONArray("crop")?.let {
            runCatching { Crop(it.float(0), it.float(1), it.float(2), it.float(3)) }.getOrNull()
        } ?: Crop.Full
        val quad = json.optJSONArray("quad")?.let {
            val coordinates = (0..7).map { index -> it.float(index) }
            if (coordinates.any { value -> value !in 0f..1f }) return@let null
            fun point(index: Int) = PagePoint(coordinates[index], coordinates[index + 1])
            DocumentQuad(point(0), point(2), point(4), point(6))
        }
        return ScanPage(
            id,
            quad,
            crop,
            json.optInt("turns").coerceIn(0, 3),
            json.optDouble("angle", 0.0).toFloat().takeIf { it in -25f..25f } ?: 0f,
            Look(
                filter,
                json.optInt("strength", filter.strength).coerceIn(0, 100),
                json.optInt("brightness", filter.brightness).coerceIn(50, 150),
                json.optInt("contrast", filter.contrast).coerceIn(50, 160),
                json.optBoolean("ml", true),
            ),
        )
    }

    private fun writePage(page: ScanPage): JSONObject {
        val crop = page.crop
        val look = page.look
        return JSONObject()
            .put("id", page.id)
            .put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom)))
            .put("quad", page.quad?.let { JSONArray(it.corners.flatMap { point -> listOf(point.x, point.y) }) })
            .put("turns", page.quarterTurns)
            .put("angle", page.fineRotation.toDouble())
            .put("filter", look.filter.name)
            .put("strength", look.strength)
            .put("brightness", look.brightness)
            .put("contrast", look.contrast)
            .put("ml", look.useMl)
    }

    private fun JSONArray.float(index: Int) = optDouble(index).toFloat()

    private companion object {
        val idPattern = Regex("[0-9a-f-]{36}")
    }
}
