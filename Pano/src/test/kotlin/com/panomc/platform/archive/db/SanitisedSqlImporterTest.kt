package com.panomc.platform.archive.db

import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcException.Code
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SanitisedSqlImporterTest {
    private val importer = SanitisedSqlImporter("pano_")

    private fun validate(sql: String) = importer.validate(sql.reader())

    private fun rejected(sql: String) {
        val error = assertThrows<PanoArcException>(sql) { validate(sql) }

        assertEquals(Code.UNSAFE_SQL, error.code, error.message)
    }

    private fun statements(sql: String): List<List<SqlToken>> {
        val tokenizer = SqlTokenizer(sql.reader())

        return generateSequence { tokenizer.nextStatement() }.toList()
    }

    /** MySQL's own unescaping of a quoted literal, to prove the escaper round-trips. */
    private fun unquote(raw: String): String {
        val body = raw.substring(1, raw.length - 1)
        val out = StringBuilder()
        var i = 0

        while (i < body.length) {
            val ch = body[i]

            if (ch == '\\') {
                out.append(
                    when (val next = body[++i]) {
                        '0' -> '\u0000'
                        'n' -> '\n'
                        'r' -> '\r'
                        'Z' -> '\u001A'
                        't' -> '\t'
                        'b' -> '\b'
                        else -> next
                    }
                )
            } else if (ch == raw[0] && i + 1 < body.length && body[i + 1] == ch) {
                out.append(ch)
                i++
            } else {
                out.append(ch)
            }

            i++
        }

        return out.toString()
    }

    @Test
    fun `escaped strings survive the tokenizer unchanged`() {
        val values = listOf(
            "", "plain", "it's", "back\\slash", "trailing\\", "\\'", "quote\"double", "nul\u0000byte", "line\nbreak\r\n",
            "ctrl-z\u001A", "semi;colon'; DROP TABLE x; --", "emoji 😀 ğüşiöç", "/* not a comment */", "-- nor this", "# nor this"
        )

        values.forEach { value ->
            val literal = SqlLiterals.string(value)
            val tokens = statements("INSERT INTO pano_t VALUES ($literal);").single()

            assertEquals(SqlToken.Type.STRING, tokens[5].type, value)
            assertEquals(literal, tokens[5].raw)
            assertEquals(value, unquote(tokens[5].raw))
        }

        assertEquals("NULL", SqlLiterals.string(null))
        assertEquals("X''", SqlLiterals.binary(ByteArray(0)))
        assertEquals("X'00FF7F80'", SqlLiterals.binary(byteArrayOf(0, -1, 127, -128)))
        assertEquals("X'00ff'", SqlLiterals.hex("00ff"))
        assertEquals("`we``ird`", SqlLiterals.identifier("we`ird"))
        assertEquals("12.5", SqlLiterals.number("12.5"))
        assertEquals("-1e+20", SqlLiterals.number("-1e+20"))
        assertEquals("'1; DROP'", SqlLiterals.number("1; DROP"))
        assertThrows<IllegalArgumentException> { SqlLiterals.hex("0') OR (1") }
    }

    @Test
    fun `a pano-native style dump is accepted`() {
        val summary = validate(
            """
            -- Pano native dump v1
            SET time_zone = '+00:00';
            SET foreign_key_checks = 0;
            SET unique_checks = 0;

            DROP TABLE IF EXISTS `pano_user`;
            CREATE TABLE `pano_user` (
              `id` bigint(20) NOT NULL AUTO_INCREMENT,
              `username` varchar(255) NOT NULL COMMENT 'it''s; a -- comment',
              `data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`data`)),
              `created` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
              `ratio` decimal(10,2) DEFAULT 1.50,
              `group_id` bigint(20) DEFAULT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `username` (`username`),
              CONSTRAINT `fk_group` FOREIGN KEY (`group_id`) REFERENCES `pano_permission_group` (`id`) ON DELETE SET NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Users';

            INSERT INTO `pano_user` (`id`,`username`,`data`,`created`,`ratio`,`group_id`) VALUES
            (1,'a\'b;c',NULL,'2026-01-01 00:00:00.123456',-1.5,NULL),
            (2,_binary 'x',X'00FF',0x0A,b'101',TRUE);
            SET foreign_key_checks = 1;
            """.trimIndent()
        )

        assertEquals(setOf("pano_user"), summary.tables)
        assertEquals(2, summary.rows)
    }

    @Test
    fun `a mariadb-dump style dump is accepted`() {
        val summary = validate(
            """
            /*M!999999\- enable the sandbox mode */
            -- MariaDB dump 10.19
            /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
            /*!40101 SET NAMES utf8mb4 */;
            /*!40103 SET TIME_ZONE='+00:00' */;
            SET NAMES utf8mb4;
            DROP TABLE IF EXISTS `pano_post`;
            /*!40101 SET @saved_cs_client     = @@character_set_client */;
            /*!40101 SET character_set_client = utf8mb4 */;
            CREATE TABLE `pano_post` (`id` int(11) NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB;
            /*!40101 SET character_set_client = @saved_cs_client */;
            LOCK TABLES `pano_post` WRITE;
            /*!40000 ALTER TABLE `pano_post` DISABLE KEYS */;
            INSERT INTO `pano_post` VALUES (1),(2),(3);
            /*!40000 ALTER TABLE `pano_post` ENABLE KEYS */;
            UNLOCK TABLES;
            """.trimIndent()
        )

        assertEquals(setOf("pano_post"), summary.tables)
        assertEquals(3, summary.rows)
    }

    @Test
    fun `routines, triggers, views and events are rejected`() {
        rejected("CREATE PROCEDURE pano_p() BEGIN SELECT 1; END;")
        rejected("CREATE DEFINER=`root`@`%` PROCEDURE `pano_p`() SELECT 1;")
        rejected("CREATE FUNCTION pano_f() RETURNS INT RETURN 1;")
        rejected("CREATE TRIGGER pano_t BEFORE INSERT ON pano_user FOR EACH ROW SET NEW.id = 1;")
        rejected("CREATE DEFINER=`root`@`localhost` TRIGGER `pano_t` BEFORE INSERT ON `pano_user` FOR EACH ROW SET NEW.id = 1;")
        rejected("CREATE VIEW pano_v AS SELECT * FROM mysql.user;")
        rejected("CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`%` SQL SECURITY DEFINER VIEW `pano_v` AS SELECT 1;")
        rejected("CREATE EVENT pano_e ON SCHEDULE EVERY 1 DAY DO DELETE FROM pano_user;")
        rejected("CREATE OR REPLACE TABLE `pano_x` (`id` int);")
        rejected("CREATE TEMPORARY TABLE `pano_x` (`id` int);")
        rejected("CREATE SEQUENCE `pano_s`;")
    }

    @Test
    fun `privilege, file and other-statement tricks are rejected`() {
        rejected("GRANT ALL ON *.* TO 'x'@'%';")
        rejected("REVOKE ALL ON *.* FROM 'x'@'%';")
        rejected("SELECT * FROM pano_user INTO OUTFILE '/tmp/x';")
        rejected("LOAD DATA INFILE '/etc/passwd' INTO TABLE pano_user;")
        rejected("DELETE FROM pano_user;")
        rejected("UPDATE pano_user SET username = 'x';")
        rejected("ALTER TABLE pano_user ADD COLUMN x int;")
        rejected("DROP DATABASE pano;")
        rejected("DROP TABLE pano_user;")
        rejected("TRUNCATE pano_user;")
        rejected("USE mysql;")
        rejected("SET GLOBAL general_log = 1;")
        rejected("SET @@global.general_log = 1;")
        rejected("SET @x = 1;")
        rejected("SET PASSWORD = PASSWORD('x');")
        rejected("SET sql_mode = CONCAT(@@sql_mode, ',NO_BACKSLASH_ESCAPES');")
        rejected("SET foreign_key_checks = 2;")
        rejected("SET time_zone = 'Europe/Istanbul'; ")
        rejected("CALL pano_p();")
        rejected("DO SLEEP(10);")
        rejected("PREPARE s FROM 'DROP DATABASE x';")
        rejected("HANDLER pano_user OPEN;")
        rejected("DELIMITER ;;")
        rejected("LOCK TABLES `mysql`.`user` WRITE;")
    }

    @Test
    fun `other prefixes and other databases are rejected`() {
        rejected("DROP TABLE IF EXISTS `other_user`;")
        rejected("DROP TABLE IF EXISTS `pano_user`, `wp_users`;")
        rejected("CREATE TABLE `wp_users` (`id` int);")
        rejected("CREATE TABLE `mysql`.`pano_user` (`id` int);")
        rejected("CREATE TABLE mysql.pano_user (`id` int);")
        rejected("CREATE TABLE `pano_x` (`id` int, FOREIGN KEY (`id`) REFERENCES `wp_users` (`id`));")
        rejected("CREATE TABLE `pano_x` (`id` int, FOREIGN KEY (`id`) REFERENCES `mysql`.`user` (`id`));")
        rejected("CREATE TABLE `pano_x` LIKE `mysql`.`user`;")
        rejected("CREATE TABLE `pano_x` SELECT * FROM `mysql`.`user`;")
        rejected("CREATE TABLE `pano_x` (`id` int) SELECT * FROM `mysql`.`user`;")
        rejected("CREATE TABLE `pano_x` (`id` int) AS SELECT 1;")
        rejected("INSERT INTO `wp_users` VALUES (1);")
        rejected("INSERT INTO `mysql`.`pano_user` VALUES (1);")
        rejected("INSERT INTO `pano_x` VALUES (1);".replace("pano_x", "pano_x`.`y"))
        rejected("INSERT INTO `pano user` VALUES (1);")
    }

    @Test
    fun `dangerous table options are rejected`() {
        rejected("CREATE TABLE `pano_x` (`id` int) ENGINE=CONNECT TABLE_TYPE=CSV FILE_NAME='/etc/passwd';")
        rejected("CREATE TABLE `pano_x` (`id` int) ENGINE=FEDERATED CONNECTION='mysql://u@h/db/t';")
        rejected("CREATE TABLE `pano_x` (`id` int) ENGINE=InnoDB DATA DIRECTORY='/tmp';")
        rejected("CREATE TABLE `pano_x` (`id` int) PARTITION BY HASH(`id`) PARTITIONS 2;")
        rejected("CREATE TABLE `pano_x` (`id` int DEFAULT (LOAD_FILE('/etc/passwd')));")
        rejected("CREATE TABLE `pano_x` (`id` int) UNION=(`pano_a`,`pano_b`);")
        rejected("CREATE TABLE `pano_x` (`id` int) /*!50100 PARTITION BY HASH(id) */;")
        rejected("CREATE TABLE `pano_x` (`id` int));")
        rejected("CREATE TABLE `pano_x` ((`id` int);")
    }

    @Test
    fun `semicolon smuggling and non-literal values are rejected`() {
        // A statement boundary is only a top-level ';': the second statement is judged on its own.
        rejected("INSERT INTO `pano_x` VALUES ('a'); DROP DATABASE pano;")
        rejected("INSERT INTO `pano_x` VALUES ('a'''); DROP DATABASE pano; -- ');")
        rejected("INSERT INTO `pano_x` VALUES (1) /* ; */ ; GRANT ALL ON *.* TO x;")
        rejected("INSERT INTO `pano_x` VALUES (1) -- x\n; GRANT ALL ON *.* TO x;")
        rejected("INSERT INTO `pano_x` VALUES (1) /*!, (1); DROP DATABASE pano */;")
        rejected("INSERT INTO `pano_x` VALUES ((SELECT authentication_string FROM mysql.user LIMIT 1));")
        rejected("INSERT INTO `pano_x` VALUES (LOAD_FILE('/etc/passwd'));")
        rejected("INSERT INTO `pano_x` VALUES (NOW());")
        rejected("INSERT INTO `pano_x` VALUES (1) ON DUPLICATE KEY UPDATE id = (SELECT 1);")
        rejected("INSERT INTO `pano_x` SELECT * FROM mysql.user;")
        rejected("INSERT INTO `pano_x` VALUES (@x);")
        rejected("INSERT INTO `pano_x` VALUES ('unterminated);")
        rejected("INSERT INTO `pano_x` VALUES (1) /* unterminated;")
        rejected("INSERT INTO `pano_x` VALUES (X'0G');")

        // Harmless look-alikes inside strings, identifiers and comments pass.
        val summary = validate(
            "INSERT INTO `pano_x` (`a;b`) VALUES ('; DROP DATABASE pano; --'), ('/*'), (\"it\"\"s\") -- ; GRANT\n;\n" +
                    "# ; DROP DATABASE pano\n/* ; DROP DATABASE pano */ INSERT INTO pano_x VALUES (1);"
        )

        assertEquals(4, summary.rows)

        // An escaped quote keeps the would-be second statement inside the string.
        assertEquals(1, validate("INSERT INTO `pano_x` VALUES ('a\\'); DROP DATABASE pano; -- ');").rows)
    }

    @Test
    fun `statements are re-rendered without comments`() {
        val tokens = statements("INSERT /* hidden */ INTO `pano_x` -- line\n VALUES (1, 'a');").single()
        val action = importer.classify(tokens) as SanitisedSqlImporter.Action.Execute

        assertEquals("INSERT INTO `pano_x` VALUES ( 1 , 'a' )", action.sql)
        assertEquals(SanitisedSqlImporter.Action.Skip, importer.classify(statements("/*!40101 SET NAMES utf8 */;").single()))
        assertTrue(statements(";;").all { it.isEmpty() })
    }

    @Test
    fun `an empty prefix still requires plain table names and oversized statements are refused`() {
        val any = SanitisedSqlImporter("")

        assertEquals(1, any.validate("INSERT INTO `anything` VALUES (1);".reader()).rows)
        assertThrows<PanoArcException> { any.validate("INSERT INTO `mysql`.`user` VALUES (1);".reader()) }
        assertThrows<IllegalArgumentException> { SanitisedSqlImporter("pano`; --") }

        val small = SanitisedSqlImporter("pano_", maxStatementChars = 100)
        val error = assertThrows<PanoArcException> { small.validate(("INSERT INTO pano_x VALUES ('" + "a".repeat(200) + "');").reader()) }

        assertEquals(Code.UNSAFE_SQL, error.code)
    }

    @Test
    fun `create table cleanup strips auto increment and definer`() {
        assertEquals(
            "CREATE TABLE `pano_x` (\n  `id` int(11) NOT NULL AUTO_INCREMENT\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            PanoNativeDumper.cleanCreateTable(
                "CREATE TABLE `pano_x` (\n  `id` int(11) NOT NULL AUTO_INCREMENT\n) ENGINE=InnoDB AUTO_INCREMENT=42 DEFAULT CHARSET=utf8mb4"
            )
        )
        assertEquals("CREATE VIEW `v` AS SELECT 1", PanoNativeDumper.cleanCreateTable("CREATE DEFINER=`root`@`%` VIEW `v` AS SELECT 1"))
    }
}
