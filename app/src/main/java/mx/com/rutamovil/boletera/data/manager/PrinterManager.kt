package mx.com.rutamovil.boletera.data.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import mx.com.rutamovil.boletera.data.local.UsbPrinterManager
import java.io.OutputStream

/**
 * Singleton encargado de coordinar la impresión tanto por USB como por Bluetooth.
 * Centraliza la lógica de selección de hardware y manejo de paquetes de datos (ESC/POS).
 * Actúa como un puente simplificado para las actividades hacia los controladores específicos.
 */
class PrinterManager private constructor() {

    private var usbManager: UsbPrinterManager? = null

    /**
     * Definición de los medios de conexión soportados.
     */
    enum class ConnectionType { NONE, BLUETOOTH, USB }

    /**
     * Indica el tipo de conexión establecido actualmente.
     */
    var currentConnection = ConnectionType.NONE

    companion object {
        @Volatile
        private var instance: PrinterManager? = null

        /**
         * Obtiene la instancia compartida de [PrinterManager].
         */
        fun getInstance(): PrinterManager {
            return instance ?: synchronized(this) {
                instance ?: PrinterManager().also { instance = it }
            }
        }
    }

    /**
     * Prepara el manager para operaciones USB si aún no ha sido inicializado.
     * @param context Contexto necesario para el servicio USB.
     */
    fun initUsb(context: Context) {
        if (usbManager == null) {
            usbManager = UsbPrinterManager(context)
        } else {
            usbManager?.setContext(context)
        }
    }

    /**
     * @return El gestor de USB activo.
     */
    fun getUsbManager(): UsbPrinterManager? = usbManager

    /**
     * Verifica si hay una comunicación exitosa con alguna impresora según el modo actual.
     * @return True si el enlace físico está activo.
     */
    fun isConnected(): Boolean {
        return when (currentConnection) {
            ConnectionType.BLUETOOTH -> BluetoothActivity.bluetoothSocket?.isConnected == true
            ConnectionType.USB -> usbManager?.estaConectada() == true
            ConnectionType.NONE -> false
        }
    }

    /**
     * Consulta el estado del papel de la impresora en uso.
     * @return True si hay papel disponible o no se puede reportar error.
     */
    fun checkPaper(): Boolean {
        return when (currentConnection) {
            ConnectionType.BLUETOOTH -> BluetoothActivity.verificarPapel()
            ConnectionType.USB -> true
            ConnectionType.NONE -> false
        }
    }

    /**
     * Envía un bloque de bytes por Bluetooth con protección contra bloqueos infinitos.
     * @param data Datos binarios a transferir.
     */
    private fun writeBluetoothWithTimeout(data: ByteArray): Boolean {
        val socket = BluetoothActivity.bluetoothSocket ?: return false
        if (!socket.isConnected) return false

        val success = booleanArrayOf(false)

        val writeThread = Thread {
            try {
                val os: OutputStream = socket.outputStream
                os.write(data)
                os.flush()
                success[0] = true
            } catch (e: Exception) {
                success[0] = false
            }
        }

        writeThread.start()

        try {
            // Se da un margen de 800ms antes de considerar que la impresora no responde
            writeThread.join(800)
            if (writeThread.isAlive) {
                try { socket.close() } catch (ignored: Exception) {}
                BluetoothActivity.bluetoothSocket = null
                currentConnection = ConnectionType.NONE
                return false
            }
        } catch (e: InterruptedException) {
            return false
        }

        if (!success[0]) {
            try { socket.close() } catch (ignored: Exception) {}
            BluetoothActivity.bluetoothSocket = null
            currentConnection = ConnectionType.NONE
        }

        return success[0]
    }

    /**
     * Imprime una cadena de texto simple procesando los comandos básicos ESC/POS.
     * @param content Texto legible.
     * @return Éxito de la operación.
     */
    fun print(content: String): Boolean {
        if (!isConnected()) return false

        try {
            val initPrinter = byteArrayOf(0x1B, 0x40)
            val textBytes = content.toByteArray(charset("UTF-8"))
            val lineFeeds = "\n\n".toByteArray()
            val cutPaper = byteArrayOf(0x1D, 0x56, 0x41, 0x10)

            return when (currentConnection) {
                ConnectionType.BLUETOOTH -> {
                    val fullPackage = initPrinter + textBytes + lineFeeds + cutPaper
                    writeBluetoothWithTimeout(fullPackage)
                }
                ConnectionType.USB -> {
                    var ok = usbManager?.imprimir(initPrinter) ?: false
                    ok = ok && (usbManager?.imprimir(textBytes) ?: false)
                    ok = ok && (usbManager?.imprimir(lineFeeds) ?: false)
                    ok = ok && (usbManager?.imprimir(cutPaper) ?: false)
                    ok
                }
                ConnectionType.NONE -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Realiza la impresión de un ticket compuesto por un logo gráfico seguido de texto.
     * @param content Texto del cuerpo del ticket.
     * @param logo Imagen opcional para la cabecera.
     */
    fun printWithLogo(content: String, logo: Bitmap?): Boolean {
        if (!isConnected()) return false

        try {
            val initPrinter = byteArrayOf(0x1B, 0x40)
            val alignCenter = byteArrayOf(0x1B, 0x61, 1)
            val alignLeft = byteArrayOf(0x1B, 0x61, 0)
            val textBytes = content.toByteArray(charset("UTF-8"))
            val lineFeeds = "\n\n".toByteArray()
            val cutPaper = byteArrayOf(0x1D, 0x56, 0x41, 0x10)

            val logoBytes = logo?.let { getLogoBytes(it) }

            return when (currentConnection) {
                ConnectionType.BLUETOOTH -> {
                    if (!writeBluetoothWithTimeout(initPrinter)) return false
                    logoBytes?.let {
                        writeBluetoothWithTimeout(alignCenter)
                        writeBluetoothWithTimeout(it)
                        writeBluetoothWithTimeout(alignLeft)
                    }
                    writeBluetoothWithTimeout(textBytes) &&
                            writeBluetoothWithTimeout(lineFeeds) &&
                            writeBluetoothWithTimeout(cutPaper)
                }
                ConnectionType.USB -> {
                    var ok = usbManager?.imprimir(initPrinter) ?: false
                    logoBytes?.let {
                        ok = ok && (usbManager?.imprimir(alignCenter) ?: false)
                        ok = ok && (usbManager?.imprimir(it) ?: false)
                        ok = ok && (usbManager?.imprimir(alignLeft) ?: false)
                    }
                    ok = ok && (usbManager?.imprimir(textBytes) ?: false)
                    ok = ok && (usbManager?.imprimir(lineFeeds) ?: false)
                    ok = ok && (usbManager?.imprimir(cutPaper) ?: false)
                    ok
                }
                ConnectionType.NONE -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Convierte un Bitmap a comandos binarios ESC/POS para rasterización de imagen en la impresora.
     */
    private fun getLogoBytes(bitmap: Bitmap): ByteArray {

        val targetWidth = 384
        val height = Math.round(targetWidth.toDouble() / bitmap.width * bitmap.height).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, height, false)

        val width = scaled.width
        val widthBytes = (width + 7) / 8
        val data = ByteArray(8 + (widthBytes * height))

        data[0] = 0x1D; data[1] = 0x76; data[2] = 0x30; data[3] = 0x00
        data[4] = (widthBytes % 256).toByte()
        data[5] = (widthBytes / 256).toByte()
        data[6] = (height % 256).toByte()
        data[7] = (height / 256).toByte()

        var k = 8
        for (y in 0 until height) {
            for (x in 0 until widthBytes) {
                var b: Byte = 0
                for (bit in 0 until 8) {
                    val px = (x * 8) + bit
                    if (px < width) {
                        val pixel = scaled.getPixel(px, y)
                        val luminance = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                        if (luminance < 128 && Color.alpha(pixel) > 128) {
                            b = (b.toInt() or (1 shl (7 - bit))).toByte()
                        }
                    }
                }
                data[k++] = b
            }
        }
        return data
    }
}
