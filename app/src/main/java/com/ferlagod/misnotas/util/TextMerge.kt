// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

/**
 * Fusión a tres bandas por líneas (como `diff3`): combina dos versiones de un texto que
 * partieron de una misma [base]. Si cada lado cambió líneas distintas, el resultado tiene los
 * cambios de ambos; si los dos cambiaron las mismas líneas de forma distinta, no hay fusión
 * posible y se devuelve null (la app conserva entonces las dos versiones).
 */
object TextMerge {

    /** Por encima de este tamaño no se intenta (el coste del diff es cuadrático). */
    private const val MAX_LINES = 3000

    fun merge3(base: String, local: String, remote: String): String? {
        if (local == remote) return local
        if (local == base) return remote
        if (remote == base) return local
        val b = base.split('\n')
        val l = local.split('\n')
        val r = remote.split('\n')
        if (b.size > MAX_LINES || l.size > MAX_LINES || r.size > MAX_LINES) return null

        val toLocal = matches(b, l)
        val toRemote = matches(b, r)
        val out = ArrayList<String>(maxOf(l.size, r.size))
        var ib = 0
        var il = 0
        var ir = 0
        while (true) {
            // Siguiente línea de la base que sigue igual en las dos versiones.
            var stable = ib
            while (stable < b.size && (toLocal[stable] < 0 || toRemote[stable] < 0)) stable++
            val endL = if (stable < b.size) toLocal[stable] else l.size
            val endR = if (stable < b.size) toRemote[stable] else r.size
            val chunkB = b.subList(ib, stable)
            val chunkL = l.subList(il, endL)
            val chunkR = r.subList(ir, endR)
            when {
                chunkL == chunkB -> out += chunkR
                chunkR == chunkB -> out += chunkL
                chunkL == chunkR -> out += chunkL
                else -> return null
            }
            if (stable >= b.size) break
            out += b[stable]
            ib = stable + 1
            il = endL + 1
            ir = endR + 1
        }
        return out.joinToString("\n")
    }

    /**
     * Para cada línea de [a], el índice de la línea emparejada en [b] según la subsecuencia
     * común más larga, o -1 si se borró o cambió.
     */
    private fun matches(a: List<String>, b: List<String>): IntArray {
        // Recorta el principio y el final comunes: es lo habitual y abarata mucho el cálculo.
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val result = IntArray(a.size) { -1 }
        for (i in 0 until start) result[i] = i
        for (k in 0 until a.size - endA) result[endA + k] = endB + k

        val n = endA - start
        val m = endB - start
        if (n == 0 || m == 0) return result
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[start + i] == b[start + j] -> { result[start + i] = start + j; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        return result
    }

    /** Títulos: si solo cambió uno de los lados se queda ese cambio; si cambiaron los dos, null. */
    fun mergeLine(base: String, local: String, remote: String): String? = when {
        local == remote -> local
        local == base -> remote
        remote == base -> local
        else -> null
    }
}
