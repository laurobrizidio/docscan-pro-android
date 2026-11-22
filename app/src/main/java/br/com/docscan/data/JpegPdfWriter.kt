package br.com.docscan.data

import br.com.docscan.domain.PdfLayout
import java.io.OutputStream
import java.util.Locale

/** Writes raster pages using PDF image XObjects so the selected JPEG quality controls the file size. */
internal class JpegPdfWriter(private val output: OutputStream, private val pageCount: Int) {
    private val offsets = LongArray(3 + pageCount * 3)
    private var position = 0L
    private var writtenPages = 0

    init {
        require(pageCount > 0)
        text("%PDF-1.4\n")
        bytes(byteArrayOf(0x25, 0xe2.toByte(), 0xe3.toByte(), 0xcf.toByte(), 0xd3.toByte(), 0x0a))
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val children = (0 until pageCount).joinToString(" ") { "${pageId(it)} 0 R" }
        obj(2, "<< /Type /Pages /Count $pageCount /Kids [$children] >>")
    }

    fun addPage(jpeg: ByteArray, width: Int, height: Int, layout: PdfLayout) {
        check(writtenPages < pageCount)
        val page = pageId(writtenPages++)
        val image = page + 1
        val contents = page + 2
        obj(page, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${layout.pageWidth} ${layout.pageHeight}] " +
            "/Resources << /XObject << /Photo $image 0 R >> >> /Contents $contents 0 R >>")
        stream(image, "/Type /XObject /Subtype /Image /Width $width /Height $height " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg)
        val commands = "q\n${decimal(layout.imageWidth)} 0 0 ${decimal(layout.imageHeight)} " +
            "${decimal(layout.x)} ${decimal(layout.y)} cm\n/Photo Do\nQ\n"
        stream(contents, "", commands.encodeToByteArray())
    }

    fun finish() {
        check(writtenPages == pageCount)
        val xref = position
        text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (offset in offsets.drop(1)) text("${offset.toString().padStart(10, '0')} 00000 n \n")
        text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }

    private fun pageId(index: Int) = 3 + index * 3

    private fun obj(id: Int, contents: String) {
        offsets[id] = position
        text("$id 0 obj\n$contents\nendobj\n")
    }

    private fun stream(id: Int, dictionary: String, data: ByteArray) {
        offsets[id] = position
        text("$id 0 obj\n<< $dictionary /Length ${data.size} >>\nstream\n")
        bytes(data)
        text("\nendstream\nendobj\n")
    }

    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private fun text(value: String) = bytes(value.encodeToByteArray())

    private fun bytes(data: ByteArray) {
        output.write(data)
        position += data.size
    }
}
