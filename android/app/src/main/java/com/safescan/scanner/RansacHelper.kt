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
                if (abs(m) > 0.65) continue
            } else {
                if (abs(p2.x - p1.x) < 0.001) continue
                m = (p2.y - p1.y) / (p2.x - p1.x)
                c = p1.y - m * p1.x
                if (abs(m) > 0.65) continue
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
                    if (abs(m) <= 0.65) {
                        return Pair(Line(m, c), bestInliers.size)
                    }
                }
            } else {
                val denom = N * sumXX - sumX * sumX
                if (abs(denom) > 0.001) {
                    val m = (N * sumXY - sumX * sumY) / denom
                    val c = (sumY - m * sumX) / N
                    if (abs(m) <= 0.65) {
                        return Pair(Line(m, c), bestInliers.size)
                    }
                }
            }
        }

        return bestLine?.takeIf { abs(it.m) <= 0.65 }?.let { Pair(it, bestInliers.size) }
    }

    fun intersectLines(vertical: Line, horizontal: Line, defaultX: Double, defaultY: Double): Point {
        val denom = 1.0 - vertical.m * horizontal.m
        if (abs(denom) < 0.001) return Point(defaultX, defaultY)
        val x = (vertical.m * horizontal.c + vertical.c) / denom
        return Point(x, horizontal.m * x + horizontal.c)
    }

    fun orderPoints(pts: List<Point>): List<Point> {
        if (pts.size != 4) return pts

        val cx = pts.sumOf { it.x } / 4.0
        val cy = pts.sumOf { it.y } / 4.0

        val sortedByAngle = pts.sortedBy { Math.atan2(it.y - cy, it.x - cx) }

        val tlIndex = sortedByAngle.indices.minByOrNull { i ->
            val p = sortedByAngle[i]
            p.x + p.y
        } ?: 0

        val tl = sortedByAngle[tlIndex]
        val tr = sortedByAngle[(tlIndex + 1) % 4]
        val br = sortedByAngle[(tlIndex + 2) % 4]
        val bl = sortedByAngle[(tlIndex + 3) % 4]

        return listOf(tl, tr, br, bl)
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
        if (worst.dev > 4.5 && (skewRatio > 1.25 || secondWorst.dev < 5.5)) {
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

        val minAreaRatio = if (isManualCrop) 0.03 else 0.08
        val maxAreaRatio = if (isManualCrop) 0.999 else (if (isCardMode) 0.94 else 0.99)

        if (a < w * h * minAreaRatio || a > w * h * maxAreaRatio) return emptyList()

        val wLen = Math.hypot(c[1].x - c[0].x, c[1].y - c[0].y)
        val hLen = Math.hypot(c[3].x - c[0].x, c[3].y - c[0].y)
        val r = wLen / hLen

        val tolerance = if (isManualCrop) 0.45 else 0.25

        var isValid = false
        if (isCardMode) {
            val cardRatio = 1.586
            isValid = abs(r - cardRatio) < tolerance || abs(r - (1.0 / cardRatio)) < tolerance
        } else {
            val ratio34 = 0.75
            val ratioA4 = 0.707
            isValid = abs(r - ratio34) < tolerance || abs(r - (1.0 / ratio34)) < tolerance ||
                    abs(r - ratioA4) < tolerance || abs(r - (1.0 / ratioA4)) < tolerance ||
                    isManualCrop
        }

        return if (isValid) c else emptyList()
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

    private var lastGoodQuad: List<Point>? = null

    /**
     * Update the cached last good quad detected by Attempt 0 (RANSAC) for temporal smoothing.
     */
    fun updateLastGoodQuad(quad: List<Point>) {
        if (quad.size == 4) {
            lastGoodQuad = quad
        }
    }

    /**
     * Attempt 2 (Smart Inset Fallback): Temporal Smoothing + Target Based Inset.
     * Uses scaled down lastGoodQuad if available for temporal stability,
     * otherwise calculates an inset based on foreground target bounds (3.5% inset).
     */
    fun getSmartInsetQuad(
        w: Int,
        h: Int,
        targetLeft: Double = w * 0.05,
        targetRight: Double = w * 0.95,
        targetTop: Double = h * 0.05,
        targetBottom: Double = h * 0.95
    ): List<Point> {
        val result = if (lastGoodQuad != null && lastGoodQuad?.size == 4) {
            // Step 3: Scale down lastGoodQuad by 0.98 from center for temporal stability
            val quad = lastGoodQuad!!
            val centerX = quad.sumOf { it.x } / 4.0
            val centerY = quad.sumOf { it.y } / 4.0
            val scale = 0.98

            quad.map { p ->
                Point(
                    centerX + (p.x - centerX) * scale,
                    centerY + (p.y - centerY) * scale
                )
            }
        } else {
            // Step 4: Create 3.5% inset based on target bounds
            val widthRange = targetRight - targetLeft
            val heightRange = targetBottom - targetTop

            val insetX = widthRange * 0.035
            val insetY = heightRange * 0.035

            val left = targetLeft + insetX
            val right = targetRight - insetX
            val top = targetTop + insetY
            val bottom = targetBottom - insetY

            listOf(
                Point(left, top),
                Point(right, top),
                Point(right, bottom),
                Point(left, bottom)
            )
        }

        // Step 5: Always clamp points to [0, w] and [0, h]
        return orderPoints(result.map { p ->
            Point(
                p.x.coerceIn(0.0, w.toDouble()),
                p.y.coerceIn(0.0, h.toDouble())
            )
        })
    }

    /**
     * Convenience overload for getSmartInsetQuad using closedData to estimate target bounds.
     */
    fun getSmartInsetQuad(w: Int, h: Int, closedData: ByteArray?): List<Point> {
        val est = if (closedData != null) estimateForegroundPercentages(closedData, w, h) else null
        return if (est != null) {
            getSmartInsetQuad(w, h, w * est.leftPct, w * est.rightPct, h * est.topPct, h * est.bottomPct)
        } else {
            getSmartInsetQuad(w, h)
        }
    }

    /**
     * Cross product of vectors OA and OB.
     */
    private fun crossProduct(o: Point, a: Point, b: Point): Double {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    /**
     * Compute Convex Hull using Andrew's Monotone Chain algorithm.
     */
    private fun computeConvexHull(points: List<Point>): List<Point> {
        if (points.size <= 3) return points
        val sorted = points.sortedWith(Comparator { p1, p2 ->
            if (p1.x != p2.x) p1.x.compareTo(p2.x) else p1.y.compareTo(p2.y)
        })
        val n = sorted.size
        var k = 0
        val lower = Array<Point?>(2 * n) { null }

        for (i in 0 until n) {
            while (k >= 2 && crossProduct(lower[k - 2]!!, lower[k - 1]!!, sorted[i]) <= 0.0) {
                k--
            }
            lower[k++] = sorted[i]
        }

        val t = k + 1
        for (i in n - 2 downTo 0) {
            while (k >= t && crossProduct(lower[k - 2]!!, lower[k - 1]!!, sorted[i]) <= 0.0) {
                k--
            }
            lower[k++] = sorted[i]
        }

        val hull = ArrayList<Point>()
        for (i in 0 until k - 1) {
            hull.add(lower[i]!!)
        }
        return hull
    }

    /**
     * Perpendicular distance from point p to line segment ab.
     */
    private fun perpendicularDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = Math.hypot(dx, dy)
        if (len < 1e-6) return Math.hypot(p.x - a.x, p.y - a.y)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
    }

    /**
     * Recursive Ramer-Douglas-Peucker helper.
     */
    private fun rdpSimplify(points: List<Point>, start: Int, end: Int, epsilon: Double, outList: MutableList<Point>) {
        var maxDist = 0.0
        var index = start
        val pStart = points[start]
        val pEnd = points[end]

        for (i in start + 1 until end) {
            val dist = perpendicularDistance(points[i], pStart, pEnd)
            if (dist > maxDist) {
                maxDist = dist
                index = i
            }
        }

        if (maxDist > epsilon) {
            val leftList = mutableListOf<Point>()
            val rightList = mutableListOf<Point>()
            rdpSimplify(points, start, index, epsilon, leftList)
            rdpSimplify(points, index, end, epsilon, rightList)

            outList.addAll(leftList)
            if (outList.isNotEmpty() && rightList.isNotEmpty() && outList.last() == rightList.first()) {
                outList.removeAt(outList.size - 1)
            }
            outList.addAll(rightList)
        } else {
            outList.add(pStart)
            outList.add(pEnd)
        }
    }

    /**
     * Simplify closed polygon using Ramer-Douglas-Peucker.
     */
    private fun simplifyPolygonRDP(hull: List<Point>, epsilon: Double): List<Point> {
        if (hull.size <= 4) return hull

        // Find point farthest from hull[0] to split closed polygon into two open chains
        var maxDist = -1.0
        var farIdx = 0
        val p0 = hull[0]
        for (i in 1 until hull.size) {
            val d = Math.hypot(hull[i].x - p0.x, hull[i].y - p0.y)
            if (d > maxDist) {
                maxDist = d
                farIdx = i
            }
        }

        val chain1 = hull.subList(0, farIdx + 1)
        val res1 = mutableListOf<Point>()
        rdpSimplify(chain1, 0, chain1.size - 1, epsilon, res1)

        val chain2 = ArrayList<Point>()
        for (i in farIdx until hull.size) {
            chain2.add(hull[i])
        }
        chain2.add(hull[0])
        val res2 = mutableListOf<Point>()
        rdpSimplify(chain2, 0, chain2.size - 1, epsilon, res2)

        val combined = ArrayList<Point>()
        combined.addAll(res1)
        if (combined.isNotEmpty() && res2.isNotEmpty() && combined.last() == res2.first()) {
            combined.removeAt(combined.size - 1)
        }
        combined.addAll(res2)
        if (combined.isNotEmpty() && combined.last() == combined.first()) {
            combined.removeAt(combined.size - 1)
        }

        return combined
    }

    /**
     * Attempt 1: Largest Blob + 4 Corner Approximation.
     * Detects foreground color from 3% borders, finds largest 4-connected component blob (>= 0.5% area),
     * computes its convex hull, and simplifies it with Ramer-Douglas-Peucker algorithm.
     */
    fun findRobustForegroundBoundingBox(closedData: ByteArray, w: Int, h: Int): List<Point>? {
        // Step 1: Detect foreground color by checking 3% border pixels
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

        // Step 2: Scan closedData and find LARGEST connected component/blob (4-connectivity)
        val totalPixels = w * h
        val minBlobSize = (totalPixels * 0.005).toInt()
        val visited = BooleanArray(totalPixels)
        val queue = IntArray(totalPixels)

        var maxBlobSize = 0
        var bestBlobIndices: IntArray? = null

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!visited[idx] && (closedData[idx].toInt() and 0xFF) == targetVal) {
                    var head = 0
                    var tail = 0

                    queue[tail++] = idx
                    visited[idx] = true

                    while (head < tail) {
                        val curr = queue[head++]
                        val cx = curr % w
                        val cy = curr / w

                        if (cx > 0) {
                            val nIdx = curr - 1
                            if (!visited[nIdx] && (closedData[nIdx].toInt() and 0xFF) == targetVal) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                        if (cx < w - 1) {
                            val nIdx = curr + 1
                            if (!visited[nIdx] && (closedData[nIdx].toInt() and 0xFF) == targetVal) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                        if (cy > 0) {
                            val nIdx = curr - w
                            if (!visited[nIdx] && (closedData[nIdx].toInt() and 0xFF) == targetVal) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                        if (cy < h - 1) {
                            val nIdx = curr + w
                            if (!visited[nIdx] && (closedData[nIdx].toInt() and 0xFF) == targetVal) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }

                    if (tail > maxBlobSize) {
                        maxBlobSize = tail
                        bestBlobIndices = queue.copyOfRange(0, tail)
                    }
                }
            }
        }

        // Step 7: If no blob found or smaller than 0.5% area, return null
        if (bestBlobIndices == null || maxBlobSize < minBlobSize) {
            return null
        }

        // Step 3: Extract boundary points of the largest blob
        val minYForX = IntArray(w) { h }
        val maxYForX = IntArray(w) { -1 }

        for (idx in bestBlobIndices) {
            val px = idx % w
            val py = idx / w
            if (py < minYForX[px]) minYForX[px] = py
            if (py > maxYForX[px]) maxYForX[px] = py
        }

        val blobPoints = ArrayList<Point>()
        for (x in 0 until w) {
            if (minYForX[x] != h) {
                blobPoints.add(Point(x.toDouble(), minYForX[x].toDouble()))
            }
            if (maxYForX[x] != -1 && maxYForX[x] != minYForX[x]) {
                blobPoints.add(Point(x.toDouble(), maxYForX[x].toDouble()))
            }
        }

        if (blobPoints.size < 3) return null

        // Step 4: Compute convex hull of the points
        val hull = computeConvexHull(blobPoints)
        if (hull.size < 3) return null

        // Step 5: Use Ramer-Douglas-Peucker algorithm with epsilon = 0.02 * perimeter
        var perimeter = 0.0
        for (i in hull.indices) {
            val nextP = hull[(i + 1) % hull.size]
            perimeter += Math.hypot(nextP.x - hull[i].x, nextP.y - hull[i].y)
        }
        val epsilon = 0.02 * perimeter

        val approxPoly = simplifyPolygonRDP(hull, epsilon)

        // Step 6: If approximated polygon has 4 points, return them. If not, return boundingRect of hull as 4 points.
        if (approxPoly.size == 4) {
            return orderPoints(approxPoly)
        } else {
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE

            for (p in hull) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }

            val boundingBox = listOf(
                Point(minX, minY),
                Point(maxX, minY),
                Point(maxX, maxY),
                Point(minX, maxY)
            )
            return orderPoints(boundingBox)
        }
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

        val isLandscape = sw > sh
        val marginX = if (isLandscape) Math.round(sw * 0.18f) else Math.round(sw * 0.15f)
        val marginY = if (isLandscape) Math.round(sh * 0.18f) else Math.round(sh * 0.12f)

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
                val valY = magnitudesY[y * sw + x].toDouble()
                if (valY > valX * 1.5 && valX < thresholdX * 0.8) continue

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
                val valY = magnitudesY[y * sw + x].toDouble()
                if (valY > valX * 1.5 && valX < thresholdX * 0.8) continue

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
                val valX = magnitudesX[y * sw + x].toDouble()
                if (valX > valY * 1.5 && valY < thresholdY * 0.8) continue

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

            // Bottom transition scan (Bottom to Top - Outside in)
            var maxScoreB = -1.0
            var foundYB = -1
            for (y in searchBottomEnd - 1 downTo searchBottomStart) {
                val valY = magnitudesY[y * sw + x].toDouble()
                val valX = magnitudesX[y * sw + x].toDouble()
                if (valX > valY * 1.5 && valY < thresholdY * 0.8) continue

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
