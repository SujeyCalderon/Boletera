package mx.com.rutamovil.boletera.presentation.ui.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import mx.com.rutamovil.boletera.common.ImpresoraController
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.local.UsbPrinterManager
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import java.util.*

/**
 * Actividad base que implementa la lógica de monitoreo de conexión para impresoras (Bluetooth y USB).
 * Proporciona un mecanismo de reconexión automática en segundo plano y actualización visual del estado.
 */
abstract class BaseStatusBluetoothActivity : AppCompatActivity() {

    protected val handler = Handler(Looper.getMainLooper())
    private lateinit var checkConnectionRunnable: Runnable
    protected var tvEstadoConexion: TextView? = null
    protected var imgEstadoConexion: ImageView? = null

    private var conectandoBTBackground = false
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    protected var bluetoothAdapter: BluetoothAdapter? = null

    /**
     * Receiver para detectar cambios en el estado del adaptador Bluetooth o desconexiones físicas de dispositivos ACL.
     */
    private val bluetoothDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action || 
                BluetoothDevice.ACTION_ACL_CONNECTED == action ||
                BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                
                // Limpieza del socket si se detecta una pérdida de enlace confirmada por el sistema
                if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action || BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                    try {
                        BluetoothActivity.bluetoothSocket?.close()
                    } catch (ignored: Exception) {}
                    BluetoothActivity.bluetoothSocket = null

                    if (ImpresoraController.getInstance().conexionActual == ImpresoraController.TipoConexion.BLUETOOTH) {
                        ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.NINGUNA
                    }
                }
                
                runOnUiThread { actualizarEstadoConexion() }
            }
        }
    }

    /**
     * Receiver encargado de gestionar eventos de hardware USB (conexión, desconexión y permisos).
     */
    private val usbGlobalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    ImpresoraController.getInstance().getUsbManager()?.desconectar()
                    if (ImpresoraController.getInstance().conexionActual == ImpresoraController.TipoConexion.USB) {
                        ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.NINGUNA
                    }
                    runOnUiThread { actualizarEstadoConexion() }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Intento de conexión con delay para permitir que el kernel registre el dispositivo correctamente
                    handler.postDelayed({
                        val usbExito = ImpresoraController.getInstance().getUsbManager()?.intentarConexionSilenciosa() ?: false
                        if (usbExito) {
                            ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.USB
                            runOnUiThread { actualizarEstadoConexion() }
                        }
                    }, 300)
                }
                UsbPrinterManager.ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                if (ImpresoraController.getInstance().getUsbManager()?.conectar(it) == true) {
                                    ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.USB
                                    runOnUiThread { actualizarEstadoConexion() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        try {
            ImpresoraController.getInstance().initUsbManager(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Tarea periódica de verificación de salud de la conexión
        checkConnectionRunnable = object : Runnable {
            override fun run() {
                try {
                    val controller = ImpresoraController.getInstance()
                    
                    if (!controller.estaConectada()) {
                        if (conectandoBTBackground) return

                        // Estrategia: USB tiene prioridad sobre Bluetooth por estabilidad
                        val usbExito = controller.getUsbManager()?.intentarConexionSilenciosa() ?: false
                        if (usbExito) {
                            controller.conexionActual = ImpresoraController.TipoConexion.USB
                        } else {
                            // Intento de recuperación automática de la última impresora BT vinculada
                            intentarConexionAutomaticaGlobalBT()
                        }
                    } else {
                        // Verificación proactiva de socket (Heartbeat)
                        if (controller.conexionActual == ImpresoraController.TipoConexion.BLUETOOTH) {
                            if (!controller.verificarPapel()) { 
                                BluetoothActivity.bluetoothSocket?.close()
                                BluetoothActivity.bluetoothSocket = null
                                controller.conexionActual = ImpresoraController.TipoConexion.NINGUNA
                            }
                        }
                    }
                    
                    actualizarEstadoConexion()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Muestreo cada 500ms para asegurar respuesta rápida en UI
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    /**
     * Recupera la dirección MAC de la última impresora configurada e intenta reconectar sin intervención del usuario.
     */
    @SuppressLint("MissingPermission")
    private fun intentarConexionAutomaticaGlobalBT() {
        val prefs = getSharedPreferences("PrinterPrefs", MODE_PRIVATE)
        val lastAddress = prefs.getString("LastPrinterAddress", null) ?: return

        if (bluetoothAdapter?.isEnabled != true) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val device = try {
            bluetoothAdapter?.getRemoteDevice(lastAddress)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return

        conectandoBTBackground = true
        Thread {
            try {
                BluetoothActivity.bluetoothSocket?.close()
                // Creación de socket inseguro para máxima compatibilidad con impresoras térmicas chinas
                val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                socket.connect()
                BluetoothActivity.bluetoothSocket = socket
                ImpresoraController.getInstance().conexionActual = ImpresoraController.TipoConexion.BLUETOOTH

                runOnUiThread {
                    Utils.mostrarToast(this, "Bluetooth auto-conectado")
                    actualizarEstadoConexion()
                }
            } catch (e: Exception) {
                try { BluetoothActivity.bluetoothSocket?.close() } catch (ignored: Exception) {}
            } finally {
                conectandoBTBackground = false
            }
        }.start()
    }

    /**
     * Refresca los componentes visuales (texto e icono) para reflejar la conectividad actual.
     */
    open fun actualizarEstadoConexion() {
        val tv = tvEstadoConexion ?: return
        val img = imgEstadoConexion ?: return

        tv.setOnLongClickListener {
            Utils.mostrarToast(this, "Logs de error automatizados en background.")
            true
        }

        val tipo = ImpresoraController.getInstance().conexionActual
        if (tipo == ImpresoraController.TipoConexion.BLUETOOTH && ImpresoraController.getInstance().estaConectada()) {
            tv.text = "Conectado por BT"
            tv.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            img.setColorFilter(resources.getColor(android.R.color.holo_green_dark, null), android.graphics.PorterDuff.Mode.SRC_IN)
        } else if (tipo == ImpresoraController.TipoConexion.USB && ImpresoraController.getInstance().estaConectada()) {
            tv.text = "Conectado por USB"
            tv.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            img.setColorFilter(resources.getColor(android.R.color.holo_green_dark, null), android.graphics.PorterDuff.Mode.SRC_IN)
        } else {
            tv.text = "Desconectado"
            tv.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            img.setColorFilter(resources.getColor(android.R.color.holo_red_dark, null), android.graphics.PorterDuff.Mode.SRC_IN)
        }
    }


    override fun onResume() {
        super.onResume()
        actualizarEstadoConexion()
        handler.post(checkConnectionRunnable)

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothDisconnectReceiver, filter)

        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbPrinterManager.ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(usbGlobalReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbGlobalReceiver, usbFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(checkConnectionRunnable)
        try { unregisterReceiver(bluetoothDisconnectReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(usbGlobalReceiver) } catch (e: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            Utils.activarPantallaCompleta(window)
        }
    }
}
