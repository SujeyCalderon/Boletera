package mx.com.rutamovil.boletera.data.manager

import android.content.Context
import mx.com.rutamovil.boletera.data.local.ControlCortes
import mx.com.rutamovil.boletera.data.remote.ApiClient
import mx.com.rutamovil.boletera.presentation.ui.core.CortesActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * Utilidad encargada de recopilar los registros de errores locales y subirlos al servidor.
 * Lee los logs pendientes de la base de datos, genera un archivo temporal y lo envía
 * mediante una petición Multipart.
 */
object LogUploader {

    /**
     * Busca logs de errores que no han sido marcados como enviados, los consolida en un archivo
     * y dispara el proceso de carga al servidor.
     * @param context Contexto de la aplicación.
     */
    fun sincronizarLogsPendientes(context: Context) {
        val dbHelper = ControlCortes(context)
        val cursor = dbHelper.obtenerLogsPendientes()

        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            return
        }

        val idsPendientes = mutableListOf<Int>()
        val logFile = File(context.cacheDir, "crash_logs.txt")

        try {
            val writer = FileWriter(logFile)
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                val fecha = cursor.getString(cursor.getColumnIndexOrThrow("fecha"))
                val msg = cursor.getString(cursor.getColumnIndexOrThrow("mensaje"))
                val stack = cursor.getString(cursor.getColumnIndexOrThrow("stacktrace"))

                idsPendientes.add(id)

                // Escritura estructurada del log en el archivo de texto
                writer.append("FECHA: $fecha\n")
                writer.append("MENSAJE: $msg\n")
                writer.append("STACKTRACE:\n$stack\n")
                writer.append("--------------------------------------------------\n\n")
            } while (cursor.moveToNext())

            writer.flush()
            writer.close()
        } catch (e: IOException) {
            cursor.close()
            return
        }
        cursor.close()

        enviarArchivoAlServidor(context, logFile, idsPendientes, dbHelper)
    }

    /**
     * Realiza la petición de red para subir el archivo de logs consolidado.
     * Si la subida es exitosa, marca los logs en la DB local para evitar duplicados y borra el archivo temporal.
     * @param context Contexto de la aplicación.
     * @param logFile Archivo físico que contiene los logs.
     * @param idsPendientes Lista de IDs de los registros procesados.
     * @param dbHelper Instancia de acceso a datos para actualizar estados.
     */
    private fun enviarArchivoAlServidor(context: Context, logFile: File, idsPendientes: List<Int>, dbHelper: ControlCortes) {
        val token = CortesActivity.TokenManager.getToken(context) ?: return

        // Preparación del cuerpo multipart para la API
        val requestFile = logFile.asRequestBody("text/plain".toMediaType())
        val body = MultipartBody.Part.createFormData("log_file", logFile.name, requestFile)

        ApiClient.getApiService().subirLogErrores("Bearer $token", body).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    dbHelper.marcarLogsComoEnviados(idsPendientes)
                    if (logFile.exists()) logFile.delete()
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                // Falla de red silenciosa para no interrumpir al usuario
            }
        })
    }
}

