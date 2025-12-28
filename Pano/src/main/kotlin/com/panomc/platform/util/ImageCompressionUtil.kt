package com.panomc.platform.util

import org.imgscalr.Scalr
import org.slf4j.Logger
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Utility class for compressing images to meet size constraints.
 * Supports PNG and JPEG formats with progressive quality reduction and resizing.
 */
object ImageCompressionUtil {
    /**
     * Compresses an image to meet the specified maximum size limit.
     * Tries to preserve original format and quality as much as possible.
     * 
     * @param originalBytes The original image bytes
     * @param mimeType The MIME type of the original image (e.g., "image/png", "image/jpeg")
     * @param maxSizeBytes Maximum allowed size in bytes
     * @param maxDimension Maximum width or height in pixels (maintains aspect ratio)
     * @param logger Optional logger for warnings and errors (can be null)
     * @return Pair of compressed image bytes and final MIME type
     */
    fun compressImage(
        originalBytes: ByteArray,
        mimeType: String,
        maxSizeBytes: Int,
        maxDimension: Int = 800,
        logger: Logger? = null
    ): Pair<ByteArray, String> {
        // If already under size limit, return original
        if (originalBytes.size <= maxSizeBytes) {
            return originalBytes to mimeType
        }

        // Read image first (outside try block so we can use it in last resort)
        val originalImage = ImageIO.read(ByteArrayInputStream(originalBytes))
            ?: return originalBytes to mimeType
        
        // Check if image has transparency (alpha channel)
        val hasTransparency = originalImage.colorModel.hasAlpha()
        val isPng = mimeType.startsWith("image/png")
        
        // Convert to appropriate format preserving transparency
        val bufferedImage = convertToAppropriateFormat(originalImage, hasTransparency)

        try {
            // Calculate new dimensions maintaining aspect ratio
            val (newWidth, newHeight) = calculateDimensions(originalImage.width, originalImage.height, maxDimension)
            
            // Resize image using high quality method
            val resized = Scalr.resize(bufferedImage, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, newWidth, newHeight)
            
            // Try to keep original format first (especially for PNG with transparency)
            if (isPng && hasTransparency) {
                val pngResult = tryCompressPng(resized, bufferedImage, originalImage, maxSizeBytes, maxDimension)
                if (pngResult != null) {
                    return pngResult
                }
                // If PNG still exceeds limit, fall through to JPEG conversion
            }
            
            // If PNG compression didn't work or it's not PNG, try JPEG
            val jpegResult = tryCompressJpeg(
                resized, 
                bufferedImage, 
                originalImage, 
                maxSizeBytes, 
                maxDimension,
                logger
            )
            if (jpegResult != null) {
                return jpegResult
            }
        } catch (e: Exception) {
            logger?.error("Failed to compress image: ${e.message}", e)
        }
        
        // Last resort: if compression completely failed, try a very small JPEG
        val lastResortResult = tryLastResortCompression(originalImage, bufferedImage, maxSizeBytes, logger)
        if (lastResortResult != null) {
            return lastResortResult
        }
        
        // Absolute last resort: return original (should rarely happen)
        logger?.error("Could not compress image below $maxSizeBytes bytes, using original (${originalBytes.size} bytes)")
        return originalBytes to mimeType
    }
    
    /**
     * Converts BufferedImage to appropriate format preserving transparency
     */
    private fun convertToAppropriateFormat(
        originalImage: BufferedImage,
        hasTransparency: Boolean
    ): BufferedImage {
        return if (hasTransparency && originalImage.type != BufferedImage.TYPE_INT_ARGB) {
            val tmp = BufferedImage(originalImage.width, originalImage.height, BufferedImage.TYPE_INT_ARGB)
            val g = tmp.createGraphics()
            g.composite = AlphaComposite.Src
            g.drawImage(originalImage, 0, 0, null)
            g.dispose()
            tmp
        } else if (!hasTransparency && originalImage.type != BufferedImage.TYPE_INT_RGB) {
            // RGB image without transparency
            val tmp = BufferedImage(originalImage.width, originalImage.height, BufferedImage.TYPE_INT_RGB)
            val g = tmp.createGraphics()
            g.drawImage(originalImage, 0, 0, null)
            g.dispose()
            tmp
        } else {
            originalImage
        }
    }
    
    /**
     * Tries to compress PNG image while preserving transparency
     */
    private fun tryCompressPng(
        resized: BufferedImage,
        bufferedImage: BufferedImage,
        originalImage: BufferedImage,
        maxSizeBytes: Int,
        maxDimension: Int
    ): Pair<ByteArray, String>? {
        // Try PNG compression first
        val pngOutputStream = ByteArrayOutputStream()
        val pngWritten = ImageIO.write(resized, "png", pngOutputStream)
        if (pngWritten) {
            val pngBytes = pngOutputStream.toByteArray()
            
            if (pngBytes.isNotEmpty() && pngBytes.size <= maxSizeBytes) {
                return pngBytes to "image/png"
            }
            
            // If PNG is still too large, try resizing more
            if (pngBytes.size > maxSizeBytes) {
                val (newWidth, newHeight) = calculateDimensions(originalImage.width, originalImage.height, maxDimension)
                val dimensionsToTry = listOf(400, 300, 250, 200, 150, 120)
                
                for (dimension in dimensionsToTry) {
                    if (dimension >= newWidth && dimension >= newHeight) continue
                    
                    val (smallerWidth, smallerHeight) = calculateDimensions(originalImage.width, originalImage.height, dimension)
                    val smallerResized = Scalr.resize(bufferedImage, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, smallerWidth, smallerHeight)
                    val smallerPngOutputStream = ByteArrayOutputStream()
                    val smallerPngWritten = ImageIO.write(smallerResized, "png", smallerPngOutputStream)
                    if (smallerPngWritten) {
                        val smallerPngBytes = smallerPngOutputStream.toByteArray()
                        
                        if (smallerPngBytes.isNotEmpty() && smallerPngBytes.size <= maxSizeBytes) {
                            return smallerPngBytes to "image/png"
                        }
                    }
                }
            }
        }
        return null
    }
    
    /**
     * Tries to compress image as JPEG
     */
    private fun tryCompressJpeg(
        resized: BufferedImage,
        bufferedImage: BufferedImage,
        originalImage: BufferedImage,
        maxSizeBytes: Int,
        maxDimension: Int,
        logger: Logger?
    ): Pair<ByteArray, String>? {
        // JPEG doesn't support transparency, so convert ARGB to RGB first
        val jpegImage = convertToRgb(resized)
        
        // Try writing JPEG using ImageIO.write first
        val jpegOutputStream = ByteArrayOutputStream()
        var jpegWritten = ImageIO.write(jpegImage, "jpg", jpegOutputStream)
        var jpegBytes = jpegOutputStream.toByteArray()
        
        // If ImageIO.write failed, try using ImageWriter directly
        if (!jpegWritten || jpegBytes.isEmpty()) {
            try {
                val jpegWriters = ImageIO.getImageWritersByFormatName("jpg")
                if (jpegWriters.hasNext()) {
                    val jpegWriter = jpegWriters.next()
                    val imageWriteParam = jpegWriter.defaultWriteParam
                    
                    val jpegOutputStream2 = ByteArrayOutputStream()
                    val imageOutputStream = ImageIO.createImageOutputStream(jpegOutputStream2)
                    jpegWriter.output = imageOutputStream
                    jpegWriter.write(null, javax.imageio.IIOImage(jpegImage, null, null), imageWriteParam)
                    jpegWriter.dispose()
                    imageOutputStream.close()
                    
                    jpegBytes = jpegOutputStream2.toByteArray()
                    jpegWritten = jpegBytes.isNotEmpty()
                }
            } catch (e: Exception) {
                logger?.warn("Failed to write JPEG image using ImageWriter: ${e.message}")
            }
        }
        
        if (!jpegWritten || jpegBytes.isEmpty()) {
            logger?.warn("Failed to write JPEG image")
            return null
        }
        
        // If JPEG is still too large, try with quality reduction
        if (jpegBytes.size > maxSizeBytes) {
            jpegBytes = tryReduceJpegQuality(jpegImage, jpegBytes, maxSizeBytes, logger)
            
            // If still too large, try resizing smaller with aggressive compression
            if (jpegBytes.size > maxSizeBytes) {
                val (newWidth, newHeight) = calculateDimensions(originalImage.width, originalImage.height, maxDimension)
                jpegBytes = tryAggressiveJpegCompression(
                    bufferedImage,
                    originalImage,
                    jpegBytes,
                    maxSizeBytes,
                    newWidth,
                    newHeight,
                    logger
                )
            }
        }
        
        // Return compressed version if it's under limit or smaller than original
        if (jpegBytes.size <= maxSizeBytes) {
            return jpegBytes to "image/jpeg"
        }
        
        return null
    }
    
    /**
     * Converts ARGB image to RGB for JPEG encoding
     */
    private fun convertToRgb(image: BufferedImage): BufferedImage {
        return if (image.type == BufferedImage.TYPE_INT_ARGB || image.colorModel.hasAlpha()) {
            // Convert ARGB to RGB (remove alpha channel for JPEG)
            val rgbImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            val g = rgbImage.createGraphics()
            // Fill with white background for transparent areas
            g.color = Color.WHITE
            g.fillRect(0, 0, rgbImage.width, rgbImage.height)
            g.drawImage(image, 0, 0, null)
            g.dispose()
            rgbImage
        } else {
            image
        }
    }
    
    /**
     * Tries to reduce JPEG quality to meet size limit
     */
    private fun tryReduceJpegQuality(
        jpegImage: BufferedImage,
        currentBytes: ByteArray,
        maxSizeBytes: Int,
        logger: Logger?
    ): ByteArray {
        try {
            val jpegWriters = ImageIO.getImageWritersByFormatName("jpg")
            if (!jpegWriters.hasNext()) {
                logger?.warn("No JPEG writers available")
                return currentBytes
            }
            
            val jpegWriter = jpegWriters.next()
            val imageWriteParam = jpegWriter.defaultWriteParam
            
            // Check if writer supports compression
            if (imageWriteParam.canWriteCompressed()) {
                imageWriteParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                
                // Try progressively lower quality settings
                val qualityLevels = listOf(0.65f, 0.55f, 0.45f, 0.35f, 0.25f, 0.2f, 0.15f, 0.1f)
                var bestBytes = currentBytes
                
                for (quality in qualityLevels) {
                    imageWriteParam.compressionQuality = quality
                    
                    val jpegOutputStream = ByteArrayOutputStream()
                    val jpegImageOutputStream = ImageIO.createImageOutputStream(jpegOutputStream)
                    jpegWriter.output = jpegImageOutputStream
                    jpegWriter.write(null, javax.imageio.IIOImage(jpegImage, null, null), imageWriteParam)
                    jpegWriter.dispose()
                    jpegImageOutputStream.close()
                    
                    val qualityBytes = jpegOutputStream.toByteArray()
                    if (qualityBytes.isNotEmpty() && qualityBytes.size <= maxSizeBytes) {
                        return qualityBytes
                    } else if (qualityBytes.isNotEmpty() && qualityBytes.size < bestBytes.size) {
                        bestBytes = qualityBytes
                    }
                }
                
                return bestBytes
            }
        } catch (e: Exception) {
            logger?.debug("Failed to compress JPEG with quality settings: ${e.message}", e)
        }
        
        return currentBytes
    }
    
    /**
     * Tries aggressive JPEG compression with smaller dimensions
     */
    private fun tryAggressiveJpegCompression(
        bufferedImage: BufferedImage,
        originalImage: BufferedImage,
        currentBytes: ByteArray,
        maxSizeBytes: Int,
        currentWidth: Int,
        currentHeight: Int,
        logger: Logger?
    ): ByteArray {
        val dimensionsToTry = listOf(400, 300, 250, 200, 150, 120, 100)
        val aggressiveQualities = listOf(0.3f, 0.25f, 0.2f, 0.15f, 0.1f)
        var bestBytes = currentBytes
        
        try {
            for (dimension in dimensionsToTry) {
                if (dimension >= currentWidth && dimension >= currentHeight) continue
                
                val (smallerWidth, smallerHeight) = calculateDimensions(originalImage.width, originalImage.height, dimension)
                val smallerResized = Scalr.resize(bufferedImage, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, smallerWidth, smallerHeight)
                val smallerJpegImage = convertToRgb(smallerResized)
                
                // Try different quality levels for this smaller size
                for (quality in aggressiveQualities) {
                    val jpegWriters = ImageIO.getImageWritersByFormatName("jpg")
                    if (!jpegWriters.hasNext()) continue
                    
                    val jpegWriter = jpegWriters.next()
                    val imageWriteParam = jpegWriter.defaultWriteParam
                    
                    if (imageWriteParam.canWriteCompressed()) {
                        imageWriteParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        imageWriteParam.compressionQuality = quality
                        
                        val jpegOutputStream = ByteArrayOutputStream()
                        val jpegImageOutputStream = ImageIO.createImageOutputStream(jpegOutputStream)
                        jpegWriter.output = jpegImageOutputStream
                        jpegWriter.write(null, javax.imageio.IIOImage(smallerJpegImage, null, null), imageWriteParam)
                        jpegWriter.dispose()
                        jpegImageOutputStream.close()
                        
                        val smallerJpegBytes = jpegOutputStream.toByteArray()
                        if (smallerJpegBytes.isNotEmpty() && smallerJpegBytes.size <= maxSizeBytes) {
                            return smallerJpegBytes
                        } else if (smallerJpegBytes.isNotEmpty() && smallerJpegBytes.size < bestBytes.size) {
                            bestBytes = smallerJpegBytes
                        }
                    }
                }
                
                if (bestBytes.size <= maxSizeBytes) {
                    break
                }
            }
        } catch (e: Exception) {
            logger?.warn("Failed aggressive JPEG compression: ${e.message}", e)
        }
        
        return bestBytes
    }
    
    /**
     * Last resort compression: creates a very small JPEG
     */
    private fun tryLastResortCompression(
        originalImage: BufferedImage,
        bufferedImage: BufferedImage,
        maxSizeBytes: Int,
        logger: Logger?
    ): Pair<ByteArray, String>? {
        try {
            val (tinyWidth, tinyHeight) = calculateDimensions(originalImage.width, originalImage.height, 120)
            val tinyResized = Scalr.resize(bufferedImage, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, tinyWidth, tinyHeight)
            val tinyJpegImage = convertToRgb(tinyResized)
            
            val jpegWriters = ImageIO.getImageWritersByFormatName("jpg")
            if (jpegWriters.hasNext()) {
                val jpegWriter = jpegWriters.next()
                val imageWriteParam = jpegWriter.defaultWriteParam
                if (imageWriteParam.canWriteCompressed()) {
                    imageWriteParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    imageWriteParam.compressionQuality = 0.15f
                    
                    val tinyOutputStream = ByteArrayOutputStream()
                    val tinyImageOutputStream = ImageIO.createImageOutputStream(tinyOutputStream)
                    jpegWriter.output = tinyImageOutputStream
                    jpegWriter.write(null, javax.imageio.IIOImage(tinyJpegImage, null, null), imageWriteParam)
                    jpegWriter.dispose()
                    tinyImageOutputStream.close()
                    
                    val tinyBytes = tinyOutputStream.toByteArray()
                    if (tinyBytes.isNotEmpty()) {
                        logger?.warn("Using tiny compressed version (${tinyBytes.size} bytes) as last resort")
                        return tinyBytes to "image/jpeg"
                    }
                }
            }
        } catch (e: Exception) {
            logger?.error("Failed to create tiny compressed version: ${e.message}", e)
        }
        
        return null
    }
    
    /**
     * Calculates new dimensions maintaining aspect ratio
     */
    fun calculateDimensions(originalWidth: Int, originalHeight: Int, maxDimension: Int): Pair<Int, Int> {
        return if (originalWidth > originalHeight) {
            val ratio = maxDimension.toDouble() / originalWidth
            maxDimension to (originalHeight * ratio).toInt()
        } else {
            val ratio = maxDimension.toDouble() / originalHeight
            (originalWidth * ratio).toInt() to maxDimension
        }
    }
}

