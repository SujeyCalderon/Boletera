package mx.com.rutamovil.boletera.common

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import mx.com.rutamovil.boletera.R

/**
 * Clase de utilidades generales para la aplicación.
 * Proporciona métodos para el manejo de UI, sonidos, formato de texto e impresión de tickets.
 */
object Utils {

    private var toastActual: Toast? = null

    /**
     * Muestra un mensaje Toast en pantalla, cancelando el anterior si aún está visible.
     * @param context Contexto de la aplicación.
     * @param mensaje Texto a mostrar.
     */
    fun mostrarToast(context: Context, mensaje: String) {
        toastActual?.cancel()
        toastActual = Toast.makeText(context, mensaje, Toast.LENGTH_SHORT)
        toastActual?.show()
    }

    /**
     * Reproduce un sonido de clic breve utilizando [MediaPlayer].
     * @param context Contexto de la aplicación.
     */
    fun reproducirSonidoClick(context: Context) {
        try {
            // Nota: Asegurarse de que res/raw/click exista.
            val sonido = MediaPlayer.create(context, R.raw.click)
            sonido?.apply {
                start()
                setOnCompletionListener { release() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Configura la ventana de la actividad para que utilice el modo de pantalla completa inmersiva.
     * Oculta las barras de navegación y de estado de forma persistente.
     * @param window Ventana de la actividad.
     */
    fun activarPantallaCompleta(window: Window) {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    /**
     * Genera una cadena de texto alineada a la izquierda y derecha con un ancho máximo.
     * @param izq Texto alineado a la izquierda.
     * @param der Texto alineado a la derecha.
     * @param maxChars Ancho total de la línea en caracteres.
     * @return Cadena formateada con espacios intermedios.
     */
    fun textoIzquierdaDerecha(izq: String, der: String, maxChars: Int): String {
        val totalLen = izq.length + der.length
        val espacios = maxChars - totalLen
        if (espacios <= 0) return "$izq $der"
        val sb = StringBuilder(izq)
        repeat(espacios) { sb.append(" ") }
        sb.append(der)
        return sb.toString()
    }

    /**
     * Muestra un diálogo con el diseño de un ticket, incluyendo el logo y los detalles de la venta.
     * Se utiliza como respaldo visual cuando no hay papel en la impresora.
     * @param activity Referencia a la actividad para mostrar el diálogo.
     * @param dialogTitle Título de la ventana del diálogo.
     * @param ticketTitleLine Línea de encabezado del ticket.
     * @param ticketDetails Cuerpo del ticket con la información detallada.
     */
    fun showTextDialog(activity: Activity, dialogTitle: String, ticketTitleLine: String, ticketDetails: String) {
        activity.runOnUiThread {
            // Se construye la vista del ticket programáticamente para el diálogo
            val scrollView = ScrollView(activity)
            val mainVerticalLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
            }

            val headerHorizontalLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
            }

            val logoView = ImageView(activity).apply {
                setImageResource(R.drawable.logorm)
                layoutParams = LinearLayout.LayoutParams(200, 80).apply { setMargins(0, 0, 20, 0) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            headerHorizontalLayout.addView(logoView)

            val titleView = TextView(activity).apply {
                text = ticketTitleLine.trim()
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
                textSize = 15f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            headerHorizontalLayout.addView(titleView)

            mainVerticalLayout.addView(headerHorizontalLayout)

            val detailsView = TextView(activity).apply {
                text = ticketDetails
                typeface = Typeface.MONOSPACE
                setTextColor(Color.BLACK)
                textSize = 18f
            }
            mainVerticalLayout.addView(detailsView)

            scrollView.addView(mainVerticalLayout)

            AlertDialog.Builder(activity)
                .setTitle(dialogTitle)
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /**
     * Intenta imprimir un ticket con el logo de la empresa. Si no hay papel, muestra un diálogo de respaldo.
     * @param activity Referencia a la actividad.
     * @param titleLine Título que aparecerá en el ticket físico o digital.
     * @param detailsText Texto detallado de la transacción.
     * @param onConnectionUpdate Callback opcional que se ejecuta tras una impresión exitosa o para actualizar el estado.
     */
    fun printTicketConLogo(activity: Activity, titleLine: String, detailsText: String, onConnectionUpdate: Runnable?) {
        Thread {
            // Primero se verifica físicamente el estado del papel
            if (!ImpresoraController.getInstance().verificarPapel()) {
                activity.runOnUiThread {
                    mostrarToast(activity, "⚠️ IMPRESORA SIN PAPEL")
                    showTextDialog(activity, "⚠️ SIN PAPEL", titleLine, detailsText)
                }
                return@Thread
            }

            val textoLogo = "RUTA MOVIL"
            val exito = ImpresoraController.getInstance().imprimirConLogo("$textoLogo\n$titleLine\n$detailsText", null)

            if (exito) {
                onConnectionUpdate?.let { activity.runOnUiThread(it) }
            } else {
                activity.runOnUiThread {
                    onConnectionUpdate?.run()
                    showTextDialog(activity, "ERROR DE IMPRESIÓN", titleLine, detailsText)
                }
            }
        }.start()
    }
}

