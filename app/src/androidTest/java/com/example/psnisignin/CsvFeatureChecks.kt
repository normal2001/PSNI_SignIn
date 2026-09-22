package com.example.psnisignin

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDate

/** Exercises real SQLite migrations and the serializers used by both SMB write paths. */
object CsvFeatureChecks {
    fun run(context: Context, directory: File) {
        val logger = AppLogger(context)
        fun open(name: String): DatabaseManager {
            AppConfig.DATABASE_PATH = File(directory, name).path
            return DatabaseManager(context, logger)
        }
        // Upgrade both the recent schema and older databases that already contain visiting.
        for (hasVisiting in listOf(false, true)) {
            val filename = "legacy-$hasVisiting.db"
            SQLiteDatabase.openOrCreateDatabase(File(directory, filename), null).use { legacy ->
                val extraColumn = if (hasVisiting) ", visiting TEXT NULL" else ""
                legacy.execSQL("""CREATE TABLE sign_in_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, first_name TEXT NOT NULL,
                    last_name TEXT NOT NULL, sign_in_datetime TEXT NOT NULL,
                    sign_out_datetime TEXT NULL, reason TEXT NULL $extraColumn)""")
                legacy.execSQL("INSERT INTO sign_in_records (first_name, last_name, sign_in_datetime, reason) VALUES ('Old', 'Visitor', '2026-09-22 09:00:00', 'Inspection')")
                if (hasVisiting) legacy.execSQL("UPDATE sign_in_records SET visiting = 'Historical Host'")
            }
            val upgraded = open(filename)
            val record = requireNotNull(upgraded.getRecord(1))
            check(record.reason == "Inspection")
            check(record.visiting == if (hasVisiting) "Historical Host" else null)
        }

        val db = open("csv-records.db")
        val date = LocalDate.of(2026, 9, 22)
        val host = "Alex, \"AJ\"\nTeam"
        val id = db.insertSignIn("Jo, Ann", "O\"Neil", "2026-09-22 10:00:00", "Repair\ncheck", host)
        check(db.getRecord(id)?.visiting == host)
        check(db.getActiveForDay(date).single().visiting == host)
        check(db.getRecordsBetween(date, date).single().visiting == host)
        val rows = db.getRecordsForYear(2026)
        check(rows.single().visiting == host)
        val exporter = CsvExporter(db, logger)
        val commonHeader = "sign_in_datetime,sign_out_datetime,first_name,last_name"
        check(exporter.buildCsv(rows) == "$commonHeader,visiting,reason\n" +
            "2026-09-22 10:00:00,,\"Jo, Ann\",\"O\"\"Neil\",\"Alex, \"\"AJ\"\"\nTeam\",\"Repair\ncheck\"\n")
        check(exporter.buildExportCsv(rows) == "$commonHeader\n2026-09-22 10:00:00,,\"Jo, Ann\",\"O\"\"Neil\"\n")
        check(exporter.buildCsv(emptyList()) == "$commonHeader,visiting,reason\n")
        check(exporter.buildExportCsv(emptyList()) == "$commonHeader\n")
        val unknown = rows.single().copy(firstName = "Old", lastName = "Visitor", visiting = null, reason = null)
        check(exporter.buildCsv(listOf(unknown)).endsWith("2026-09-22 10:00:00,,Old,Visitor,,\n"))
        val otherId = db.insertSignIn("Another", "Visitor", "2026-09-22 11:00:00", "Inspection", "Other")
        check(db.getRecord(otherId)?.visiting == "Other")
        check(db.adminEdit(id, "2026-09-22 10:01:00", null, "Updated reason"))
        check(db.signOut(id, "2026-09-22 12:00:00"))
        check(db.getRecord(id)?.visiting == host) { "Editing or signing out lost the saved host" }
        check(db.getRecordsForYear(2025).isEmpty())
    }
}
