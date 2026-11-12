package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Drivable road graph in local ENU, built from an OpenStreetMap XML export. */
class RoadGraph private constructor(val ways: List<Way>) {

    class Way(val id: Long, val nodeIds: Set<Long>, val points: Array<Enu>) {
        /** Arc length from the start of the way to each vertex. */
        val cumLen: DoubleArray = DoubleArray(points.size).also { c ->
            for (i in 1 until points.size) c[i] = c[i - 1] + (points[i] - points[i - 1]).norm()
        }
    }

    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        ways.forEachIndexed { wi, w ->
            val minX = w.points.minOf { it.x }
            val maxX = w.points.maxOf { it.x }
            val minY = w.points.minOf { it.y }
            val maxY = w.points.maxOf { it.y }
            for (ix in floor(minX / CELL_M).toInt()..floor(maxX / CELL_M).toInt()) {
                for (iy in floor(minY / CELL_M).toInt()..floor(maxY / CELL_M).toInt()) {
                    grid.getOrPut(cellKey(ix, iy)) { mutableListOf() }.add(wi)
                }
            }
        }
    }

    /** Ways are connected when they share an OSM node. */
    fun connected(a: Int, b: Int): Boolean =
        a == b || ways[a].nodeIds.any { it in ways[b].nodeIds }

    /** All road edges within [radiusM] of [p], with their projections. */
    fun candidates(p: Enu, radiusM: Double): List<Candidate> {
        val wayIdx = HashSet<Int>()
        for (ix in floor((p.x - radiusM) / CELL_M).toInt()..floor((p.x + radiusM) / CELL_M).toInt()) {
            for (iy in floor((p.y - radiusM) / CELL_M).toInt()..floor((p.y + radiusM) / CELL_M).toInt()) {
                grid[cellKey(ix, iy)]?.let { wayIdx.addAll(it) }
            }
        }
        val out = ArrayList<Candidate>()
        for (wi in wayIdx) {
            val w = ways[wi]
            for (e in 0 until w.points.size - 1) {
                val a = w.points[e]
                val b = w.points[e + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len2 = dx * dx + dy * dy
                if (len2 == 0.0) continue
                val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
                val q = Enu(a.x + t * dx, a.y + t * dy)
                val dist = (p - q).norm()
                if (dist <= radiusM) {
                    val edgeLen = sqrt(len2)
                    out.add(
                        Candidate(
                            wayIndex = wi,
                            edgeIndex = e,
                            arcM = w.cumLen[e] + t * edgeLen,
                            point = q,
                            distanceM = dist,
                            bearingRad = atan2(dy, dx),
                        ),
                    )
                }
            }
        }
        return out
    }

    companion object {
        const val CELL_M = 250.0

        val DRIVABLE_HIGHWAYS = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "living_street", "service",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
        )

        private fun cellKey(ix: Int, iy: Int): Long =
            (ix.toLong() shl 32) or (iy.toLong() and 0xFFFFFFFFL)

        /** Parses an OSM XML export (`.osm`) and keeps only drivable highway ways. */
        fun fromOsmXml(xml: String, frame: LocalFrame): RoadGraph {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

            val nodes = HashMap<Long, Enu>()
            val nodeEls = doc.getElementsByTagName("node")
            for (i in 0 until nodeEls.length) {
                val e = nodeEls.item(i) as Element
                nodes[e.getAttribute("id").toLong()] = frame.toEnu(
                    LatLon(e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble()),
                )
            }

            val ways = ArrayList<Way>()
            val wayEls = doc.getElementsByTagName("way")
            for (i in 0 until wayEls.length) {
                val way = wayEls.item(i) as Element
                val tags = children(way, "tag").associate { it.getAttribute("k") to it.getAttribute("v") }
                val highway = tags["highway"] ?: continue
                if (highway !in DRIVABLE_HIGHWAYS) continue
                val refs = children(way, "nd").map { it.getAttribute("ref").toLong() }.filter { it in nodes }
                if (refs.size < 2) continue
                ways.add(
                    Way(
                        id = way.getAttribute("id").toLong(),
                        nodeIds = refs.toSet(),
                        points = refs.map { nodes.getValue(it) }.toTypedArray(),
                    ),
                )
            }
            return RoadGraph(ways)
        }

        private fun children(parent: Element, name: String): List<Element> {
            val nl = parent.childNodes
            val out = ArrayList<Element>()
            for (i in 0 until nl.length) {
                val n = nl.item(i)
                if (n is Element && n.tagName == name) out.add(n)
            }
            return out
        }
    }
}

data class Candidate(
    val wayIndex: Int,
    val edgeIndex: Int,
    /** Arc length along the way to the projection. */
    val arcM: Double,
    val point: Enu,
    val distanceM: Double,
    val bearingRad: Double,
)

data class MatchInput(
    val timestampMs: Long,
    val position: Enu,
    val sigmaM: Double,
    val headingRad: Double? = null,
    val speedMps: Double? = null,
)

data class MatchedPoint(
    val timestampMs: Long,
    val position: Enu,
    /** OSM way id, or null when no road was within search radius. */
    val wayId: Long?,
    /** True when a road on a different way was almost as likely as the chosen one. */
    val ambiguous: Boolean,
)

/**
 * Hidden Markov map matcher (Newson & Krumm style) with Viterbi decoding.
 *
 * - Emission: distance from observation to candidate edge, plus heading agreement when moving.
 *   Heading is compared undirected, so one-way restrictions are not enforced.
 * - Transition: penalty for |route distance - straight-line distance| between consecutive observations,
 *   plus a penalty when consecutive candidates are on ways that do not share a node.
 *
 * Ambiguity is reported from the local emission gap, not from the full posterior.
 */
class MapMatcher(
    private val graph: RoadGraph,
    private val headingSigmaRad: Double = Math.toRadians(30.0),
    private val betaM: Double = 20.0,
    private val unconnectedPenalty: Double = 5.0,
    private val minSpeedForHeading: Double = 3.0,
) {
    fun match(inputs: List<MatchInput>): List<MatchedPoint> {
        val n = inputs.size
        val cands = inputs.map { graph.candidates(it.position, max(3 * it.sigmaM, 30.0)) }
        val out = arrayOfNulls<MatchedPoint>(n)

        var i = 0
        while (i < n) {
            if (cands[i].isEmpty()) {
                out[i] = MatchedPoint(inputs[i].timestampMs, inputs[i].position, null, ambiguous = false)
                i++
                continue
            }
            var j = i
            while (j < n && cands[j].isNotEmpty()) j++
            decode(inputs, cands, i, j, out)
            i = j
        }
        return out.map { it!! }
    }

    private fun decode(
        inputs: List<MatchInput>,
        cands: List<List<Candidate>>,
        from: Int,
        to: Int,
        out: Array<MatchedPoint?>,
    ) {
        val len = to - from
        val score = Array(len) { DoubleArray(cands[from + it].size) }
        val back = Array(len) { IntArray(cands[from + it].size) }

        for (j in cands[from].indices) score[0][j] = emission(inputs[from], cands[from][j])

        for (t in 1 until len) {
            val prev = cands[from + t - 1]
            val cur = cands[from + t]
            val gcM = (inputs[from + t].position - inputs[from + t - 1].position).norm()
            for (j in cur.indices) {
                var best = Double.NEGATIVE_INFINITY
                var arg = 0
                for (k in prev.indices) {
                    val s = score[t - 1][k] + transition(prev[k], cur[j], gcM)
                    if (s > best) {
                        best = s
                        arg = k
                    }
                }
                score[t][j] = best + emission(inputs[from + t], cur[j])
                back[t][j] = arg
            }
        }

        var j = score[len - 1].indices.maxBy { score[len - 1][it] }
        for (t in len - 1 downTo 0) {
            val c = cands[from + t][j]
            val input = inputs[from + t]
            val ambiguous = cands[from + t].any { other ->
                other.wayIndex != c.wayIndex && emission(input, other) >= emission(input, c) - AMBIGUITY_NATS
            }
            out[from + t] = MatchedPoint(
                timestampMs = input.timestampMs,
                position = c.point,
                wayId = graph.ways[c.wayIndex].id,
                ambiguous = ambiguous,
            )
            if (t > 0) j = back[t][j]
        }
    }

    private fun emission(input: MatchInput, c: Candidate): Double {
        val z = c.distanceM / input.sigmaM
        var l = -0.5 * z * z
        val h = input.headingRad
        val speed = input.speedMps
        if (h != null && speed != null && speed >= minSpeedForHeading) {
            // Undirected angle difference in (-pi/2, pi/2].
            val d = 0.5 * atan2(sin(2 * (h - c.bearingRad)), cos(2 * (h - c.bearingRad)))
            val e = d / headingSigmaRad
            l += -0.5 * e * e
        }
        return l
    }

    private fun transition(a: Candidate, b: Candidate, gcM: Double): Double {
        val routeM = if (a.wayIndex == b.wayIndex) {
            abs(b.arcM - a.arcM)
        } else {
            (b.point - a.point).norm()
        }
        var l = -abs(routeM - gcM) / betaM
        if (a.wayIndex != b.wayIndex && !graph.connected(a.wayIndex, b.wayIndex)) {
            l -= unconnectedPenalty
        }
        return l
    }

    private companion object {
        const val AMBIGUITY_NATS = 1.0
    }
}
