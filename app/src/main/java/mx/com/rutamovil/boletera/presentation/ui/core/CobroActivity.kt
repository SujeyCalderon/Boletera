package mx.com.rutamovil.boletera.presentation.ui.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import mx.com.rutamovil.boletera.R
import mx.com.rutamovil.boletera.common.ImpresoraController
import mx.com.rutamovil.boletera.data.manager.LogUploader
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.local.ControlCortes
import mx.com.rutamovil.boletera.data.local.DatabaseHelper
import mx.com.rutamovil.boletera.data.remote.ApiClient
import mx.com.rutamovil.boletera.data.remote.dto.TarifasResponse
import mx.com.rutamovil.boletera.data.remote.dto.TransactionSyncRequest
import mx.com.rutamovil.boletera.data.remote.dto.TransactionSyncResponse
import mx.com.rutamovil.boletera.domain.model.TarifaControl
import mx.com.rutamovil.boletera.presentation.ui.auth.MainActivity
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import mx.com.rutamovil.boletera.presentation.ui.core.CortesActivity
import mx.com.rutamovil.boletera.presentation.ui.core.BaseStatusBluetoothActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

/**
 * Actividad principal de operación encargada del cobro de pasajes y emisión de boletos.
 * Gestiona la interfaz de tarifas, geolocalización de ventas, comunicación con la impresora
 * y sincronización de transacciones individuales con la nube.
 */
class CobroActivity : BaseStatusBluetoothActivity() {

    private lateinit var tvNumero: TextView
    private lateinit var layoutTarifas: LinearLayout
    private var contador = 1
    private var numeroTransaccion = 1
    private lateinit var iconosTarifas: MutableMap<String, Int>
    private lateinit var tarifasPorTipo: MutableMap<String, TarifaControl>
    private lateinit var coloresTarifas: List<Int>
    private var passwordUsuario: String? = null
    private var identificador: String? = null
    private var userPhone: String? = null
    private var unidadRutaStr: String = "Sin Unidad"
    private lateinit var dbHelper: ControlCortes
    private lateinit var prefs: SharedPreferences

    private lateinit var locationManager: LocationManager
    private var latitudActual = "0.0"
    private var longitudActual = "0.0"
    private var ultimoTiempoClick = 0L
    private val TIEMPO_ANTIREBOTE = 1000L
    private val LIMITE_MAXIMO_PASAJEROS = 15

    private var isDialogoPreciosAbierto = false
    private val botonesDialogoAbierto = mutableListOf<Button>()
    private var indiceDialogoSeleccionado = 0
    private var dialogoPreciosActual: AlertDialog? = null

    /**
     * Escucha cambios en la ubicación GPS para estampar coordenadas en cada venta.
     */
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latitudActual = location.latitude.toString()
            longitudActual = location.longitude.toString()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (sharedPrefs.getString("accessToken", null) == null) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        setContentView(R.layout.activity_cobro)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            startLockTask()
        } catch (e: Exception) {
            Log.e("SEGURIDAD", "No se pudo fijar la pantalla: ${e.message}")
        }

        procesarIntentUsb(intent)

        coloresTarifas = listOf(
            ContextCompat.getColor(this, R.color.colorTarifa1),
            ContextCompat.getColor(this, R.color.colorTarifa2),
            ContextCompat.getColor(this, R.color.colorTarifa3),
            ContextCompat.getColor(this, R.color.colorTarifa4),
            ContextCompat.getColor(this, R.color.colorTarifa5),
            ContextCompat.getColor(this, R.color.colorTarifa6),
            ContextCompat.getColor(this, R.color.colorTarifa7)
        )

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.nav_inicio
        bottomNavigationView.isItemActiveIndicatorEnabled = false
        bottomNavigationView.itemActiveIndicatorColor = ColorStateList.valueOf(Color.TRANSPARENT)

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_inicio -> true
                R.id.nav_cortes -> {
                    startActivity(Intent(this, CortesActivity::class.java).apply {
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

        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        imgEstadoConexion = findViewById(R.id.imgEstadoConexion)
        layoutTarifas = findViewById(R.id.layoutTarifas)

        passwordUsuario = intent.getStringExtra("passwordUsuario") ?: sharedPrefs.getString("passwordUsuario", null)
        prefs = getSharedPreferences("MisPreferencias", MODE_PRIVATE)
        numeroTransaccion = prefs.getInt("numeroTransaccion", 1)
        actualizarEstadoConexion()

        dbHelper = ControlCortes(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        solicitarUbicacionGPS()

        iconosTarifas = mutableMapOf(
            "REGULAR" to R.drawable.persona,
            "ESTUDIANTE" to R.drawable.birrete,
            "3ERA. EDAD" to R.drawable.tercera_edad,
            "PERSONA CON DISCAPACIDAD" to R.drawable.discapacitado
        )

        tarifasPorTipo = mutableMapOf()

        val userIdLocal = sharedPrefs.getInt("userIdLocal", -1)
        if (userIdLocal != -1) {
            val dbUsuarios = DatabaseHelper(this)
            val cursor = dbUsuarios.obtenerUsuarioPorId(userIdLocal)
            if (cursor != null && cursor.moveToFirst()) {
                identificador = cursor.getString(cursor.getColumnIndexOrThrow("identificador"))
                userPhone = cursor.getString(cursor.getColumnIndexOrThrow("phone"))
                unidadRutaStr = cursor.getString(cursor.getColumnIndexOrThrow("unidad_ruta")) ?: "Sin Unidad"
            }
            cursor?.close()
        }

        val tarifasCacheadas = dbHelper.obtenerTarifasCache()
        if (tarifasCacheadas.isNotEmpty()) {
            configurarBotonesTarifas(tarifasCacheadas)
        } else {
            Utils.mostrarToast(this, "⚠️ CRÍTICO: No hay tarifas. Inicie sesión con internet.")
        }

        val btnMas = findViewById<Button>(R.id.btnMas)
        val btnMenos = findViewById<Button>(R.id.btnMenos)
        tvNumero = findViewById(R.id.tvNumero)
        tvNumero.text = contador.toString()

        btnMas.setOnClickListener {
            if (contador < LIMITE_MAXIMO_PASAJEROS) {
                contador++
                tvNumero.text = contador.toString()
                Utils.reproducirSonidoClick(this)
            } else {
                Utils.mostrarToast(this, "Límite máximo ($LIMITE_MAXIMO_PASAJEROS) alcanzado")
            }
        }

        btnMenos.setOnClickListener {
            if (contador > 1) {
                contador--
                tvNumero.text = contador.toString()
            }
            Utils.reproducirSonidoClick(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (sharedPrefs.getString("accessToken", null) == null) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        procesarIntentUsb(intent)

        val macroKeyCode = intent.getIntExtra("macro_keycode", -1)
        if (macroKeyCode != -1) {
            Handler(mainLooper).postDelayed({ procesarMacroBoton(macroKeyCode) }, 400)
        }
    }

    private fun procesarIntentUsb(intent: Intent?) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let {
                if (ImpresoraController.getInstance().getUsbManager()?.conectar(it) == true) {
                    ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.USB
                    Utils.mostrarToast(this, "Impresora reconectada automáticamente")
                    actualizarEstadoConexion()
                }
            }
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        Utils.mostrarToast(this, "Acción bloqueada. Utilice 'Cerrar sesión' para salir.")
    }

    private fun solicitarUbicacionGPS() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 5f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 5f, locationListener)
            var lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnown == null) lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let {
                latitudActual = it.latitude.toString()
                longitudActual = it.longitude.toString()
            }
        } catch (e: Exception) {
            Log.e("GPS_COBRO", "Error al iniciar GPS: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            solicitarUbicacionGPS()
        } else if (requestCode == 100) {
            Utils.mostrarToast(this, "Sin permisos de GPS. Ventas sin ubicación.")
        }
    }

    private fun configurarBotonesTarifas(tarifas: List<TarifasResponse.Fare>) {
        tarifasPorTipo.clear()
        layoutTarifas.removeAllViews()
        for (tarifa in tarifas) {
            if (tarifa.payment_type == null || tarifa.payment_type.equals("P.efectivo", ignoreCase = true)) {
                val passengerTypeUnificado = normalizarCategoria(tarifa.passenger_type)
                val control = tarifasPorTipo.getOrPut(passengerTypeUnificado) { TarifaControl() }
                control.agregarNivel(tarifa.id, tarifa.price?.toDoubleOrNull() ?: 0.0, tarifa.fare ?: "")
            }
        }

        val ordenCategorias = listOf("REGULAR", "ESTUDIANTE", "3ERA. EDAD", "PERSONA CON DISCAPACIDAD")
        var colorIndex = 0
        for (passengerType in ordenCategorias) {
            val control = tarifasPorTipo[passengerType] ?: continue
            agregarBotonTarifa(passengerType, control, coloresTarifas[colorIndex % coloresTarifas.size])
            colorIndex++
        }
    }

    private fun normalizarCategoria(tipoServidor: String?): String {
        if (tipoServidor == null) return "OTROS"
        val txt = tipoServidor.trim().uppercase(Locale.getDefault())
        if (txt.contains("REGULAR") || txt.contains("GENERAL") || txt.contains("ADULTO")) return "REGULAR"
        if (txt.contains("ESTUD") || txt.contains("UNIV") || txt.contains("ESCOLAR")) return "ESTUDIANTE"
        if (txt.contains("3") || txt.contains("TERCERA") || txt.contains("MAYOR") || txt.contains("INAPAM")) return "3ERA. EDAD"
        if (txt.contains("DISCA")) return "PERSONA CON DISCAPACIDAD"
        return "REGULAR"
    }

    private fun agregarBotonTarifa(passengerType: String, control: TarifaControl, colorFondo: Int) {
        val vistaTarifa = LayoutInflater.from(this).inflate(R.layout.card_tarifa, null)
        val txtNombreTarifa = vistaTarifa.findViewById<TextView>(R.id.txtNombreTarifa)
        val iconTarifa = vistaTarifa.findViewById<ImageView>(R.id.iconTarifa)
        val cardTarifa = vistaTarifa.findViewById<LinearLayout>(R.id.cardTarifa)

        cardTarifa.setBackgroundColor(colorFondo)
        txtNombreTarifa.text = passengerType
        val iconoRes = iconosTarifas[passengerType] ?: R.drawable.cash
        iconTarifa.setImageResource(iconoRes)

        cardTarifa.setOnClickListener {
            val niveles = control.getListaNiveles()
            if (niveles.size == 1) {
                if (System.currentTimeMillis() - ultimoTiempoClick < TIEMPO_ANTIREBOTE) return@setOnClickListener
                ultimoTiempoClick = System.currentTimeMillis()
                Utils.reproducirSonidoClick(this)
                acumularVenta(passengerType, niveles[0].id, niveles[0].precio)
                generateSingleTicketText(passengerType, contador, niveles[0].precio)
                sincronizarTransaccionesPendientes()
                contador = 1
                tvNumero.text = "1"
            } else {
                Utils.reproducirSonidoClick(this)
                mostrarSelectorPrecios(passengerType, control)
            }
        }

        val separacionPx = (16 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f).apply {
            setMargins(0, 0, 0, separacionPx)
        }
        vistaTarifa.layoutParams = params
        layoutTarifas.addView(vistaTarifa)
    }

    private fun actualizarFocoDialogo() {
        if (botonesDialogoAbierto.isEmpty()) return
        for (i in botonesDialogoAbierto.indices) {
            val b = botonesDialogoAbierto[i]
            if (i == indiceDialogoSeleccionado) {
                if (b.text.toString() == "CANCELAR") {
                    b.setBackgroundColor(Color.parseColor("#FF9800"))
                    b.setTextColor(Color.WHITE)
                } else {
                    b.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
                }
            } else {
                if (b.text.toString() == "CANCELAR") {
                    b.backgroundTintList = null
                    b.setBackgroundColor(Color.TRANSPARENT)
                    b.setTextColor(Color.parseColor("#D32F2F"))
                } else {
                    b.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565C0"))
                }
            }
        }
    }

    private fun mostrarSelectorPrecios(categoria: String, control: TarifaControl) {
        isDialogoPreciosAbierto = true
        botonesDialogoAbierto.clear()
        indiceDialogoSeleccionado = 0

        val builder = AlertDialog.Builder(this)
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 80)
        }
        val dialog = builder.create()
        dialogoPreciosActual = dialog

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (keyCode) {
                    192 -> {
                        if (indiceDialogoSeleccionado > 0) {
                            indiceDialogoSeleccionado--
                            actualizarFocoDialogo()
                        }
                        true
                    }
                    194 -> {
                        if (indiceDialogoSeleccionado < botonesDialogoAbierto.size - 1) {
                            indiceDialogoSeleccionado++
                            actualizarFocoDialogo()
                        }
                        true
                    }
                    193 -> {
                        if (botonesDialogoAbierto.isNotEmpty()) {
                            botonesDialogoAbierto[indiceDialogoSeleccionado].performClick()
                        }
                        true
                    }
                    else -> false
                }
            } else false
        }

        dialog.setOnDismissListener {
            isDialogoPreciosAbierto = false
            botonesDialogoAbierto.clear()
            dialogoPreciosActual = null
        }

        val headerLayout = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 60)
            }
        }

        val titulo = TextView(this).apply {
            text = categoria
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
        }
        headerLayout.addView(titulo)

        val btnCerrar = TextView(this).apply {
            text = "X"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(20, 0, 20, 0)
            layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE)
                addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE)
            }
            setOnClickListener { Utils.reproducirSonidoClick(this@CobroActivity); dialog.dismiss() }
        }
        headerLayout.addView(btnCerrar)
        layout.addView(headerLayout)

        for (nivel in control.getListaNiveles()) {
            val btn = Button(this).apply {
                text = "${nivel.nombreBackend} - \$${String.format(Locale.US, "%.2f", nivel.precio)}"
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565C0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 40)
                }
                setPadding(30, 70, 30, 70)
                setOnClickListener {
                    if (System.currentTimeMillis() - ultimoTiempoClick < TIEMPO_ANTIREBOTE) return@setOnClickListener
                    ultimoTiempoClick = System.currentTimeMillis()
                    Utils.reproducirSonidoClick(this@CobroActivity)
                    acumularVenta(categoria, nivel.id, nivel.precio)
                    generateSingleTicketText(categoria, contador, nivel.precio)
                    sincronizarTransaccionesPendientes()
                    contador = 1
                    tvNumero.text = "1"
                    dialog.dismiss()
                }
            }
            botonesDialogoAbierto.add(btn)
            layout.addView(btn)
        }

        val btnCancelar = Button(this).apply {
            text = "CANCELAR"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D32F2F"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 60, 0, 0)
            }
            setPadding(20, 40, 20, 40)
            setOnClickListener { Utils.reproducirSonidoClick(this@CobroActivity); dialog.dismiss() }
        }
        botonesDialogoAbierto.add(btnCancelar)
        layout.addView(btnCancelar)
        scrollView.addView(layout)
        dialog.setView(scrollView)
        dialog.show()

        actualizarFocoDialogo()
    }

    private fun sincronizarTransaccionesPendientes() {
        val tokenActual = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("accessToken", null) ?: return

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
                if (response.isSuccessful) dbHelper.marcarTransaccionesComoEnviadasWeb()
            }
            override fun onFailure(call: Call<TransactionSyncResponse>, t: Throwable) {}
        })
    }

    private fun cerrarSesion() {
        var hayPendientes = dbHelper.getVentas().isNotEmpty()
        if (!hayPendientes) hayPendientes = dbHelper.cortesParcialesNoEnviados().isNotEmpty()

        if (!hayPendientes) {
            val c = dbHelper.getResumenCortesParciales()
            if (c != null && c.count > 0) hayPendientes = true
            c?.close()
        }

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

    fun acumularVenta(tipo: String, routeFareId: Int, precio: Double) {
        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        repeat(contador) {
            dbHelper.insertarBoleto(tipo, routeFareId, precio, fecha, latitudActual, longitudActual)
        }
    }

    private fun generateSingleTicketText(tipo: String, cantidad: Int, precio: Double) {
        val fecha = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val maxChars = 32

        repeat(cantidad) {
            val titleLine = "TICKET DE COBRO\n$fecha $hora"
            val ticketDetails = StringBuilder("\n")

            var unidadCorta = unidadRutaStr
            var rutaNombre = "Sin Ruta"

            if (unidadRutaStr.contains("-")) {
                val partes = unidadRutaStr.split("-")
                unidadCorta = partes[0].trim()
                if (partes.size > 1) rutaNombre = partes[1].trim()
            }

            ticketDetails.append(centrarTexto("RUTA: $rutaNombre", maxChars)).append("\n")
            val unidadFolio = "UNIDAD: $unidadCorta   TICKET: $numeroTransaccion"
            ticketDetails.append(centrarTexto(unidadFolio, maxChars)).append("\n\n")

            var tipoImpreso = tipo
            if (tipoImpreso.equals("PERSONA CON DISCAPACIDAD", ignoreCase = true)) tipoImpreso = "PCD"

            ticketDetails.append(Utils.textoIzquierdaDerecha("Tipo:", tipoImpreso, maxChars)).append("\n")
            ticketDetails.append(Utils.textoIzquierdaDerecha("Costo:", "\$${precio.toInt()}", maxChars)).append("\n")

            Utils.printTicketConLogo(this, titleLine, ticketDetails.toString()) { actualizarEstadoConexion() }

            numeroTransaccion++
            prefs.edit().putInt("numeroTransaccion", numeroTransaccion).apply()
        }
    }

    private fun centrarTexto(texto: String, maxChars: Int): String {
        if (texto.length >= maxChars) return texto
        val padding = (maxChars - texto.length) / 2
        val sb = StringBuilder()
        repeat(padding) { sb.append(" ") }
        sb.append(texto)
        return sb.toString()
    }

    override fun onPause() {
        super.onPause()
        locationManager.removeUpdates(locationListener)
    }

    override fun onResume() {
        super.onResume()
        solicitarUbicacionGPS()
        if (dbHelper.existenPendientesDeDiasAnteriores()) {
            mostrarAlertaBloqueoPorCorteAtrasado()
        }
        findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_inicio
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
    }

    private fun mostrarAlertaBloqueoPorCorteAtrasado() {
        AlertDialog.Builder(this)
            .setTitle("CORTE PENDIENTE")
            .setMessage("Tienes cobros o cortes parciales pendientes de días anteriores. Debes realizar el Corte Total antes de iniciar una nueva jornada.")
            .setCancelable(false)
            .setPositiveButton("IR A CORTES") { _, _ ->
                startActivity(Intent(this, CortesActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                })
            }
            .show()
    }

    private fun cobrarDirectoArcade(categoria: String, esMinimo: Boolean) {
        val control = tarifasPorTipo[categoria] ?: return
        val niveles = control.getListaNiveles()

        if (niveles.isNotEmpty()) {
            if (System.currentTimeMillis() - ultimoTiempoClick < TIEMPO_ANTIREBOTE) return
            ultimoTiempoClick = System.currentTimeMillis()

            Utils.reproducirSonidoClick(this)
            val nivelASeleccionar = if (esMinimo) niveles.last() else niveles.first()

            acumularVenta(categoria, nivelASeleccionar.id, nivelASeleccionar.precio)
            generateSingleTicketText(categoria, contador, nivelASeleccionar.precio)
            sincronizarTransaccionesPendientes()

            contador = 1
            tvNumero.text = "1"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0) {
            if (procesarMacroBoton(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun procesarMacroBoton(keyCode: Int): Boolean {
        if (!isDialogoPreciosAbierto) {
            when (keyCode) {
                192 -> { cobrarDirectoArcade("REGULAR", true); return true }
                191 -> { cobrarDirectoArcade("REGULAR", false); return true }
                194 -> { cobrarDirectoArcade("ESTUDIANTE", true); return true }
                190 -> { cobrarDirectoArcade("ESTUDIANTE", false); return true }
                193 -> { cobrarDirectoArcade("3ERA. EDAD", true); return true }
                189 -> { cobrarDirectoArcade("3ERA. EDAD", false); return true }
                188 -> { cobrarDirectoArcade("PERSONA CON DISCAPACIDAD", false); return true }
            }
        }
        return false
    }

    override fun actualizarEstadoConexion() {
        super.actualizarEstadoConexion()
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav?.menu?.findItem(R.id.nav_conexion)?.isEnabled = !ImpresoraController.getInstance().estaConectada()
    }
}
