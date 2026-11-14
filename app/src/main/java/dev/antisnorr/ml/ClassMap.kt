package dev.antisnorr.ml

import android.content.Context

/**
 * Loads yamnet_class_map.csv and turns it into an ordered list of labels.
 *
 * The list is ordered by the model's output index, so `labels[38]` is the
 * "Snoring" class (AudioSet /m/01d3sd). Never hardcode a different index
 * without checking this file — several blog posts wrongly claim 418.
 */
object ClassMap {

    fun load(context: Context): List<String> {
        val labels = ArrayList<String>(521)
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = parseCsvLine(line)
                if (fields.size >= 3) labels.add(fields[2])
            }
        }
        return labels
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>(3)
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }

                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }

                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private const val ASSET = "yamnet_class_map.csv"
}
