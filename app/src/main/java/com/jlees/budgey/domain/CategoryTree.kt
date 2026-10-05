package com.jlees.budgey.domain

import com.jlees.budgey.data.db.CategoryEntity

/**
 * In-memory view of the category folders. Built once per emission of the category table
 * and shared by every screen (folder browser, pickers, filters, budget roll-ups).
 */
class CategoryTree(val all: List<CategoryEntity>) {
    val byId: Map<String, CategoryEntity> = all.associateBy { it.id }
    private val childrenOf: Map<String?, List<CategoryEntity>> =
        // Orphans become roots, and so does any folder whose parents loop back to itself
        // (A inside B inside A — only possible from damaged data), so nothing ever disappears.
        all.groupBy { c -> c.parentId?.takeIf { p -> p in byId && !inLoop(c) } }

    private fun inLoop(c: CategoryEntity): Boolean {
        val seen = HashSet<String>()
        var cur = c.parentId
        while (cur != null && seen.add(cur)) {
            if (cur == c.id) return true
            cur = byId[cur]?.parentId
        }
        return false
    }

    val roots: List<CategoryEntity> get() = children(null)

    fun children(parentId: String?): List<CategoryEntity> = childrenOf[parentId].orEmpty()

    fun hasChildren(id: String): Boolean = childrenOf[id].orEmpty().isNotEmpty()

    /** Root-first chain of ancestors including the category itself. */
    fun path(id: String?): List<CategoryEntity> {
        val out = ArrayList<CategoryEntity>()
        var cur = id?.let(byId::get)
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            val c: CategoryEntity = cur
            out += c
            cur = if (inLoop(c)) null else c.parentId?.let(byId::get)
        }
        return out.asReversed()
    }

    fun pathLabel(id: String?, separator: String = " › "): String =
        path(id).joinToString(separator) { it.name }

    /** The category and all of its descendants. */
    fun subtreeIds(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (out.add(cur)) children(cur).forEach { stack.add(it.id) }
        }
        return out
    }

    /** Would moving [id] under [newParent] create a cycle? */
    fun wouldCycle(id: String, newParent: String?): Boolean =
        newParent != null && newParent in subtreeIds(id)

    /** Depth-first list with depth, for pickers. */
    fun flattened(): List<Pair<CategoryEntity, Int>> {
        val out = ArrayList<Pair<CategoryEntity, Int>>()
        fun walk(parent: String?, depth: Int) {
            children(parent).forEach {
                out += it to depth
                walk(it.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    /** Top-level ancestor (used to color/group charts by main category). */
    fun rootOf(id: String?): CategoryEntity? = path(id).firstOrNull()

    /** The child of [parentId] that contains [id] (for drill-down charts). */
    fun childOfAncestor(id: String?, parentId: String?): CategoryEntity? {
        val p = path(id)
        if (parentId == null) return p.firstOrNull()
        val idx = p.indexOfFirst { it.id == parentId }
        return if (idx >= 0) p.getOrNull(idx + 1) else null
    }

    companion object {
        val EMPTY = CategoryTree(emptyList())
    }
}
