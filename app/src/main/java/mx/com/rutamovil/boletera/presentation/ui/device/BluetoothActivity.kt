package mx.com.rutamovil.boletera.presentation.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import mx.com.rutamovil.boletera.R
import mx.com.rutamovil.boletera.common.ImpresoraController
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.local.ControlCortes
import mx.com.rutamovil.boletera.data.local.UsbPrinterManager
import mx.com.rutamovil.boletera.presentation.ui.auth.MainActivity
import mx.com.rutamovil.boletera.presentation.ui.core.CobroActivity
import mx.com.rutamovil.boletera.presentation.ui.core.CortesActivity
import mx.com.rutamovil.boletera.presentation.ui.core.BaseStatusBluetoothActivity
import java.io.IOException
import java.util.*

/**
 * Actividad dedicada a la configuración y gestión manual de la conexión con impresoras.
 * Permite listar dispositivos Bluetooth vinculados, realizar emparejamientos y establecer
 * el enlace inicial tanto por Bluetooth como por USB.
 */
class BluetoothActivity : BaseStatusBluetoothActivity() {

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        
        /**
         * Socket activo para la comunicación serie con la impresora Bluetooth.
         */
        @JvmStatic
        var bluetoothSocket: BluetoothSocket? = null
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Realiza una consulta directa a la impresora térmica para verificar si tiene papel.
         * Envía el comando ESC/POS correspondiente y espera respuesta del sensor.
         * @return True si hay papel o no se pudo determinar el error, false si el sensor indica falta de papel.
         */
        fun verificarPapel(): Boolean {
            val socket = bluetoothSocket ?: return false
            if (!socket.isConnected) return false
            return try {
                val os = socket.outputStream
                val inputStream = socket.inputStream
                // Comando estándar ESC/POS para consulta de estado de transmisión en tiempo real
                val command = byteArrayOf(0x10, 0x04, 0x04)
                os.write(command)
                os.flush()
                Thread.sleep(100) // Tiempo de espera para respuesta física del hardware
                if (inputStream.available() > 0) {
                    val status = inputStream.read()
                    // El bit 5 y 6 suelen indicar el estado del papel
                    (status and 0x60) == 0
                } else {
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                true
            }
        }
    }

    private lateinit var devicesArrayAdapter: ArrayAdapter<String>
    private val pairedDevicesList = ArrayList<BluetoothDevice>()

    /**
     * Receiver para procesar la respuesta del usuario ante la solicitud de permisos USB.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbPrinterManager.ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { conectarUsb(it) }
                    } else {
                        Utils.mostrarToast(context, "Permiso USB denegado")
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth)

        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        imgEstadoConexion = findViewById(R.id.imgEstadoConexion)

        ImpresoraController.getInstance().initUsbManager(this)

        val filter = IntentFilter(UsbPrinterManager.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.nav_conexion
        bottomNavigationView.isItemActiveIndicatorEnabled = false
        bottomNavigationView.itemActiveIndicatorColor = ColorStateList.valueOf(Color.TRANSPARENT)

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_conexion -> true
                R.id.nav_inicio -> {
                    startActivity(Intent(this, CobroActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    })
                    true
                }
                R.id.nav_cortes -> {
                    startActivity(Intent(this, CortesActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    })
                    true
                }
                R.id.nav_cerrarSesion -> {
                    cerrarSesion()
                    false
                }
                else -> false
            }
        }

        val devicesListView = findViewById<ListView>(R.id.devices_list_view)
        devicesArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        pairedDevicesList.clear()
        devicesListView.adapter = devicesArrayAdapter

        findViewById<Button>(R.id.btn_usb)?.setOnClickListener {
            try {
                ImpresoraController.getInstance().getUsbManager()?.buscarYConectarImpresora()
            } catch (e: Exception) {
                AlertDialog.Builder(this)
                    .setTitle("🔍 Error Detectado")
                    .setMessage(e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        ImpresoraController.getInstance().getUsbManager()?.let { usbManager ->
            if (usbManager.intentarConexionSilenciosa()) {
                ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.USB
                Utils.mostrarToast(this, "Conexión USB automática exitosa")
                actualizarEstadoConexion()
                startActivity(Intent(this, CobroActivity::class.java))
                finish()
                return
            } else if (usbManager.hayImpresoraPendienteSinPermiso()) {
                usbManager.buscarYConectarImpresora()
            }
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Utils.mostrarToast(this, "El dispositivo no soporta Bluetooth")
            finish()
            return
        }

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            if (pairedDevicesList.isNotEmpty() && position < pairedDevicesList.size) {
                conectarDispositivo(pairedDevicesList[position])
            }
        }

        checkPermissionsAndShowDevices()
        actualizarEstadoConexion()
    }

    private fun conectarUsb(device: UsbDevice) {
        val exito = ImpresoraController.getInstance().getUsbManager()?.conectar(device) ?: false
        if (exito) {
            ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.USB
            Utils.mostrarToast(this, "¡Impresora USB Conectada!")
            actualizarEstadoConexion()
            startActivity(Intent(this, CobroActivity::class.java))
            finish()
        } else {
            Utils.mostrarToast(this, "Error al conectar por USB")
        }
    }

    private fun checkPermissionsAndShowDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_ENABLE_BT)
                return
            }
        }
        if (bluetoothAdapter?.isEnabled == false) {
            Utils.mostrarToast(this, "Por favor encienda el Bluetooth")
            return
        }
        listarDispositivos()
        intentarConexionAutomaticaBT()
    }

    private fun intentarConexionAutomaticaBT() {
        val prefs = getSharedPreferences("PrinterPrefs", MODE_PRIVATE)
        val lastAddress = prefs.getString("LastPrinterAddress", null)

        if (lastAddress != null && bluetoothAdapter?.isEnabled == true) {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(lastAddress)
                device?.let {
                    Utils.mostrarToast(this, "Intentando conexión Bluetooth...")
                    conectarDispositivo(it)
                }
            } catch (e: IllegalArgumentException) {
                Log.e("BT_AUTO", "Dirección MAC guardada no es válida: $lastAddress", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun listarDispositivos() {
        devicesArrayAdapter.clear()
        pairedDevicesList.clear()
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()

        for (device in pairedDevices) {
            val deviceName = device.name?.uppercase(Locale.getDefault()) ?: "DESCONOCIDO"
            var esImpresora = false

            device.bluetoothClass?.let { bClass ->
                val majorClass = bClass.majorDeviceClass
                if (majorClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    majorClass == android.bluetooth.BluetoothClass.Device.Major.PHONE ||
                    majorClass == android.bluetooth.BluetoothClass.Device.Major.WEARABLE ||
                    majorClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER) {
                    return@let
                }
                if (majorClass == android.bluetooth.BluetoothClass.Device.Major.IMAGING ||
                    majorClass == android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED) {
                    esImpresora = true
                }
            }

            var soportaSPP = false
            device.uuids?.let { uuids ->
                for (uuid in uuids) {
                    if (uuid.toString().equals("00001101-0000-1000-8000-00805F9B34FB", ignoreCase = true)) {
                        soportaSPP = true
                        break
                    }
                }
            }
            if (esImpresora && device.uuids != null && !soportaSPP) esImpresora = false

            if (deviceName.contains("JBL") || deviceName.contains("BOSE") ||
                deviceName.contains("SPEAKER") || deviceName.contains("AUDIO") ||
                deviceName.contains("TWS") || deviceName.contains("AIRPODS")) {
                esImpresora = false
            }

            if (esImpresora) {
                devicesArrayAdapter.add("${device.name ?: "Desconocido"}\n${device.address}")
                pairedDevicesList.add(device)
            }
        }

        if (devicesArrayAdapter.isEmpty) {
            devicesArrayAdapter.add("No se encontraron impresoras térmicas vinculadas.")
        }
        devicesArrayAdapter.notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    private fun conectarDispositivo(device: BluetoothDevice) {
        Utils.mostrarToast(this, "Conectando a ${device.name}...")
        Thread {
            try {
                bluetoothSocket?.close()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()

                getSharedPreferences("PrinterPrefs", MODE_PRIVATE).edit().putString("LastPrinterAddress", device.address).apply()

                runOnUiThread {
                    ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.BLUETOOTH
                    Utils.mostrarToast(this, "¡Impresora Conectada!")
                    actualizarEstadoConexion()
                    startActivity(Intent(this, CobroActivity::class.java))
                    finish()
                }
            } catch (e: IOException) {
                try { bluetoothSocket?.close() } catch (ignored: IOException) {}
                runOnUiThread { Utils.mostrarToast(this, "Error al conectar") }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_ENABLE_BT) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Utils.mostrarToast(this, "Permisos necesarios")
                    finish()
                    return
                }
            }
            listarDispositivos()
        }
    }

    private fun cerrarSesion() {
        val dbHelper = ControlCortes(this)
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

    override fun onResume() {
        super.onResume()
        findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_conexion
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
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
        // Here we could add specific UI updates for BluetoothActivity if needed
    }
}
