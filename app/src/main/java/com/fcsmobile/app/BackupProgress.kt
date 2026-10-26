package com.fcsmobile.app

import androidx.work.Data
import androidx.work.workDataOf

data class BackupProgress(
    val phase: String = "Préparation de la sauvegarde",
    val total: Int = 0,
    val completed: Int = 0,
    val saved: Int = 0,
    val errors: Int = 0
) {
    val summary: String
        get() = if (total > 0) "$completed / $total médias · $saved transférés" +
            if (errors > 0) " · $errors erreurs" else "" else phase

    fun data() = workDataOf("phase" to phase, "total" to total, "completed" to completed,
        "saved" to saved, "errors" to errors, "updated_at" to System.currentTimeMillis())

    companion object {
        fun from(data: Data) = BackupProgress(data.getString("phase") ?: "Préparation de la sauvegarde",
            data.getInt("total", 0), data.getInt("completed", 0), data.getInt("saved", 0), data.getInt("errors", 0))
    }
}
