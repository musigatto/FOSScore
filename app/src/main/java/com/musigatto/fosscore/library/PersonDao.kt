package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A person on a score, as the lists need it: which score, who, and in which [PersonRole] (its name). */
data class SheetPersonName(val sheetId: Long, val personId: Long, val name: String, val role: String)

/** The people (authors, composers, performers) and the scores they are on, each in a role. */
@Dao
interface PersonDao {
    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<Person>>

    @Query("SELECT * FROM people")
    suspend fun all(): List<Person>

    @Query(
        "SELECT sp.sheetId AS sheetId, p.id AS personId, p.name AS name, sp.role AS role FROM sheet_people sp " +
            "JOIN people p ON p.id = sp.personId ORDER BY p.name COLLATE NOCASE, p.id"
    )
    fun observeSheetPeople(): Flow<List<SheetPersonName>>

    @Query(
        "SELECT p.* FROM people p JOIN sheet_people sp ON sp.personId = p.id " +
            "WHERE sp.sheetId = :sheetId AND sp.role = :role ORDER BY p.name COLLATE NOCASE, p.id"
    )
    suspend fun peopleOf(sheetId: Long, role: String): List<Person>

    /** Every person, with how many scores they are on in any role, as the people screen lists them. */
    @Query(
        "SELECT p.id AS id, p.name AS name, " +
            "(SELECT COUNT(DISTINCT sp.sheetId) FROM sheet_people sp WHERE sp.personId = p.id) AS size " +
            "FROM people p ORDER BY p.name COLLATE NOCASE, p.id"
    )
    fun observeSummaries(): Flow<List<GroupSummary>>

    @Query("SELECT name FROM people WHERE id = :id")
    fun observeName(id: Long): Flow<String?>

    /** The scores [personId] is on, in any role, each once. */
    @Query(
        "SELECT DISTINCT sh.id AS entryKey, sh.* FROM sheet_people sp JOIN sheets sh ON sh.id = sp.sheetId " +
            "WHERE sp.personId = :personId ORDER BY sh.title COLLATE NOCASE, sh.id"
    )
    fun observeAllEntries(personId: Long): Flow<List<GroupEntry>>

    /** The scores [personId] is on in [role]; each is its own entry. */
    @Query(
        "SELECT sh.id AS entryKey, sh.* FROM sheet_people sp JOIN sheets sh ON sh.id = sp.sheetId " +
            "WHERE sp.personId = :personId AND sp.role = :role ORDER BY sh.title COLLATE NOCASE, sh.id"
    )
    fun observeEntries(personId: Long, role: String): Flow<List<GroupEntry>>

    @Insert
    suspend fun insert(person: Person): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(links: List<SheetPerson>)

    @Query("DELETE FROM sheet_people WHERE sheetId = :sheetId AND role = :role")
    suspend fun unlinkRole(sheetId: Long, role: String)

    @Query("DELETE FROM sheet_people WHERE sheetId = :sheetId AND personId = :personId AND role = :role")
    suspend fun unlink(sheetId: Long, personId: Long, role: String)

    @Query("DELETE FROM sheet_people WHERE sheetId = :sheetId AND personId = :personId")
    suspend fun unlinkEveryRole(sheetId: Long, personId: Long)

    @Query("DELETE FROM sheet_people WHERE personId = :personId")
    suspend fun unlinkAll(personId: Long)

    /** [from]'s scores, each in its role, given to [into] too (a link [into] already has stays once). */
    @Query(
        "INSERT OR IGNORE INTO sheet_people (sheetId, personId, role) " +
            "SELECT sheetId, :into, role FROM sheet_people WHERE personId = :from"
    )
    suspend fun relink(from: Long, into: Long)

    // a person sorts by their name as written, until a sort name of their own is asked for
    @Query("UPDATE people SET name = :name, sortName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun delete(id: Long)
}
