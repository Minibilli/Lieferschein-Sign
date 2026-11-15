package de.lieferschein.signer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import java.io.File

/**
 * Schreibt die Unterschrift-Striche als Vektorgrafik in die Original-PDF.
 * Der bestehende Inhalt bleibt unverändert (Text bleibt durchsuchbar), es wird nur ergänzt.
 */
object PdfSigner {

    fun sign(input: File, strokesByPage: Map<Int, List<Stroke>>, output: File) {
        PDDocument.load(input).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true

            for ((pageIndex, strokes) in strokesByPage) {
                if (strokes.isEmpty() || pageIndex >= doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val box = page.cropBox
                val llx = box.lowerLeftX
                val lly = box.lowerLeftY
                val w = box.width
                val h = box.height
                val rot = ((page.rotation % 360) + 360) % 360

                // Anzeige-Anteile (fx, fy; oben links) -> PDF-Koordinaten (unten links)
                fun map(fx: Float, fy: Float): Pair<Float, Float> = when (rot) {
                    90 -> Pair(llx + fy * w, lly + fx * h)
                    180 -> Pair(llx + w - fx * w, lly + fy * h)
                    270 -> Pair(llx + w - fy * w, lly + h - fx * h)
                    else -> Pair(llx + fx * w, lly + (1f - fy) * h)
                }

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setStrokingColor(0f, 0f, 0.55f)
                    cs.setLineWidth(INK_WIDTH_PT)
                    cs.setLineCapStyle(1)  // rund
                    cs.setLineJoinStyle(1) // rund
                    for (s in strokes) {
                        val pts = s.points
                        if (pts.isEmpty()) continue
                        val (x0, y0) = map(pts[0].x, pts[0].y)
                        cs.moveTo(x0, y0)
                        if (pts.size == 1) {
                            cs.lineTo(x0 + 0.3f, y0)
                        } else {
                            for (i in 1 until pts.size) {
                                val (x, y) = map(pts[i].x, pts[i].y)
                                cs.lineTo(x, y)
                            }
                        }
                        cs.stroke()
                    }
                }
            }
            doc.save(output)
        }
    }
}
