package mx.com.rutamovil.boletera.presentation.ui.core

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import mx.com.rutamovil.boletera.R
import mx.com.rutamovil.boletera.common.ImpresoraController
import mx.com.rutamovil.boletera.data.manager.LogUploader
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.local.ControlCortes
import mx.com.rutamovil.boletera.data.local.DatabaseHelper
import mx.com.rutamovil.boletera.data.remote.ApiClient
import mx.com.rutamovil.boletera.data.remote.dto.PartialCutRequest
import mx.com.rutamovil.boletera.data.remote.dto.TransactionSyncRequest
import mx.com.rutamovil.boletera.data.remote.dto.TransactionSyncResponse
import mx.com.rutamovil.boletera.domain.model.CorteTotal
import mx.com.rutamovil.boletera.domain.model.SaleItem
import mx.com.rutamovil.boletera.presentation.adapter.CorteAdapter
import mx.com.rutamovil.boletera.presentation.ui.auth.MainActivity
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

/**
 * Actividad encargada de la gestión de cortes parciales y totales.
 * Permite visualizar el historial de ventas, realizar arqueos de caja, sincronizar datos con el servidor
 * y generar los comprobantes físicos correspondientes.
 */
class CortesActivity : BaseStatusBluetoothActivity() {

    private lateinit var dbHelper: ControlCortes
    private lateinit var btnCorteParcial: Button
    private lateinit var btnCorteTotal: Button
    private lateinit var Sincro_Totales: LinearLayout
    private lateinit var Sincro_Parciales: LinearLayout
    private lateinit var listaTotales: ListView
    private lateinit var btnParciales: TextView
    private lateinit var btnTotales: TextView
    private lateinit var btnVentas: TextView
    private lateinit var ParcialTextView: TextView
    private lateinit var totalTextView: TextView
    private lateinit var ParcialiconView: ImageView
    private lateinit var totalIconView: ImageView
    private lateinit var calendario: ImageView

    private var userPhone: String? = null
    private var identificador: String? = null
    private var unidadRutaStr: String = "Sin Unidad"
    private var fechaSeleccionada: String = ""
    private var isProcessing = false // Flag para evitar múltiples ejecuciones concurrentes de cortes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cortes)

        dbHelper = ControlCortes(this)
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        imgEstadoConexion = findViewById(R.id.imgEstadoConexion)
        btnCorteTotal = findViewById(R.id.btnCorteTotal)
        btnCorteParcial = findViewById(R.id.btnCorteParcial)
        ParcialTextView = findViewById(R.id.sincronizar)
        ParcialiconView = findViewById(R.id.iconView)
        totalTextView = findViewById(R.id.sincronizar_totales)
        totalIconView = findViewById(R.id.iconView_totales)
        btnParciales = findViewById(R.id.btnCortesParciales)
        btnTotales = findViewById(R.id.btnCortesTotales)
        btnVentas = findViewById(R.id.btnVentas)
        calendario = findViewById(R.id.calendario)
        listaTotales = findViewById(R.id.listViewCortes)

        // Configuración de la barra de navegación inferior
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.nav_cortes
        bottomNavigationView.isItemActiveIndicatorEnabled = false
        bottomNavigationView.itemActiveIndicatorColor = ColorStateList.valueOf(Color.TRANSPARENT)

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cortes -> true
                R.id.nav_inicio -> {
                    startActivity(Intent(this, CobroActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    })
                    true
                }
                R.id.nav_conexion -> {
                    if (ImpresoraController.getInstance().estaConectada()) {
                        Utils.mostrarToast(this, "La impresora ya está conectada")
                        false
                    } else {
                        startActivity(Intent(this, BluetoothActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        })
                        true
                    }
                }
                R.id.nav_cerrarSesion -> {
                    cerrarSesion()
                    false
                }
                else -> false
            }
        }

        fechaSeleccionada = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())

        // Recuperación de metadatos del operador
        val userIdLocal = sharedPreferences.getInt("userIdLocal", -1)
        if (userIdLocal != -1) {
            val dbHelperUser = DatabaseHelper(this)
            val cursor = dbHelperUser.obtenerUsuarioPorId(userIdLocal)
            if (cursor != null && cursor.moveToFirst()) {
                identificador = cursor.getString(cursor.getColumnIndexOrThrow("identificador"))
                userPhone = cursor.getString(cursor.getColumnIndexOrThrow("phone"))
                unidadRutaStr = cursor.getString(cursor.getColumnIndexOrThrow("unidad_ruta")) ?: "Sin Unidad"
            }
            cursor?.close()
        }

        Sincro_Totales = findViewById(R.id.Sincro_Totales)
        Sincro_Parciales = findViewById(R.id.textViewWithIcon)

        actualizarEstadoConexion()
        actualizarVisibilidadBotones(View.GONE, View.GONE)
        cargaCortesTotales()
        cargaCortesParciales()
        mostrarVentas()
        colorSincronizacionParciales()
        colorSincronizacionTotales()

        btnCorteTotal.setOnClickListener { mostrarDialogoPin { EnvioCorteTotal() } }
        btnCorteParcial.setOnClickListener { mostrarDialogoPin { realizarCorteParcial() } }
        calendario.setOnClickListener { showDatePickerDialog() }

        // Filtros de visualización
        btnParciales.setOnClickListener { cargaCortesParciales(); actualizarVisibilidadBotones(View.VISIBLE, View.GONE) }
        btnTotales.setOnClickListener { cargaCortesTotales(); actualizarVisibilidadBotones(View.GONE, View.VISIBLE) }
        btnVentas.setOnClickListener { mostrarVentas(); actualizarVisibilidadBotones(View.GONE, View.GONE) }

        Sincro_Parciales.setOnClickListener { EnviarCortesNoEnviados() }
        Sincro_Totales.setOnClickListener {
            val jsonGuardado = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("jsonPendiente", null)
            if (jsonGuardado != null) reenviarCorteTotal(jsonGuardado)
            else Utils.mostrarToast(this, "No hay cortes totales pendientes")
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        Utils.mostrarToast(this, "Acción bloqueada. Utilice el menú inferior para navegar.")
    }

    /**
     * Muestra una ventana para entrada de PIN de seguridad antes de proceder con un corte.
     */
    private fun mostrarDialogoPin(accionSiAprobado: Runnable) {
        if (isProcessing) {
            Utils.mostrarToast(this, "Procesando solicitud, espere...")
            return
        }
        val inputPin = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Contraseña de cortes"
        }

        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(50, 20, 50, 0)
        }
        inputPin.layoutParams = params
        container.addView(inputPin)

        AlertDialog.Builder(this)
            .setTitle("Autorización requerida")
            .setMessage("Ingrese la contraseña para confirmar el corte.")
            .setView(container)
            .setPositiveButton("Confirmar") { _, _ ->
                if (inputPin.text.toString() == "1234") {
                    Utils.reproducirSonidoClick(this)
                    accionSiAprobado.run()
                } else {
                    Utils.mostrarToast(this, "Contraseña incorrecta")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun actualizarVisibilidadBotones(parciales: Int, totales: Int) {
        btnCorteParcial.visibility = parciales
        Sincro_Parciales.visibility = parciales
        btnCorteTotal.visibility = totales
        Sincro_Totales.visibility = totales
    }

    /**
     * Recupera y muestra las ventas individuales del día.
     */
    private fun mostrarVentas() {
        btnVentas.setTextColor(Color.WHITE)
        btnParciales.setTextColor(Color.GRAY)
        btnTotales.setTextColor(Color.GRAY)
        val ventas = dbHelper.getVentasPorFecha(fechaSeleccionada).toMutableList()
        if (ventas.isEmpty()) ventas.add(CorteTotal("Sin ventas", "", 0))
        listaTotales.adapter = CorteAdapter(this, ventas, false)
    }

    /**
     * Carga y muestra los cortes parciales realizados. Para la fecha actual,
     * también calcula y muestra el arqueo preliminar (lo acumulado sin cerrar).
     */
    private fun cargaCortesParciales() {
        btnParciales.setTextColor(Color.WHITE)
        btnTotales.setTextColor(Color.GRAY)
        btnVentas.setTextColor(Color.GRAY)

        val listaCortes = dbHelper.getCortesParcialesPorFecha(fechaSeleccionada).toMutableList()
        val hoy = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())

        if (fechaSeleccionada == hoy) {
            val cursor = dbHelper.obtenerBoletosVendidosAgrupados()
            if (cursor != null && cursor.count > 0) {
                val sb = StringBuilder()
                if (dbHelper.existenPendientesDeDiasAnteriores()) {
                    sb.append("⚠️ INCLUYE VENTAS DE DÍAS ANTERIORES\n\n")
                }

                var totalCorte = 0.0
                while (cursor.moveToNext()) {
                    val tipo = cursor.getString(cursor.getColumnIndexOrThrow("tipo"))
                    val cantidad = cursor.getInt(cursor.getColumnIndexOrThrow("cantidad"))
                    val total = cursor.getDouble(cursor.getColumnIndexOrThrow("total"))

                    var tipoImpreso = tipo
                    if (tipoImpreso.equals("PERSONA CON DISCAPACIDAD", ignoreCase = true)) tipoImpreso = "PCD"

                    val precioUnitario = (total / cantidad).toInt()
                    val tipoConPrecio = "$tipoImpreso \$$precioUnitario"

                    sb.append(tipoConPrecio).append(" x ").append(cantidad).append(" - \$").append(total.toInt()).append("\n")
                    totalCorte += total
                }
                sb.append("Total Recaudado: \$").append(totalCorte.toInt())

                val proxCorte = obtenerNumeroCorteParcial()
                var titulo = "Corte Parcial No realizado #$proxCorte"
                if (dbHelper.existenPendientesDeDiasAnteriores()) titulo = "⚠️ $titulo (CON PENDIENTES)"

                listaCortes.add(0, CorteTotal(titulo, sb.toString(), 0))
            }
            cursor?.close()
        }
        listaTotales.adapter = CorteAdapter(this, listaCortes, true)
    }

    /**
     * Carga y muestra los cortes totales.
     */
    private fun cargaCortesTotales() {
        btnTotales.setTextColor(Color.WHITE)
        btnParciales.setTextColor(Color.GRAY)
        btnVentas.setTextColor(Color.GRAY)

        val listaCortes = dbHelper.getCortesPorFecha(fechaSeleccionada).toMutableList()
        val hoy = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())

        if (fechaSeleccionada == hoy) {
            val cursor = dbHelper.getResumenCortesParciales()
            if (cursor != null && cursor.moveToFirst()) {
                val sb = StringBuilder()
                var granTotal = 0.0
                do {
                    val tipo = cursor.getString(cursor.getColumnIndexOrThrow("tipo"))
                    val cant = cursor.getInt(cursor.getColumnIndexOrThrow("totalPasajeros"))
                    val sub = cursor.getDouble(cursor.getColumnIndexOrThrow("totalRecaudado"))

                    var tipoImpreso = tipo
                    if (tipoImpreso.equals("PERSONA CON DISCAPACIDAD", ignoreCase = true)) tipoImpreso = "PCD"

                    val precioUnitario = (sub / cant).toInt()
                    if (!tipoImpreso.contains("\$")) tipoImpreso = "$tipoImpreso \$$precioUnitario"

                    sb.append(tipoImpreso).append(" x ").append(cant).append(" - \$").append(sub.toInt()).append("\n")
                    granTotal += sub
                } while (cursor.moveToNext())
                sb.append("Total Recaudado: \$").append(granTotal.toInt())

                val proxTotal = obtenerNumeroCorteTotal()
                listaCortes.add(0, CorteTotal("Corte Total No realizado #$proxTotal", sb.toString(), 0))
            }
            cursor?.close()
        }
        listaTotales.adapter = CorteAdapter(this, listaCortes, true)
    }

    /**
     * Proceso de cierre parcial: totaliza ventas actuales, genera ticket y envía a la API.
     */
    private fun realizarCorteParcial() {
        if (isProcessing) return
        isProcessing = true
        btnCorteParcial.isEnabled = false

        Utils.reproducirSonidoClick(this)
        if (dbHelper.getVentas().isEmpty()) {
            Utils.mostrarToast(this, "No hay ventas pendientes.")
            isProcessing = false
            btnCorteParcial.isEnabled = true
            return
        }

        val numCorte = obtenerNumeroCorteParcial()
        val fechaISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val fechaTicket = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

        val maxChars = 32
        val titleLine = "CORTE PARCIAL #$numCorte\n$fechaTicket"
        val details = StringBuilder("\n")

        var unidadCorta = unidadRutaStr
        if (unidadRutaStr.contains("-")) unidadCorta = unidadRutaStr.split("-")[0].trim()

        details.append(Utils.textoIzquierdaDerecha("Unidad:", unidadCorta, maxChars)).append("\n")

        val cursor = dbHelper.obtenerBoletosVendidosAgrupados()
        if (cursor == null || cursor.count == 0) {
            cursor?.close()
            isProcessing = false
            btnCorteParcial.isEnabled = true
            return
        }

        // Se bloquean los boletos para que pertenezcan a este corte numéricamente
        dbHelper.asignarCorteABoletosPendientes(numCorte)

        var totalBoletos = 0
        var totalCorte = 0.0
        val listaVentas = mutableListOf<SaleItem>()
        while (cursor.moveToNext()) {
            val idTarifa = cursor.getInt(cursor.getColumnIndexOrThrow("routeFareId"))
            val tipo = cursor.getString(cursor.getColumnIndexOrThrow("tipo"))
            val cantidad = cursor.getInt(cursor.getColumnIndexOrThrow("cantidad"))
            val total = cursor.getDouble(cursor.getColumnIndexOrThrow("total"))

            totalBoletos += cantidad
            var tipoImpreso = tipo
            if (tipoImpreso.equals("PERSONA CON DISCAPACIDAD", ignoreCase = true)) tipoImpreso = "PCD"

            val precioUnitario = (total / cantidad).toInt()
            val tipoConPrecio = "$tipoImpreso \$$precioUnitario"

            details.append(Utils.textoIzquierdaDerecha("$tipoConPrecio x $cantidad", "\$${total.toInt()}", maxChars)).append("\n")

            listaVentas.add(SaleItem(idTarifa, cantidad, precioUnitario))
            totalCorte += total
            dbHelper.insertarCorteParcialNuevo(numCorte, tipoConPrecio, cantidad, total, fechaISO, 0)
        }
        cursor.close()

        details.append(Utils.textoIzquierdaDerecha("\n TOTAL:", "\$${totalCorte.toInt()}", maxChars)).append("\n")
        Utils.printTicketConLogo(this, titleLine, details.toString()) { actualizarEstadoConexion() }

        guardarNumeroCorteParcial(numCorte + 1)
        enviarCorteParcialAPI(fechaISO, listaVentas, numCorte)
    }

    /**
     * Proceso de cierre total: consolida todos los parciales del día, limpia la mesa y sincroniza reporte final.
     */
    private fun EnvioCorteTotal() {
        if (isProcessing) return
        isProcessing = true
        btnCorteTotal.isEnabled = false

        Utils.reproducirSonidoClick(this)
        // No se permite corte total si hay fragmentos (parciales) sin reportar a la nube
        if (dbHelper.existenCortesPendientes()) {
            Utils.mostrarToast(this, "Sincroniza parciales primero")
            isProcessing = false
            btnCorteTotal.isEnabled = true
            return
        }

        val cursor = dbHelper.getResumenCortesParciales()
        if (cursor != null && cursor.moveToFirst()) {
            val numTotal = obtenerNumeroCorteTotal()
            val fechaISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val fechaTicket = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

            val maxChars = 32
            val titleLine = "CORTE TOTAL #$numTotal\n$fechaTicket"
            val details = StringBuilder("\n")

            var unidadCorta = unidadRutaStr
            if (unidadRutaStr.contains("-")) unidadCorta = unidadRutaStr.split("-")[0].trim()
            details.append(Utils.textoIzquierdaDerecha("Unidad:", unidadCorta, maxChars)).append("\n")

            var granTotal = 0.0
            do {
                val tipo = cursor.getString(cursor.getColumnIndexOrThrow("tipo"))
                val cant = cursor.getInt(cursor.getColumnIndexOrThrow("totalPasajeros"))
                val sub = cursor.getDouble(cursor.getColumnIndexOrThrow("totalRecaudado"))

                var tipoImpreso = tipo
                if (tipoImpreso.equals("PERSONA CON DISCAPACIDAD", ignoreCase = true)) tipoImpreso = "PCD"
                val precioUnitario = (sub / cant).toInt()
                if (!tipoImpreso.contains("\$")) tipoImpreso = "$tipoImpreso \$$precioUnitario"

                details.append(Utils.textoIzquierdaDerecha("$tipoImpreso x $cant", "\$${sub.toInt()}", maxChars)).append("\n")
                granTotal += sub
                dbHelper.insertarCorteTotalDetalle(numTotal, fechaISO, tipo, cant, sub, 1)
            } while (cursor.moveToNext())
            cursor.close()

            details.append(Utils.textoIzquierdaDerecha("\n GRAN TOTAL:", "\$${granTotal.toInt()}", maxChars)).append("\n")
            Utils.printTicketConLogo(this, titleLine, details.toString()) { actualizarEstadoConexion() }

            incrementarNumeroCorteTotal()
            cargaCortesTotales()
            enviarCorteTotalAPI(fechaISO)
        } else {
            Utils.mostrarToast(this, "No hay nuevos cortes parciales para procesar")
            cursor?.close()
            isProcessing = false
            btnCorteTotal.isEnabled = true
        }
    }
    
    private fun enviarCorteParcialAPI(timestamp: String, ventas: List<SaleItem>, numCorte: Int) {
        val token = TokenManager.getToken(this) ?: run { manejarFalloEnvio(timestamp, ventas, numCorte); return }

        val request = PartialCutRequest(identificador ?: "", timestamp, "partial", userPhone ?: "", ventas)
        ApiClient.getApiService().enviarCorteParcial("Bearer $token", request).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                isProcessing = false
                btnCorteParcial.isEnabled = true
                if (response.isSuccessful) {
                    enviarTransaccionesGPS()
                    dbHelper.actualizarEstatusBoletosPorCorte(1, numCorte)
                    dbHelper.actualizarEstatusCortesParcialesPorNumero(1, numCorte)
                    for (v in ventas) dbHelper.guardarDetalleCorte(userPhone ?: "", timestamp, v.route_fare_id, v.quantity, v.price.toDouble(), 1)
                    colorSincronizacionParciales()
                    cargaCortesParciales()
                } else manejarFalloEnvio(timestamp, ventas, numCorte)
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                isProcessing = false
                btnCorteParcial.isEnabled = true
                manejarFalloEnvio(timestamp, ventas, numCorte)
            }
        })
    }

    private fun enviarTransaccionesGPS() {
        val tokenActual = TokenManager.getToken(this) ?: return
        var routeName = "Ruta Desconocida"
        var unitNumber = unidadRutaStr
        if (unidadRutaStr.contains("-")) {
            val partes = unidadRutaStr.split("-")
            unitNumber = partes[0].trim()
            routeName = partes[1].trim()
        }

        val cashPointId = getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("cashPointId", 1)
        val transacciones = dbHelper.obtenerTransaccionesNoSincronizadas(routeName, unitNumber, cashPointId)
        if (transacciones.isEmpty()) return

        val request = TransactionSyncRequest(userPhone ?: "", transacciones)
        ApiClient.getApiService().sincronizarTransacciones("Bearer $tokenActual", request).enqueue(object : Callback<TransactionSyncResponse> {
            override fun onResponse(call: Call<TransactionSyncResponse>, response: Response<TransactionSyncResponse>) {
                if (response.isSuccessful) {
                    dbHelper.marcarTransaccionesComoEnviadasWeb()
                    LogUploader.sincronizarLogsPendientes(this@CortesActivity)
                }
            }
            override fun onFailure(call: Call<TransactionSyncResponse>, t: Throwable) {}
        })
    }

    private fun manejarFalloEnvio(timestamp: String, ventas: List<SaleItem>, numCorte: Int) {
        for (v in ventas) dbHelper.guardarDetalleCorte(userPhone ?: "", timestamp, v.route_fare_id, v.quantity, v.price.toDouble(), 3)
        dbHelper.actualizarEstatusCortesParcialesNoSincronizados(3, numCorte)
        dbHelper.actualizarEstatusBoletosPorCorte(3, numCorte)
        ParcialTextView.setTextColor(Color.RED)
        ParcialiconView.setImageResource(R.drawable.sincronizacion_necesaria)
        cargaCortesParciales()
    }

    private fun enviarCorteTotalAPI(timestamp: String) {
        val ventasEstructuradas = dbHelper.obtenerTodosLosCortesParcialesEstructurado()
        dbHelper.marcarParcialesComoTotalizados()
        try {
            val json = JSONObject().apply {
                put("device_identifier", identificador)
                put("user", userPhone)
                put("type", "final")
                put("timestamp", timestamp)
                put("reports", JSONArray(ventasEstructuradas))
            }
            val jsonString = json.toString()
            val body = jsonString.toRequestBody("application/json".toMediaTypeOrNull())
            val token = TokenManager.getToken(this)

            if (token != null) {
                ApiClient.getApiService().enviarCorteTotal("Bearer $token", body).enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        isProcessing = false
                        btnCorteTotal.isEnabled = true
                        if (response.isSuccessful) {
                            dbHelper.actualizarEstatusCorteTotal(2)
                            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().remove("jsonPendiente").apply()
                            colorSincronizacionTotales()
                            cargaCortesTotales()
                            Utils.mostrarToast(this@CortesActivity, "Corte Total subido exitosamente")
                        } else manejarFalloEnvioTotal(jsonString)
                    }
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        isProcessing = false
                        btnCorteTotal.isEnabled = true
                        manejarFalloEnvioTotal(jsonString)
                    }
                })
            } else {
                isProcessing = false
                btnCorteTotal.isEnabled = true
                manejarFalloEnvioTotal(jsonString)
            }
        } catch (e: JSONException) {
            e.printStackTrace()
            isProcessing = false
            btnCorteTotal.isEnabled = true
        }
    }

    private fun manejarFalloEnvioTotal(jsonString: String) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putString("jsonPendiente", jsonString).apply()
        dbHelper.actualizarEstatusCorteTotal(3)
        colorSincronizacionTotales()
        cargaCortesTotales()
        Utils.mostrarToast(this, "Error de red. Corte total guardado para reenvío.")
    }

    private fun reenviarCorteTotal(jsonGuardado: String) {
        Utils.reproducirSonidoClick(this)
        val token = TokenManager.getToken(this) ?: return
        val body = jsonGuardado.toRequestBody("application/json".toMediaTypeOrNull())
        ApiClient.getApiService().enviarCorteTotal("Bearer $token", body).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    dbHelper.actualizarEstatusCorteTotalNoEnviado(1)
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().remove("jsonPendiente").apply()
                    colorSincronizacionTotales()
                    cargaCortesTotales()
                    Utils.mostrarToast(this@CortesActivity, "Corte Total reenviado")
                } else Utils.mostrarToast(this@CortesActivity, "Error al reenviar")
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Utils.mostrarToast(this@CortesActivity, "Error de red al reenviar")
            }
        })
    }

    private fun colorSincronizacionTotales() {
        if (getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("jsonPendiente", null) == null) {
            totalTextView.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            totalIconView.setImageResource(R.drawable.reenvio)
        } else {
            totalTextView.setTextColor(Color.RED)
            totalIconView.setImageResource(R.drawable.sincronizacion_necesaria)
        }
    }

    private fun EnviarCortesNoEnviados() {
        Utils.reproducirSonidoClick(this)
        val pendientes = dbHelper.cortesParcialesNoEnviados()
        if (pendientes.isEmpty()) { Utils.mostrarToast(this, "Sin pendientes"); return }
        try {
            val json = JSONObject().apply {
                put("device_identifier", identificador)
                put("user", userPhone)
                put("type", "partial")
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                put("reports", JSONArray(pendientes))
            }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val token = TokenManager.getToken(this)
            if (token != null) {
                ApiClient.getApiService().enviarCorteTotal("Bearer $token", body).enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        if (response.isSuccessful) {
                            dbHelper.actualizarEstatusCortesNoEnviados(1)
                            dbHelper.actualizarEstatusCortesParcialesASincronizado(1)
                            dbHelper.actualizarEstatusBoletosReenviados(1)
                            colorSincronizacionParciales()
                            cargaCortesParciales()
                        }
                    }
                    override fun onFailure(call: Call<Void>, t: Throwable) {}
                })
            }
        } catch (e: JSONException) { e.printStackTrace() }
    }

    private fun showDatePickerDialog() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            fechaSeleccionada = String.format(Locale.getDefault(), "%02d/%02d/%04d", day, month + 1, year)
            cargaCortesTotales(); cargaCortesParciales(); mostrarVentas()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun obtenerNumeroCorteParcial() = getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("numeroCorteParcial", 1)
    private fun guardarNumeroCorteParcial(n: Int) = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putInt("numeroCorteParcial", n).apply()
    private fun obtenerNumeroCorteTotal() = getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("numero_corte_total", 1)
    private fun incrementarNumeroCorteTotal() = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putInt("numero_corte_total", obtenerNumeroCorteTotal() + 1).apply()

    private fun colorSincronizacionParciales() {
        if (dbHelper.cortesParcialesNoEnviados().isEmpty()) {
            ParcialTextView.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            ParcialiconView.setImageResource(R.drawable.reenvio)
        } else {
            ParcialTextView.setTextColor(Color.RED)
            ParcialiconView.setImageResource(R.drawable.sincronizacion_necesaria)
        }
    }

    private fun cerrarSesion() {
        var hayPendientes = dbHelper.getVentas().isNotEmpty()
        if (!hayPendientes) hayPendientes = dbHelper.cortesParcialesNoEnviados().isNotEmpty()
        val c = dbHelper.getResumenCortesParciales()
        if (c != null && c.count > 0) hayPendientes = true
        c?.close()
        if (!hayPendientes) hayPendientes = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("jsonPendiente", null) != null

        if (hayPendientes) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ TIENES CORTES PENDIENTES")
                .setMessage("Hay ventas o cortes que no se han procesado ni sincronizado con el servidor.\n\n¿Estás seguro de que deseas cerrar sesión y salir?")
                .setPositiveButton("SALIR DE TODOS MODOS") { _, _ -> ejecutarCerrarSesion() }
                .setNegativeButton("CANCELAR", null).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("¿Estás seguro de que deseas cerrar sesión?")
                .setPositiveButton("Sí") { _, _ -> ejecutarCerrarSesion() }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun ejecutarCerrarSesion() {
        getSharedPreferences("sesion", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().apply {
            remove("accessToken")
            remove("userPhone")
            remove("cashPointId")
            apply()
        }
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    object TokenManager {
        fun getToken(c: Context): String? = c.getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("accessToken", null)
    }

    override fun onResume() {
        super.onResume()
        findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_cortes
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0) {
            if (keyCode in intArrayOf(192, 191, 194, 190, 193, 189, 188)) {
                startActivity(Intent(this, CobroActivity::class.java).apply {
                    putExtra("macro_keycode", keyCode)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun actualizarEstadoConexion() {
        super.actualizarEstadoConexion()
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav?.menu?.findItem(R.id.nav_conexion)?.isEnabled = !ImpresoraController.getInstance().estaConectada()
    }
}
