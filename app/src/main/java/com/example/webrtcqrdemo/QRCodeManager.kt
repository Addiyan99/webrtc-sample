package com.example.webrtcqrdemo

import android.graphics.Bitmap
import android.graphics.Color
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import android.util.Base64

object QRCodeManager {
    
    fun generateQRCode(signalingData: SignalingData, size: Int = 512): Bitmap? {
        return try {
            val json = Gson().toJson(signalingData)
            android.util.Log.d("QRCodeManager", "Original JSON length: ${json.length}")
            
            // Try compression first
            val compressedData = compressString(json)
            android.util.Log.d("QRCodeManager", "Compressed data length: ${compressedData.length}")
            
            // Use compressed data if it's significantly smaller, otherwise use simplified SDP
            val dataToEncode = if (compressedData.length < json.length * 0.7) {
                android.util.Log.d("QRCodeManager", "Using compressed data")
                "COMPRESSED:$compressedData"
            } else {
                android.util.Log.d("QRCodeManager", "Compression not effective, using simplified SDP")
                val simplifiedData = simplifySignalingData(signalingData)
                Gson().toJson(simplifiedData)
            }
            
            android.util.Log.d("QRCodeManager", "Final data length: ${dataToEncode.length}")
            
            val writer = QRCodeWriter()
            val bitMatrix: BitMatrix = writer.encode(dataToEncode, BarcodeFormat.QR_CODE, size, size)
            
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: WriterException) {
            android.util.Log.e("QRCodeManager", "WriterException: ${e.message}", e)
            null
        } catch (e: Exception) {
            android.util.Log.e("QRCodeManager", "General exception: ${e.message}", e)
            null
        }
    }
    
    fun parseQRCodeData(qrContent: String): SignalingData? {
        return try {
            if (qrContent.startsWith("COMPRESSED:")) {
                val compressedData = qrContent.substring(11) // Remove "COMPRESSED:" prefix
                val decompressedJson = decompressString(compressedData)
                Gson().fromJson(decompressedJson, SignalingData::class.java)
            } else {
                Gson().fromJson(qrContent, SignalingData::class.java)
            }
        } catch (e: Exception) {
            android.util.Log.e("QRCodeManager", "Error parsing QR data", e)
            null
        }
    }
    
    private fun compressString(input: String): String {
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val gzipOutputStream = GZIPOutputStream(byteArrayOutputStream)
            gzipOutputStream.write(input.toByteArray())
            gzipOutputStream.close()
            Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("QRCodeManager", "Compression failed", e)
            input
        }
    }
    
    private fun decompressString(compressed: String): String {
        return try {
            val bytes = Base64.decode(compressed, Base64.NO_WRAP)
            val byteArrayInputStream = ByteArrayInputStream(bytes)
            val gzipInputStream = GZIPInputStream(byteArrayInputStream)
            gzipInputStream.readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("QRCodeManager", "Decompression failed", e)
            compressed
        }
    }
    
    private fun simplifySignalingData(data: SignalingData): SignalingData {
        // Create a simplified version by removing verbose parts of SDP
        val simplifiedSdp = simplifySdp(data.sdp)
        return SignalingData(
            type = data.type,
            sdp = simplifiedSdp,
            iceCandidates = data.iceCandidates.take(3) // Limit ICE candidates
        )
    }
    
    private fun simplifySdp(sdp: String): String {
        return try {
            val lines = sdp.split("\\r?\\n".toRegex())
            val essentialLines = lines.filter { line ->
                line.startsWith("v=") ||
                line.startsWith("o=") ||
                line.startsWith("s=") ||
                line.startsWith("t=") ||
                line.startsWith("m=") ||
                line.startsWith("c=") ||
                line.startsWith("a=rtpmap:") ||
                line.startsWith("a=sendrecv") ||
                line.startsWith("a=fingerprint") ||
                line.startsWith("a=setup") ||
                line.startsWith("a=mid") ||
                line.startsWith("a=ice-ufrag") ||
                line.startsWith("a=ice-pwd")
            }
            essentialLines.joinToString("\r\n")
        } catch (e: Exception) {
            android.util.Log.e("QRCodeManager", "SDP simplification failed", e)
            sdp.take(1000) // Fallback: just truncate
        }
    }
}