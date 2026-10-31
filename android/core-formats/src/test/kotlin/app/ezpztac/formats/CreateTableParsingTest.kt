package app.ezpztac.formats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Column names are read out of the `CREATE TABLE` text SQLite keeps, which can be written in many ways. */
class CreateTableParsingTest {
    private fun names(sql: String) = parseColumns(sql).names
    private fun alias(sql: String) = parseColumns(sql).rowIdColumn

    @Test
    fun `plain columns, with types and constraints`() {
        assertEquals(listOf("ID", "NAME", "N"), names("CREATE TABLE T (ID INTEGER NOT NULL, NAME TEXT(50) DEFAULT 'x', N DOUBLE)"))
        assertEquals(listOf("A"), names("CREATE TABLE T (A)"))
        assertEquals(listOf("A", "B"), names("CREATE TABLE T(A,B)"))
        assertEquals(emptyList<String>(), names("CREATE TABLE T ()"))
        assertEquals(emptyList<String>(), names(""))
    }

    @Test
    fun `a quoted name keeps its spaces and its punctuation`() {
        assertEquals(listOf("My Col", "b", "c d", "e,f", "g\"h", "i]j"), names("""CREATE TABLE "T" ("My Col" TEXT, [b] INT, `c d` X, "e,f" Y, "g""h" Z, "i]j" W)"""))
        assertEquals(listOf("a b"), names("CREATE TABLE T ([a b] TEXT)"))
    }

    @Test
    fun `table constraints are not columns`() {
        val sql = """CREATE TABLE T (A INT, B INT, CONSTRAINT pk PRIMARY KEY (A, B), UNIQUE (B), CHECK (A > 0),
            FOREIGN KEY (B) REFERENCES U (X), PRIMARY KEY (A))"""
        assertEquals(listOf("A", "B"), names(sql))
    }

    @Test
    fun `a column whose name starts like a constraint is a column`() {
        assertEquals(listOf("CHECKED", "UNIQUEID", "PRIMARYKEY2", "CONSTRAINTS"), names("CREATE TABLE T (CHECKED INT, UNIQUEID INT, PRIMARYKEY2 INT, CONSTRAINTS INT)"))
    }

    @Test
    fun `commas inside brackets, strings and comments do not split`() {
        assertEquals(listOf("A", "B", "C"), names("CREATE TABLE T (A TEXT DEFAULT 'x,y', B NUMERIC CHECK (B IN (1, 2, 3)), C DEFAULT (max(1, 2)))"))
        assertEquals(listOf("A", "B"), names("CREATE TABLE T (A INT, -- a comment, with a comma\n B INT /* and (another, one */)"))
        assertEquals(listOf("A", "B"), names("CREATE TABLE T (A TEXT DEFAULT 'it''s, ok', B INT)"))
    }

    @Test
    fun `what follows the closing bracket is not a column`() {
        assertEquals(listOf("A", "B"), names("CREATE TABLE T (A INT, B INT) WITHOUT ROWID"))
        assertEquals(listOf("A"), names("CREATE TABLE \"a(b\" (A INT)"))
    }

    @Test
    fun `the template's own schema reads`() {
        val sql = "CREATE TABLE SYSTEM (SYSTEM_CODE LONG DEFAULT 0,SYSTEM_GROUP LONG DEFAULT 0,SYSTEM_NAME TEXT(50),EQUIPMENT_FKEY LONG DEFAULT 0," +
            "USE_ENGAGEMENT INTEGER NOT NULL,USE_DETECTION INTEGER NOT NULL,CONSTRAINT PrimaryKey PRIMARY KEY (SYSTEM_CODE))"
        assertEquals(listOf("SYSTEM_CODE", "SYSTEM_GROUP", "SYSTEM_NAME", "EQUIPMENT_FKEY", "USE_ENGAGEMENT", "USE_DETECTION"), names(sql))
        assertEquals(-1, alias(sql))                       // LONG is not INTEGER: SYSTEM_CODE is stored, not an alias
    }

    @Test
    fun `an INTEGER PRIMARY KEY is the row id, wherever it is and however it is written`() {
        assertEquals(0, alias("CREATE TABLE T (ID INTEGER PRIMARY KEY, A)"))
        assertEquals(0, alias("CREATE TABLE T (id integer primary key autoincrement, A)"))
        assertEquals(1, alias("CREATE TABLE T (A, ID INTEGER NOT NULL PRIMARY KEY)"))
        assertEquals(0, alias("CREATE TABLE T (ID INTEGER, A, PRIMARY KEY (ID))"))
        assertEquals(0, alias("CREATE TABLE T (ID INTEGER, A, CONSTRAINT pk PRIMARY KEY (id))"))
    }

    @Test
    fun `what is not a row id alias`() {
        assertEquals(-1, alias("CREATE TABLE T (ID INT PRIMARY KEY, A)"))              // INT is not INTEGER
        assertEquals(-1, alias("CREATE TABLE T (ID INTEGER, A)"))                      // no key
        assertEquals(-1, alias("CREATE TABLE T (ID INTEGER PRIMARY KEY DESC, A)"))     // SQLite's one exception
        assertEquals(-1, alias("CREATE TABLE T (ID INTEGER, A, PRIMARY KEY (ID, A))")) // composite
        assertEquals(-1, alias("CREATE TABLE T (ID INTEGER(4) PRIMARY KEY, A)"))
        assertEquals(-1, alias("CREATE TABLE T (A TEXT DEFAULT 'INTEGER PRIMARY KEY', B)"))
    }
}
