package io.github.refined.morledger.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal data class Sql(val text: String, val parameters: List<Any?>)

// The Bind message counts parameters in a 16-bit field; 32767 is safe for every pgjdbc version.
internal const val MAX_PARAMETERS_PER_STATEMENT = 32_767

internal fun <T> List<T>.chunkedForParameters(perRow: Int): List<List<T>> = chunked(MAX_PARAMETERS_PER_STATEMENT / perRow)

internal class SqlBuilder {
    private val parameters = mutableListOf<Any?>()

    fun param(value: Any?): String {
        parameters += value
        return "?"
    }

    internal fun build(text: String): Sql {
        check(parameters.size <= MAX_PARAMETERS_PER_STATEMENT) { "${parameters.size} parameters in one statement; chunk the rows" }
        return Sql(text.trimIndent(), parameters.toList())
    }
}

internal fun sql(block: SqlBuilder.() -> String): Sql = SqlBuilder().run { build(block()) }

internal fun where(block: MutableList<String>.() -> Unit): String =
    buildList(block).joinToString(" AND ").ifEmpty { "TRUE" }

internal fun <T> Connection.query(sql: Sql, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql.text).use { statement ->
        statement.bind(sql.parameters)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }

internal fun Connection.update(sql: Sql): Int = prepareStatement(sql.text).use { statement ->
    statement.bind(sql.parameters)
    statement.executeUpdate()
}

internal fun <T> Connection.batch(items: List<T>, query: (T) -> Sql) {
    if (items.isEmpty()) return
    val first = query(items.first())
    prepareStatement(first.text).use { statement ->
        fun add(sql: Sql) {
            require(sql.text == first.text) { "batch statements must have the same SQL" }
            statement.bind(sql.parameters)
            statement.addBatch()
        }
        add(first)
        items.drop(1).forEach { add(query(it)) }
        statement.executeBatch()
    }
}

private fun PreparedStatement.bind(parameters: List<Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            null -> setNull(index + 1, Types.NULL)
            is Instant -> setObject(index + 1, value.atOffset(ZoneOffset.UTC))
            else -> setObject(index + 1, value)
        }
    }
}

internal fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
