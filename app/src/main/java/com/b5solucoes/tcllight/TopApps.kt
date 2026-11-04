package com.b5solucoes.tcllight

/** Choose membership by use first; a saved visual order must never lock an unused app into a slot. */
internal object TopApps {
    const val MAX_COUNT=24
    fun <T> select(ranked: List<T>,pinned: Set<String>,count: Int,order: List<String>,key:(T)->String): List<T> {
        val fixed=ranked.filter { key(it) in pinned }
        val limit=maxOf(count.coerceIn(5,MAX_COUNT),fixed.size)
        val chosen=fixed+ranked.filterNot { key(it) in pinned }.take((limit-fixed.size).coerceAtLeast(0))
        if(order.isEmpty()) return chosen
        val known=order.toSet()
        // A newly qualifying used app stays discoverable instead of being hidden behind every old position.
        return chosen.filterNot { key(it) in known }+AppOrdering.apply(chosen.filter { key(it) in known },order,key)
    }
}
