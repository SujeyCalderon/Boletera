package mx.com.rutamovil.boletera.presentation.adapter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import mx.com.rutamovil.boletera.R
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.domain.model.CorteTotal
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import java.util.*

/**
 * Adaptador personalizado para la visualización de cortes y ventas en una lista.
 * Se encarga de formatear la información financiera, asignar colores según el estatus de sincronización
 * y gestionar la funcionalidad de re-impresión de tickets desde el historial.
 * 
 * @param context Contexto de la aplicación.
 * @param cortes Lista de objetos [CorteTotal] a mostrar.
 * @param mostrarBotonImpresion Flag para habilitar o deshabilitar el icono de impresión en cada fila.
 */
class CorteAdapter(context: Context, cortes: List<CorteTotal>, private val mostrarBotonImpresion: Boolean) :
    ArrayAdapter<CorteTotal>(context, 0, cortes) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var view = convertView
        val corte = getItem(position)!!

        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_corte_total, parent, false)
        }

        val textNombre = view!!.findViewById<TextView>(R.id.textNombre)
        val textInfo = view.findViewById<TextView>(R.id.textInfo)
        val btnPrint = view.findViewById<ImageView>(R.id.btnPrint)

        textNombre.text = corte.nombre

        // Resaltado en negritas para el monto total recaudado dentro de la descripción
        if (corte.info.contains("Total Recaudado:")) {
            val spannableInfo = SpannableString(corte.info)
            val start = corte.info.indexOf("Total Recaudado:")
            if (start != -1) {
                var end = corte.info.indexOf("\n", start)
                if (end == -1) end = corte.info.length
                spannableInfo.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textInfo.text = spannableInfo
        } else {
            textInfo.text = corte.info
        }

        // Estado visual según la naturaleza del registro
        if (corte.nombre == "Sin ventas") {
            textNombre.setTextColor(Color.GRAY)
            textNombre.textAlignment = View.TEXT_ALIGNMENT_CENTER
            textInfo.visibility = View.GONE
            btnPrint.visibility = View.GONE
        } else {
            textInfo.visibility = View.VISIBLE
            btnPrint.visibility = if (mostrarBotonImpresion && corte.status != 0) View.VISIBLE else View.GONE

            when (corte.status) {
                1, 2 -> textNombre.setTextColor(Color.parseColor("#388E3C")) // Sincronizado exitosamente
                3 -> textNombre.setTextColor(Color.RED) // Error de sincronización
                else -> textNombre.setTextColor(Color.BLACK) // Local / No procesado
            }
            textNombre.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

        btnPrint.setOnClickListener {
            var titleLine = corte.nombre.uppercase(Locale.getDefault())
            val lineas = corte.info.split("\n")
            var fecha = ""
            val nuevosDetalles = StringBuilder("\n")
            val maxChars = 32

            // Re-procesamiento de la información cruda para formatear el ticket de impresión
            for (linea in lineas) {
                when {
                    linea.startsWith("Fecha y hora:") -> fecha = linea.replace("Fecha y hora:", "").trim()
                    linea.startsWith("Total Recaudado:") -> {
                        var total = linea.replace("Total Recaudado:", "").replace("\$", "").trim()
                        if (total.endsWith(".00")) total = total.substring(0, total.length - 3)
                        nuevosDetalles.append(Utils.textoIzquierdaDerecha("TOTAL:", "\$$total", maxChars)).append("\n")
                    }
                    linea.contains(" - \$") -> {
                        val partes = linea.split(" - \\$")
                        if (partes.size == 2) {
                            var priceStr = partes[1].trim()
                            if (priceStr.endsWith(".00")) priceStr = priceStr.substring(0, priceStr.length - 3)
                            val left = partes[0].trim().replace(":", " x")
                            nuevosDetalles.append(Utils.textoIzquierdaDerecha(left, "\$$priceStr", maxChars)).append("\n")
                        } else nuevosDetalles.append(linea).append("\n")
                    }
                    linea.isNotBlank() -> nuevosDetalles.append(linea).append("\n")
                }
            }

            if (fecha.isNotBlank()) titleLine += "\n$fecha"
            Utils.printTicketConLogo(context as Activity, titleLine, nuevosDetalles.toString()) { actualizarEstadoConexion() }
        }

        return view
    }

    /**
     * Verifica proactivamente el estado del adaptador Bluetooth para asegurar que los comandos
     * de impresión lleguen a su destino.
     */
    private fun actualizarEstadoConexion() {
        if (BluetoothActivity.bluetoothSocket?.isConnected == true) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}
