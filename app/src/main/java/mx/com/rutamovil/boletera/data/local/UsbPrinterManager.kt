package mx.com.rutamovil.boletera.data.local

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import mx.com.rutamovil.boletera.common.Utils

/**
 * Gestor para la conexión y comunicación con impresoras mediante el puerto USB.
 * Maneja la detección de dispositivos, solicitud de permisos y transferencia de datos ESC/POS.
 */
class UsbPrinterManager(private var context: Context) {

    companion object {
        /**
         * Acción para el Intent de solicitud de permiso USB.
         */
        const val ACTION_USB_PERMISSION = "mx.com.rutamovil.boletera.USB_PERMISSION"
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbEndpointOut: UsbEndpoint? = null

    /**
     * Busca dispositivos USB conectados y trata de establecer una conexión con el primero que sea identificado como impresora.
     * Si no tiene permisos, los solicita al usuario.
     */
    fun buscarYConectarImpresora() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            // Se verifica la clase del dispositivo o se busca una interfaz de tipo impresora
            if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER || buscarInterfazImpresora(device) != null) {
                usbDevice = device
                if (usbManager.hasPermission(usbDevice)) {
                    conectar(device)
                } else {
                    solicitarPermisoUsb(device)
                }
                return
            }
        }
        Utils.mostrarToast(context, "No se detectó impresora USB conectada")
    }

    /**
     * Intenta conectar con una impresora USB sin mostrar diálogos si ya se cuentan con los permisos necesarios.
     * @return True si se logró conectar exitosamente, false de lo contrario.
     */
    fun intentarConexionSilenciosa(): Boolean {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER || buscarInterfazImpresora(device) != null) {
                if (usbManager.hasPermission(device)) {
                    return conectar(device)
                }
            }
        }
        return false
    }

    /**
     * Verifica si hay alguna impresora conectada físicamente para la cual aún no se tiene permiso de acceso.
     */
    fun hayImpresoraPendienteSinPermiso(): Boolean {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER || buscarInterfazImpresora(device) != null) {
                if (!usbManager.hasPermission(device)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Recorre las interfaces de un dispositivo USB en busca de una que sea de tipo IMPRESORA.
     */
    private fun buscarInterfazImpresora(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return usbInterface
            }
        }
        return null
    }

    /**
     * Inicia el flujo del sistema para solicitar permisos de acceso al dispositivo USB.
     * Detiene temporalmente el modo quiosco (LockTask) si está activo para permitir ver el diálogo del sistema.
     */
    private fun solicitarPermisoUsb(device: UsbDevice) {
        if (context is Activity) {
            try {
                (context as Activity).stopLockTask()
            } catch (e: Exception) {
                Log.e("USB", "Error al quitar fijación: ${e.message}")
            }
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Abre el dispositivo USB y reclama la interfaz de impresión para habilitar el envío de datos.
     * @param device El dispositivo a conectar.
     * @return True si se estableció la conexión y se encontró un endpoint de salida.
     */
    fun conectar(device: UsbDevice): Boolean {
        this.usbDevice = device
        val usbInterface = buscarInterfazImpresora(device) ?: return false

        usbConnection = usbManager.openDevice(device)
        if (usbConnection != null) {
            usbConnection!!.claimInterface(usbInterface, true)
            // Se busca el endpoint de salida para enviar los comandos de impresión
            for (i in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(i)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    usbEndpointOut = endpoint
                    return true
                }
            }
        }
        return false
    }

    /**
     * Envía un arreglo de bytes al endpoint de la impresora mediante transferencias bulk.
     * Los datos se envían en fragmentos (chunks) para mejorar la fiabilidad.
     * @param bytes Comandos y datos ESC/POS a imprimir.
     * @return True si todos los bytes se transfirieron sin error.
     */
    fun imprimir(bytes: ByteArray): Boolean {
        if (usbConnection != null && usbEndpointOut != null) {
            var offset = 0
            val chunkSize = 2048 // Tamaño del fragmento para la transferencia

            while (offset < bytes.size) {
                val length = Math.min(chunkSize, bytes.size - offset)
                val chunk = ByteArray(length)
                System.arraycopy(bytes, offset, chunk, 0, length)
                val result = usbConnection!!.bulkTransfer(usbEndpointOut, chunk, length, 5000)
                if (result < 0) {
                    Log.e("USB_PRINTER", "Error en transferencia USB en el offset $offset")
                    return false
                }
                offset += length
            }
            return true
        }
        return false
    }

    /**
     * @return True si existe una conexión USB activa y configurada.
     */
    fun estaConectada(): Boolean = usbConnection != null && usbEndpointOut != null

    /**
     * Actualiza el contexto utilizado por el manager.
     */
    fun setContext(context: Context) {
        this.context = context
    }

    /**
     * Libera los recursos de la conexión USB y cierra la interfaz.
     */
    fun desconectar() {
        usbConnection?.apply {
            close()
        }
        usbConnection = null
        usbEndpointOut = null
    }
}

