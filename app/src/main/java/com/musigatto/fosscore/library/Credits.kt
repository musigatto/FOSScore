package com.musigatto.fosscore.library

/** Who a score is by: its authors/composers and its performers, by name. */
data class Credits(val composers: List<String> = emptyList(), val performers: List<String> = emptyList()) {
    /**
     * As the lists and the title bar show it under the title: "Composer · Performer", each side
     * with its names joined by commas, a performer who is also a composer named once.
     */
    val line: String
        get() {
            val others = performers.filter { performer -> composers.none { sameTag(it, performer) } }
            return listOf(composers, others).filter { it.isNotEmpty() }.joinToString(" · ") { it.joinToString(", ") }
        }

    companion object {
        /** The credits of each score, by score id, from its people rows. */
        fun bySheet(rows: List<SheetPersonName>): Map<Long, Credits> =
            rows.groupBy { it.sheetId }.mapValues { (_, people) ->
                Credits(
                    composers = people.filter { it.role == PersonRole.COMPOSER.name }.map { it.name },
                    performers = people.filter { it.role == PersonRole.PERFORMER.name }.map { it.name },
                )
            }
    }
}
