package com.jlees.budgey.data

import android.content.Context
import android.net.Uri

/**
 * Who owns which receipt photo while an editor is open, so every photo is deleted the moment
 * nothing needs it — instead of waiting for the clean-up at the next app start.
 *
 * - [stored]: the photo the saved purchase/subscription has (null for a new one). It stays until a
 *   save replaces it, or the item is deleted.
 * - Photos attached during this visit are this session's own: replacing or removing one deletes it,
 *   and leaving without saving deletes them all.
 * - A new item's photo from a scan or a resume belongs to its "resume" entry ([PendingAdds]), which
 *   deletes it when you discard. Removing it from the form deletes it straight away.
 */
class ReceiptSession(private val context: Context, private val receipts: ReceiptStore, private val repo: BudgetRepository) {
    private val created = LinkedHashSet<String>()
    var stored: String? = null

    /** Saves [uri] as a new photo replacing [current]. */
    suspend fun attach(uri: Uri, current: String?): String {
        val name = receipts.importImage(uri)
        synchronized(created) { created += name }
        TempFiles.releaseCamera(context, uri)
        drop(current)
        return name
    }

    /** [name] was taken off the form (removed or replaced). */
    fun drop(name: String?) {
        if (name == null || name == stored) return // the saved item still uses it until you save
        synchronized(created) { created -= name }
        receipts.delete(name)
    }

    /** The form was saved with [saved] as its photo: the old stored photo goes if nothing else uses it. */
    suspend fun saved(saved: String?) {
        val old = stored
        stored = saved
        abandon(keep = saved)
        release(saved) // the saved item owns it now
        if (old != null && old != saved) repo.deleteReceiptIfUnused(old)
    }

    /** The item was deleted. */
    suspend fun deleted() {
        abandon(keep = null)
        stored?.let { repo.deleteReceiptIfUnused(it) }
        stored = null
    }

    /** [name] was handed to someone else (e.g. Purchase ⇄ Subscription switch); don't delete it. */
    fun release(name: String?) {
        if (name != null) synchronized(created) { created -= name }
    }

    /** Leaving without saving: photos attached this visit are thrown away, except [keep]. */
    fun abandon(keep: String?) {
        val gone = synchronized(created) {
            val kept = keep?.takeIf { it in created }
            created.filter { it != keep }.also { created.clear(); kept?.let(created::add) }
        }
        gone.forEach { if (it != stored) receipts.delete(it) }
    }
}
