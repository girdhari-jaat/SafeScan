package com.safescan.scanner

import com.safescan.domain.model.Point
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

data class Line(val m: Double, val c: Double)

object RansacHelper {

    fun fitLineRANSAC(
        points: List<Point>,
        isVertical: Boolean,
        iterations: Int = 30,
        threshold: Double = 2.0
    ): Pair<Line, Int>? {
        if (points.size < 2) return null

        var bestInliers = emptyList<Point>()
        var bestLine: Line? = null
        val earlyExitCount = points.size * 0.82

        for (iter in 0 until iterations) {
            val p1 = points[Random.nextInt(points.size)]
            val p2 = points[Random.nextInt(points.size)]
            if (p1 == p2) continue

            var m = 0.0
            var c = 0.0

            if (isVertical) {
                if (abs(p2.y - p1.y) < 0.001) continue
                m = (p2.x - p1.x) / (p2.y - p1.y)
                c = p1.x - m * p1.y
            } else {
                if (abs(p2.x - p1.x) < 0.001) continue
                m = (p2.y - p1.y) / (p2.x - p1.x)
                c = p1.y - m * p1.x
            }

            val inliers = mutableListOf<Point>()
            val norm = sqrt(1.0 + m * m)

            for (p in points) {
                val dist = if (isVertical) {
                    abs(p.x - (m * p.y + c)) / norm
                } else {
                    abs(p.y - (m * p.x + c)) / norm
                }
                if (dist < threshold) {
                    inliers.add(p)
                }
            }

            if (inliers.size > bestInliers.size) {
                bestInliers = inliers
                bestLine = Line(m, c)
                if (bestInliers.size >= earlyExitCount) break
            }
        }

        // Refined Least Squares over detected inliers
        if (bestInliers.size >= 2) {
            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            val N = bestInliers.size.toDouble()
            for (p in bestInliers) {
                sumX += p.x
                sumY += p.y
                sumXY += p.x * p.y
                sumXX += p.x * p.x
                sumYY += p.y * p.y
            }

            if (isVertical) {
                val denom = N * sumYY - sumY * sumY
                if (abs(denom) > 0.001) {
                    val m = (N * sumXY - sumX * sumY) / denom
                    val c = (sumX - m * sumY) / N
                    return Pair(Line(m, c), bestInliers.size)
                }
            } else {
                val denom = N * sumXX - sumX * sumX
                if (abs(denom) > 0.001) {
                    val m = (N * sumXY - sumX * sumY) / denom
                    val c = (sumY - m * sumX) / N
                    return Pair(Line(m, c), bestInliers.size)
                }
            }
        }

        return bestLine?.let { Pair(it, bestInliers.size) }
    }

    fun intersectLines(vertical: Line, horizontal: Line, defaultX: Double, defaultY: Double): Point {
        val denom = 1.0 - vertical.m * horizontal.m
        if (abs(denom) < 0.001) return Point(defaultX, defaultY)
        val x = (vertical.m * horizontal.c + vertical.c) / denom
        return Point(x, horizontal.m * x + horizontal.c)
    }

    fun orderPoints(pts: List<Point>): List<Point> {
        if (pts.size != 4) return pts
        val s = pts.sortedBy { it.y }
        val t = s.subList(0, 2).sortedBy { it.x }
        val b = s.subList(2, 4).sortedBy { it.x }
        return listOf(t[0], t[1], b[1], b[0])
    }

    fun getAngle(p: Point, a: Point, b: Point): Double {
        val dx1 = a.x - p.x
        val dy1 = a.y - p.y
        val dx2 = b.x - p.x
        val dy2 = b.y - p.y
        val len1 = Math.hypot(dx1, dy1)
        val len2 = Math.hypot(dx2, dy2)
        if (len1 < 1e-4 || len2 < 1e-4) return 90.0
        val dot = dx1 * dx2 + dy1 * dy2
        val cosTheta = Math.max(-1.0, Math.min(1.0, dot / (len1 * len2)))
        return Math.acos(cosTheta) * (180.0 / Math.PI)
    }

    fun refineSkewedCorner(pts: List<Point>): List<Point> {
        if (pts.size != 4) return pts

        val tl = pts[0]
        val tr = pts[1]
        val br = pts[2]
        val bl = pts[3]

        val angleTL = getAngle(tl, tr, bl)
        val angleTR = getAngle(tr, tl, br)
        val angleBR = getAngle(br, tr, bl)
        val angleBL = getAngle(bl, tl, br)

        val devTL = abs(angleTL - 90.0)
        val devTR = abs(angleTR - 90.0)
        val devBR = abs(angleBR - 90.0)
        val devBL = abs(angleBL - 90.0)

        data class Dev(val index: Int, val dev: Double, val pt: Point)

        val devs = listOf(
            Dev(0, devTL, tl),
            Dev(1, devTR, tr),
            Dev(2, devBR, br),
            Dev(3, devBL, bl)
        ).sortedByDescending { it.dev }

        val worst = devs[0]
        val secondWorst = devs[1]

        val skewRatio = worst.dev / Math.max(0.5, secondWorst.dev)
        if (worst.dev > 5.5 && (skewRatio > 1.35 || secondWorst.dev < 6.5)) {
            val result = pts.toMutableList()
            when (worst.index) {
                0 -> result[0] = Point(tr.x + bl.x - br.x, tr.y + bl.y - br.y)
                1 -> result[1] = Point(tl.x + br.x - bl.x, tl.y + br.y - bl.y)
                2 -> result[2] = Point(tr.x + bl.x - tl.x, tr.y + bl.y - tl.y)
                3 -> result[3] = Point(tl.x + br.x - tr.x, tl.y + br.y - tr.y)
            }
            return result
        }

        return pts
    }

    fun polygonArea(p: List<Point>): Double {
        var a = 0.0
        for (i in p.indices) {
            val j = (i + 1) % p.size
            a += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(a / 2.0)
    }

    fun filterBestQuad(c: List<Point>, w: Double, h: Double, isCardMode: Boolean, isManualCrop: Boolean): List<Point> {
        if (c.size != 4) return emptyList()
        val a = polygonArea(c)

        val minAreaRatio = if (isManualCrop) 0.02 else 0.08
        val maxAreaRatio = if (isManualCrop) 0.999 else (if (isCardMode) 0.94 else 0.99)

        if (a < w * h * minAreaRatio || a > w * h * maxAreaRatio) return emptyList()

        if (isManualCrop) {
            return c
        }

        val wLen = Math.hypot(c[1].x - c[0].x, c[1].y - c[0].y)
        val hLen = Math.hypot(c[3].x - c[0].x, c[3].y - c[0].y)
        val r = wLen / hLen

        val tolerance = 0.35

        var isValid = false
        if (isCardMode) {
            val cardRatio = 1.586
            isValid = abs(r - cardRatio) < tolerance || abs(r - (1.0 / cardRatio)) < tolerance
        } else {
            val ratio34 = 0.75
            val ratioA4 = 0.707
            isValid = abs(r - ratio34) < tolerance || abs(r - (1.0 / ratio34)) < tolerance ||
                    abs(r - ratioA4) < tolerance || abs(r - (1.0 / ratioA4)) < tolerance
        }

        return if (isValid) c else emptyList()
    }

    fun validateAndRepairTier1Quad(
        pts: List<Point>,
        w: Double,
        h: Double,
        est: ForecastPct?,
        isCardMode: Boolean,
        isManualCrop: Boolean
    ): List<Point> {
        if (pts.size != 4) return emptyList()

        val ordered = orderPoints(pts)

        // 1. Check side parallelism / symmetry ratio (Anti-Flare)
        val topLen = Math.hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y)
        val botLen = Math.hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
        val leftLen = Math.hypot(ordered[3].x - ordered[0].x, ordered[3].y - ordered[0].y)
        val rightLen = Math.hypot(ordered[2].x - ordered[1].x, ordered[2].y - ordered[1].y)

        val wRatio = Math.min(topLen, botLen) / Math.max(1e-3, Math.max(topLen, botLen))
        val hRatio = Math.min(leftLen, rightLen) / Math.max(1e-3, Math.max(leftLen, rightLen))

        // If one side is severely flared (> 2.5x difference with opposite side), reject severe distortion
        if (wRatio < 0.40 || hRatio < 0.40) {
            return emptyList()
        }

        // 2. Refine single flared corner using angle deviation
        var repaired = refineSkewedCorner(ordered)

        // 3. Foreground bounds check if available
        if (est != null) {
            val minX = w * Math.max(0.0, est.leftPct - 0.08)
            val maxX = w * Math.min(1.0, est.rightPct + 0.08)
            val minY = h * Math.max(0.0, est.topPct - 0.08)
            val maxY = h * Math.min(1.0, est.bottomPct + 0.08)

            var outCount = 0
            for (p in repaired) {
                if (p.x < minX || p.x > maxX || p.y < minY || p.y > maxY) {
                    outCount++
                }
            }

            // If 1 corner flared outside foreground bounds due to shadow/reflection, pull it back/repair it
            if (outCount == 1) {
                repaired = refineSkewedCorner(repaired)
            } else if (outCount >= 2) {
                // If multiple corners are outside foreground prediction, reject Tier 1 candidate
                return emptyList()
            }
        }

        return filterBestQuad(repaired, w, h, isCardMode, isManualCrop)
    }

    fun estimateForegroundPercentages(closedData: ByteArray, w: Int, h: Int): ForecastPct? {
        var bgPointsCount = 0
        var bgWhiteSum = 0
        val marginW = Math.max(1, Math.round(w * 0.03f))
        val marginH = Math.max(1, Math.round(h * 0.03f))

        for (x in 0 until w) {
            for (y in 0 until marginH) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
            for (y in h - marginH until h) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
        }
        for (y in marginH until h - marginH) {
            for (x in 0 until marginW) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
            for (x in w - marginW until w) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
        }

        val isForegroundWhite = bgPointsCount > 0 && (bgWhiteSum.toDouble() / bgPointsCount) < 0.5
        val targetVal = if (isForegroundWhite) 255 else 0

        val xCoords = ArrayList<Int>()
        val yCoords = ArrayList<Int>()

        val startY = Math.round(h * 0.04f)
        val endY = h - Math.round(h * 0.04f)
        val startX = Math.round(w * 0.04f)
        val endX = w - Math.round(w * 0.04f)

        for (y in startY until endY step 3) {
            for (x in startX until endX step 3) {
                if ((closedData[y * w + x].toInt() and 0xFF) == targetVal) {
                    xCoords.add(x)
                    yCoords.add(y)
                }
            }
        }

        if (xCoords.size < (w * h * 0.005)) {
            return null
        }

        xCoords.sort()
        yCoords.sort()

        val pctLow = 0.05
        val pctHigh = 0.95

        val minX = xCoords[(xCoords.size * pctLow).toInt()]
        val maxX = xCoords[(xCoords.size * pctHigh).toInt()]
        val minY = yCoords[(yCoords.size * pctLow).toInt()]
        val maxY = yCoords[(yCoords.size * pctHigh).toInt()]

        if (maxX - minX < w * 0.20 || maxY - minY < h * 0.20) {
            return null
        }

        return ForecastPct(
            leftPct = Math.max(0.02, minX.toDouble() / w),
            rightPct = Math.min(0.98, maxX.toDouble() / w),
            topPct = Math.max(0.02, minY.toDouble() / h),
            bottomPct = Math.min(0.98, maxY.toDouble() / h)
        )
    }

    fun findRobustForegroundBoundingBox(closedData: ByteArray, w: Int, h: Int): List<Point> {
        var bgPointsCount = 0
        var bgWhiteSum = 0
        val marginW = Math.max(1, Math.round(w * 0.03f))
        val marginH = Math.max(1, Math.round(h * 0.03f))

        for (x in 0 until w) {
            for (y in 0 until marginH) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
            for (y in h - marginH until h) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
        }
        for (y in marginH until h - marginH) {
            for (x in 0 until marginW) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
            for (x in w - marginW until w) {
                bgPointsCount++
                if ((closedData[y * w + x].toInt() and 0xFF) == 255) bgWhiteSum++
            }
        }

        val isForegroundWhite = bgPointsCount > 0 && (bgWhiteSum.toDouble() / bgPointsCount) < 0.5
        val targetVal = if (isForegroundWhite) 255 else 0

        val xCoords = ArrayList<Int>()
        val yCoords = ArrayList<Int>()
        val startX = Math.round(w * 0.04f)
        val endX = Math.round(w * 0.96f)
        val startY = Math.round(h * 0.04f)
        val endY = Math.round(h * 0.96f)

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                if ((closedData[y * w + x].toInt() and 0xFF) == targetVal) {
                    xCoords.add(x)
                    yCoords.add(y)
                }
            }
        }

        if (xCoords.size < (w * h * 0.005)) {
            return listOf(
                Point(w * 0.04, h * 0.04),
                Point(w * 0.96, h * 0.04),
                Point(w * 0.96, h * 0.96),
                Point(w * 0.04, h * 0.96)
            )
        }

        xCoords.sort()
        yCoords.sort()

        val pctLow = 0.05
        val pctHigh = 0.95

        val minX = xCoords[(xCoords.size * pctLow).toInt()]
        val maxX = xCoords[(xCoords.size * pctHigh).toInt()]
        val minY = yCoords[(yCoords.size * pctLow).toInt()]
        val maxY = yCoords[(yCoords.size * pctHigh).toInt()]

        val finalMinX = Math.max(w * 0.03, Math.min(minX.toDouble(), w * 0.35))
        val finalMaxX = Math.min(w * 0.97, Math.max(maxX.toDouble(), w * 0.65))
        val finalMinY = Math.max(h * 0.03, Math.min(minY.toDouble(), h * 0.35))
        val finalMaxY = Math.min(h * 0.97, Math.max(maxY.toDouble(), h * 0.65))

        return listOf(
            Point(finalMinX, finalMinY),
            Point(finalMaxX, finalMinY),
            Point(finalMaxX, finalMaxY),
            Point(finalMinX, finalMaxY)
        )
    }

    fun scanTarget(
        sw: Int, sh: Int,
        leftPct: Double, rightPct: Double, topPct: Double, bottomPct: Double,
        borderX: Int, borderY: Int,
        closedData: ByteArray,
        magnitudesX: FloatArray, magnitudesY: FloatArray,
        thresholdX: Double, thresholdY: Double,
        isManualCrop: Boolean, isCardMode: Boolean
    ): List<Point>? {
        val leftPoints = ArrayList<Point>()
        val rightPoints = ArrayList<Point>()
        val topPoints = ArrayList<Point>()
        val bottomPoints = ArrayList<Point>()

        val targetLeft = sw * leftPct
        val targetRight = sw * rightPct
        val targetTop = sh * topPct
        val targetBottom = sh * bottomPct

        val marginX = Math.round(sw * 0.15f)
        val marginY = Math.round(sh * 0.12f)

        val searchLeftStart = Math.max(borderX + 2, Math.round(targetLeft - marginX).toInt())
        val searchLeftEnd = Math.min(sw - borderX - 2, Math.round(targetLeft + marginX).toInt())
        val searchRightStart = Math.max(borderX + 2, Math.round(targetRight - marginX).toInt())
        val searchRightEnd = Math.min(sw - borderX - 2, Math.round(targetRight + marginX).toInt())

        val searchTopStart = Math.max(borderY + 2, Math.round(targetTop - marginY).toInt())
        val searchTopEnd = Math.min(sh - borderY - 2, Math.round(targetTop + marginY).toInt())
        val searchBottomStart = Math.max(borderY + 2, Math.round(targetBottom - marginY).toInt())
        val searchBottomEnd = Math.min(sh - borderY - 2, Math.round(targetBottom + marginY).toInt())

        val scanYStart = Math.max(0, Math.round(sh * (topPct - 0.04f)).toInt())
        val scanYEnd = Math.min(sh, Math.round(sh * (bottomPct + 0.04f)).toInt())

        val scanStride = 3

        for (y in scanYStart until scanYEnd step scanStride) {
            if (y < 0 || y >= sh) continue

            // Left transition scan
            var maxScoreL = -1.0
            var maxXL = -1
            for (x in searchLeftStart until searchLeftEnd) {
                val valX = magnitudesX[y * sw + x].toDouble()
                val isTransition = x > 0 && x < sw - 1 && (
                    closedData[y * sw + x] != closedData[y * sw + (x - 1)] ||
                    closedData[y * sw + x] != closedData[y * sw + (x + 1)]
                )
                val score = valX * (if (isTransition) 25.0 else 0.1)
                if (score > maxScoreL) {
                    maxScoreL = score
                    maxXL = x
                }
                if (isTransition && valX > thresholdX * 0.5) break
            }
            if (maxXL != -1 && maxScoreL > thresholdX * 0.45) {
                leftPoints.add(Point(maxXL.toDouble(), y.toDouble()))
            }

            // Right transition scan (Right to Left)
            var maxScoreR = -1.0
            var maxXR = -1
            for (x in searchRightEnd - 1 downTo searchRightStart) {
                val valX = magnitudesX[y * sw + x].toDouble()
                val isTransition = x > 0 && x < sw - 1 && (
                    closedData[y * sw + x] != closedData[y * sw + (x - 1)] ||
                    closedData[y * sw + x] != closedData[y * sw + (x + 1)]
                )
                val score = valX * (if (isTransition) 25.0 else 0.1)
                if (score > maxScoreR) {
                    maxScoreR = score
                    maxXR = x
                }
                if (isTransition && valX > thresholdX * 0.5) break
            }
            if (maxXR != -1 && maxScoreR > thresholdX * 0.45) {
                rightPoints.add(Point(maxXR.toDouble(), y.toDouble()))
            }
        }

        val scanXStart = Math.max(0, Math.round(sw * (leftPct - 0.04f)).toInt())
        val scanXEnd = Math.min(sw, Math.round(sw * (rightPct + 0.04f)).toInt())

        for (x in scanXStart until scanXEnd step scanStride) {
            if (x < 0 || x >= sw) continue

            // Top transition scan
            var maxScoreT = -1.0
            var foundYT = -1
            for (y in searchTopStart until searchTopEnd) {
                val valY = magnitudesY[y * sw + x].toDouble()
                val isTransition = y > 0 && y < sh - 1 && (
                    closedData[y * sw + x] != closedData[(y - 1) * sw + x] ||
                    closedData[y * sw + x] != closedData[(y + 1) * sw + x]
                )
                val score = valY * (if (isTransition) 25.0 else 0.1)
                if (score > maxScoreT) {
                    maxScoreT = score
                    foundYT = y
                }
                if (isTransition && valY > thresholdY * 0.5) break
            }
            if (foundYT != -1 && maxScoreT > thresholdY * 0.45) {
                topPoints.add(Point(x.toDouble(), foundYT.toDouble()))
            }

            // Bottom transition scan
            var maxScoreB = -1.0
            var foundYB = -1
            for (y in searchBottomEnd - 1 downTo searchBottomStart) {
                val valY = magnitudesY[y * sw + x].toDouble()
                val isTransition = y > 0 && y < sh - 1 && (
                    closedData[y * sw + x] != closedData[(y - 1) * sw + x] ||
                    closedData[y * sw + x] != closedData[(y + 1) * sw + x]
                )
                val score = valY * (if (isTransition) 25.0 else 0.1)
                if (score > maxScoreB) {
                    maxScoreB = score
                    foundYB = y
                }
                if (isTransition && valY > thresholdY * 0.5) break
            }
            if (foundYB != -1 && maxScoreB > thresholdY * 0.45) {
                bottomPoints.add(Point(x.toDouble(), foundYB.toDouble()))
            }
        }

        if (leftPoints.size >= 4 && rightPoints.size >= 4 && topPoints.size >= 4 && bottomPoints.size >= 4) {
            val isHighSparsity = leftPoints.size < 8 || rightPoints.size < 8
            val ransacIters = if (isHighSparsity) 45 else 25

            val leftRes = fitLineRANSAC(leftPoints, true, ransacIters, 2.0)
            val rightRes = fitLineRANSAC(rightPoints, true, ransacIters, 2.0)
            val topRes = fitLineRANSAC(topPoints, false, ransacIters, 2.0)
            val bottomRes = fitLineRANSAC(bottomPoints, false, ransacIters, 2.0)

            if (leftRes != null && rightRes != null && topRes != null && bottomRes != null) {
                val tl = intersectLines(leftRes.first, topRes.first, targetLeft, targetTop)
                val tr = intersectLines(rightRes.first, topRes.first, targetRight, targetTop)
                val br = intersectLines(rightRes.first, bottomRes.first, targetRight, targetBottom)
                val bl = intersectLines(leftRes.first, bottomRes.first, targetLeft, targetBottom)

                val isValidSmart = (
                    tl.x >= -sw * 0.50 && tl.x < sw * (leftPct + 0.60) && tl.y >= -sh * 0.50 && tl.y < sh * (topPct + 0.60) &&
                    tr.x > sw * (rightPct - 0.60) && tr.x <= sw * 1.50 && tr.y >= -sh * 0.50 && tr.y < sh * (topPct + 0.60) &&
                    br.x > sw * (rightPct - 0.60) && br.x <= sw * 1.50 && br.y > sh * (bottomPct - 0.60) && br.y <= sh * 1.50 &&
                    bl.x >= -sw * 0.50 && bl.x < sw * (leftPct + 0.60) && bl.y > sh * (bottomPct - 0.60) && bl.y <= sh * 1.50
                )

                val dist = { p1: Point, p2: Point -> Math.sqrt(Math.pow(p1.x - p2.x, 2.0) + Math.pow(p1.y - p2.y, 2.0)) }
                val wTop = dist(tl, tr)
                val wBottom = dist(bl, br)
                val hLeft = dist(tl, bl)
                val hRight = dist(tr, br)

                val isGoodSize = (
                    wTop >= sw * 0.15 &&
                    wBottom >= sw * 0.15 &&
                    hLeft >= sh * 0.15 &&
                    hRight >= sh * 0.15
                )

                val confAverage = (
                    (leftRes.second.toDouble() / leftPoints.size) +
                    (rightRes.second.toDouble() / rightPoints.size) +
                    (topRes.second.toDouble() / topPoints.size) +
                    (bottomRes.second.toDouble() / bottomPoints.size)
                ) / 4.0

                val confThreshold = if (isManualCrop) 0.15 else 0.32

                if (isValidSmart && isGoodSize && confAverage > confThreshold) {
                    var pts = listOf(tl, tr, br, bl).map { p ->
                        Point(
                            Math.max(0.0, Math.min(sw.toDouble(), p.x)),
                            Math.max(0.0, Math.min(sh.toDouble(), p.y))
                        )
                    }

                    pts = orderPoints(pts)
                    pts = refineSkewedCorner(pts)

                    val validQuad = filterBestQuad(pts, sw.toDouble(), sh.toDouble(), isCardMode, isManualCrop)
                    if (validQuad.size == 4) {
                        return validQuad
                    }
                }
            }
        }

        return null
    }
}

data class ForecastPct(
    val leftPct: Double,
    val rightPct: Double,
    val topPct: Double,
    val bottomPct: Double
)
