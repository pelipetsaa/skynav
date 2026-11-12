package com.skynav.domain.routing

import com.skynav.domain.geo.LatLon

/** Parses an OSRM `route` response requested with `geometries=geojson&steps=true`. */
fun parseOsrmRoute(json: String): Route? {
    val root = parseJson(json) as? Json.Obj ?: return null
    if (root.str("code") != "Ok") return null
    val route = root.arr("routes")?.firstOrNull() as? Json.Obj ?: return null
    val coords = (route.obj("geometry")?.arr("coordinates")) ?: return null
    val points = coords.mapNotNull { pair ->
        val a = pair as? Json.Arr ?: return@mapNotNull null
        val lon = (a.items.getOrNull(0) as? Json.Num)?.n ?: return@mapNotNull null
        val lat = (a.items.getOrNull(1) as? Json.Num)?.n ?: return@mapNotNull null
        LatLon(lat, lon)
    }
    if (points.size < 2) return null
    val steps = route.arr("legs").orEmpty().flatMap { leg ->
        ((leg as? Json.Obj)?.arr("steps")).orEmpty().mapNotNull { raw ->
            val step = raw as? Json.Obj ?: return@mapNotNull null
            val maneuver = step.obj("maneuver") ?: return@mapNotNull null
            val loc = maneuver.arr("location")
            val lon = (loc?.getOrNull(0) as? Json.Num)?.n ?: return@mapNotNull null
            val lat = (loc.getOrNull(1) as? Json.Num)?.n ?: return@mapNotNull null
            val type = maneuver.str("type") ?: "continue"
            val modifier = maneuver.str("modifier")
            val name = step.str("name").orEmpty()
            RouteStep(
                maneuver = type,
                instruction = instruction(type, modifier, name),
                roadName = name,
                distanceM = step.num("distance") ?: 0.0,
                location = LatLon(lat, lon),
            )
        }
    }
    return Route(
        points = points,
        steps = steps,
        distanceM = route.num("distance") ?: 0.0,
        durationS = route.num("duration") ?: 0.0,
    )
}

data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Parses a Nominatim `search` JSON array. */
fun parseNominatim(json: String): List<Place> {
    val arr = parseJson(json) as? Json.Arr ?: return emptyList()
    return arr.items.mapNotNull { raw ->
        val o = raw as? Json.Obj ?: return@mapNotNull null
        val lat = o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
        val name = o.str("display_name") ?: return@mapNotNull null
        Place(name, lat, lon)
    }
}

internal fun instruction(type: String, modifier: String?, road: String): String {
    val turn = when (modifier) {
        "uturn" -> "Развернитесь"
        "sharp left" -> "Круто поверните налево"
        "left" -> "Поверните налево"
        "slight left" -> "Держитесь левее"
        "straight" -> "Продолжайте прямо"
        "slight right" -> "Держитесь правее"
        "right" -> "Поверните направо"
        "sharp right" -> "Круто поверните направо"
        else -> null
    }
    val base = when (type) {
        "depart" -> "Начните движение"
        "arrive" -> "Вы прибыли"
        "roundabout", "rotary", "exit roundabout", "exit rotary" -> "На кольце ${turn ?: "продолжайте"}"
        "merge" -> turn ?: "Перестройтесь"
        "fork", "end of road", "turn" -> turn ?: "Продолжайте"
        "on ramp" -> "Съезд ${turn ?: ""}".trim()
        "off ramp" -> "Выезд ${turn ?: ""}".trim()
        else -> turn ?: "Продолжайте прямо"
    }
    return if (road.isNotBlank() && type != "arrive") "$base по $road" else base
}

internal sealed interface Json {
    data class Obj(val map: Map<String, Json>) : Json
    data class Arr(val items: List<Json>) : Json
    data class Num(val n: Double) : Json
    data class Str(val s: String) : Json
    data object Null : Json
    data class Bool(val v: Boolean) : Json
}

internal fun Json.Obj.obj(key: String) = map[key] as? Json.Obj
internal fun Json.Obj.arr(key: String) = (map[key] as? Json.Arr)?.items
internal fun Json.Obj.str(key: String) = (map[key] as? Json.Str)?.s
internal fun Json.Obj.num(key: String) = (map[key] as? Json.Num)?.n

/** A JSON reader for the catalog responses. It is not a general library. */
internal fun parseJson(text: String): Json {
    val p = Parser(text)
    val v = p.value()
    return v
}

private class Parser(private val s: String) {
    private var i = 0

    fun value(): Json {
        skip()
        return when (val c = peek()) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(str())
            't' -> { i += 4; Json.Bool(true) }
            'f' -> { i += 5; Json.Bool(false) }
            'n' -> { i += 4; Json.Null }
            else -> num()
        }
    }

    private fun obj(): Json.Obj {
        expect('{')
        val map = LinkedHashMap<String, Json>()
        skip()
        if (peek() == '}') { i++; return Json.Obj(map) }
        while (true) {
            skip()
            val key = str()
            skip(); expect(':')
            map[key] = value()
            skip()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return Json.Obj(map) }
                else -> error("json object at $i")
            }
        }
    }

    private fun arr(): Json.Arr {
        expect('[')
        val items = ArrayList<Json>()
        skip()
        if (peek() == ']') { i++; return Json.Arr(items) }
        while (true) {
            items.add(value())
            skip()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return Json.Arr(items) }
                else -> error("json array at $i")
            }
        }
    }

    private fun str(): String {
        expect('"')
        val out = StringBuilder()
        while (i < s.length) {
            when (val c = s[i++]) {
                '"' -> return out.toString()
                '\\' -> out.append(escape())
                else -> out.append(c)
            }
        }
        error("unterminated string")
    }

    private fun escape(): Char = when (val c = s[i++]) {
        '"', '\\', '/' -> c
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> s.substring(i, i + 4).toInt(16).toChar().also { i += 4 }
        else -> c
    }

    private fun num(): Json.Num {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return Json.Num(s.substring(start, i).toDouble())
    }

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun peek(): Char = s[i]
    private fun expect(c: Char) { skip(); if (s[i] != c) error("expected $c at $i"); i++ }
}
