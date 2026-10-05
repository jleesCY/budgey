package com.jlees.budgey.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The full copy taken right before "Erase all data", kept on the phone for [DAYS] days so a mistaken
 * erase can be undone. It's pruned at every start-up (not just when Settings opens), excluded from
 * phone-to-phone transfers, and can be deleted early from Settings.
 */
object SafetyCopy {
    const val DAYS = 7

    fun file(context: Context): File = File(File(context.filesDir, "safety").apply { mkdirs() }, "before-erase.zip")

    /** The copy's date and days left, or null when there's none (an expired one is deleted). */
    fun info(context: Context, today: LocalDate = LocalDate.now()): Pair<LocalDate, Int>? {
        val f = file(context)
        if (!f.exists()) return null
        val made = Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
        val age = ChronoUnit.DAYS.between(made, today).toInt()
        if (age >= DAYS) {
            f.delete()
            return null
        }
        return made to (DAYS - age.coerceAtLeast(0))
    }

    /** Deletes an expired copy. */
    fun prune(context: Context) { info(context) }

    fun delete(context: Context): Boolean = file(context).let { !it.exists() || it.delete() }
}
