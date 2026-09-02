package mx.com.rutamovil.boletera.common

import android.content.Context
import mx.com.rutamovil.boletera.data.local.ControlCortes
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Manejador de excepciones no capturadas a nivel global para la aplicación.
 * Captura cierres inesperados (crashes), guarda el stacktrace en la base de datos local para su posterior análisis
 * y delega el comportamiento final al manejador predeterminado del sistema.
 *
 * @property context Contexto de la aplicación para interactuar con la base de datos.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    /**
     * Se invoca cuando un hilo termina abruptamente debido a una excepción no capturada.
     * Registra el error en la tabla de logs antes de que la aplicación se cierre.
     *
     * @param thread El hilo donde ocurrió la excepción.
     * @param throwable La excepción no capturada.
     */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        // Persistencia del error fatal en la base de datos local
        val db = ControlCortes(context)
        db.insertarLog("CRASH FATAL: ${throwable.message}", stackTrace)

        // Continuar con el proceso de cierre normal de Android
        defaultHandler?.uncaughtException(thread, throwable)
    }
}

