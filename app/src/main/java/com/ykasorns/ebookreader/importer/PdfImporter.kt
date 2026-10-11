package com.ykasorns.ebookreader.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

object PdfImporter {
    const val FILE_NAME = "book.pdf"

    fun import(source: File, bookDir: File, coverFile: File): ImportMeta {
        val pdf = File(bookDir, FILE_NAME)
        source.copyTo(pdf, overwrite = true)
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount > 0) renderCover(renderer, coverFile)
                }
            }
        } catch (e: SecurityException) {
            throw ImportException("Password-protected PDFs are not supported")
        } catch (e: Exception) {
            throw ImportException("Not a valid PDF")
        }
        return ImportMeta(title = "", author = "")
    }

    private fun renderCover(renderer: PdfRenderer, coverFile: File) {
        renderer.openPage(0).use { page ->
            val width = 480
            val height = (width * page.height.toFloat() / page.width).toInt().coerceIn(1, width * 3)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            coverFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
        }
    }
}
