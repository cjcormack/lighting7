package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * The tables in [tables] the live database lacks something of: the table, a column, or an index,
 * matched as Exposed matches one — by its ordered columns and uniqueness, whatever its name — and
 * ignoring SQLite's own `sqlite_` indexes, as Exposed does.
 *
 * `initDatabase` reconciles only these. `createMissingTablesAndColumns` costs seconds on this schema
 * whether or not anything is missing, because sqlite-jdbc answers its primary-key and foreign-key
 * metadata by regex over each table's DDL, once per referenced table. On SQLite it changes nothing
 * in a table this leaves out: it cannot add a constraint or alter an existing column, bar fixing the
 * case of its name, which compares as missing here. A model index this cannot compare (partial or
 * functional) counts as missing.
 */
internal fun JdbcTransaction.tablesOutOfStep(tables: List<Table>): List<Table> {
    val columns = HashMap<String, MutableSet<String>>()
    exec("SELECT m.name AS t, c.name AS c FROM sqlite_master m JOIN pragma_table_info(m.name) c WHERE m.type = 'table'") { rs ->
        while (rs.next()) columns.getOrPut(rs.getString("t")) { HashSet() }.add(rs.getString("c"))
    }

    val indexColumns = LinkedHashMap<Pair<String, String>, MutableList<String?>>()
    val indexUnique = HashMap<Pair<String, String>, Boolean>()
    exec(
        """SELECT m.name AS t, l.name AS i, l."unique" AS u, x.name AS c
           FROM sqlite_master m JOIN pragma_index_list(m.name) l JOIN pragma_index_info(l.name) x
           WHERE m.type = 'table' AND l.name NOT LIKE 'sqlite_%'
           ORDER BY m.name, l.name, x.seqno""",
    ) { rs ->
        while (rs.next()) {
            val key = rs.getString("t") to rs.getString("i")
            indexColumns.getOrPut(key) { ArrayList() }.add(rs.getString("c"))
            indexUnique[key] = rs.getInt("u") == 1
        }
    }
    val indexes = HashMap<String, MutableSet<Pair<Boolean, List<String?>>>>()
    for ((key, cols) in indexColumns) indexes.getOrPut(key.first) { HashSet() }.add(indexUnique.getValue(key) to cols)

    return tables.filter { table ->
        val name = table.nameInDatabaseCaseUnquoted()
        val live = columns[name] ?: return@filter true
        if (table.columns.any { it.nameUnquoted() !in live }) return@filter true
        val liveIndexes = indexes[name].orEmpty()
        table.indices.filterNot { it.indexName.startsWith("sqlite_") }.any { index ->
            index.filterCondition != null || index.functions != null ||
                (index.unique to index.columns.map { it.nameUnquoted() }) !in liveIndexes
        }
    }
}
