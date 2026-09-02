package mx.com.rutamovil.boletera.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import mx.com.rutamovil.boletera.data.local.UsbPrinterManager
import java.io.OutputStream

/**
 * Controlador singleton encargado de gestionar la conexión y comunicación con impresoras.
 * Soporta conexiones mediante Bluetooth y USB, proporcionando métodos para impresión
 * de texto y gráficos (logos).
 */
class ImpresoraController private constructor() {

    private var usbManager: UsbPrinterManager? = null

    /**
     * Enumeración de los tipos de conexión soportados.
     */
    enum class TipoConexion { NINGUNA, BLUETOOTH, USB }

    /**
     * Almacena el tipo de conexión que se encuentra activa actualmente.
     */
    var conexionActual = TipoConexion.NINGUNA

    companion object {
        @Volatile
        private var instancia: ImpresoraController? = null

        /**
         * Obtiene la instancia única de [ImpresoraController].
         * @return Instancia del controlador.
         */
        fun getInstance(): ImpresoraController {
            return instancia ?: synchronized(this) {
                instancia ?: ImpresoraController().also { instancia = it }
            }
        }
    }

    /**
     * Inicializa o actualiza el manejador de impresora USB.
     * @param context Contexto de la aplicación.
     */
    fun initUsbManager(context: Context) {
        if (usbManager == null) {
            usbManager = UsbPrinterManager(context)
        } else {
            usbManager?.setContext(context)
        }
    }

    /**
     * Obtiene el manejador de impresora USB actual.
     * @return Instancia de [UsbPrinterManager] o null si no se ha inicializado.
     */
    fun getUsbManager(): UsbPrinterManager? = usbManager

    /**
     * Verifica si existe una conexión activa con alguna impresora.
     * @return True si está conectada por Bluetooth o USB, false en caso contrario.
     */
    fun estaConectada(): Boolean {
        return when (conexionActual) {
            TipoConexion.BLUETOOTH -> BluetoothActivity.bluetoothSocket?.isConnected == true
            TipoConexion.USB -> usbManager?.estaConectada() == true
            TipoConexion.NINGUNA -> false
        }
    }

    /**
     * Verifica el estado del papel en la impresora conectada.
     * @return True si hay papel o si el estado no puede determinarse (como en USB), false si no hay papel.
     */
    fun verificarPapel(): Boolean {
        return when (conexionActual) {
            TipoConexion.BLUETOOTH -> BluetoothActivity.verificarPapel()
            TipoConexion.USB -> true
            TipoConexion.NINGUNA -> false
        }
    }

    /**
     * Intenta escribir datos en el socket de Bluetooth con un tiempo de espera definido.
     * Si la operación excede los 800ms, se considera fallida y se cierra el socket.
     * @param datos Arreglo de bytes a enviar.
     * @return True si el envío fue exitoso, false en caso contrario.
     */
    private fun escribirBluetoothConTimeout(datos: ByteArray): Boolean {
        val socket = BluetoothActivity.bluetoothSocket ?: return false
        if (!socket.isConnected) return false

        val exito = booleanArrayOf(false)

        // Hilo dedicado para la escritura con el fin de evitar bloqueos en el UI
        val writeThread = Thread {
            try {
                val os: OutputStream = socket.outputStream
                os.write(datos)
                os.flush()
                exito[0] = true
            } catch (e: Exception) {
                exito[0] = false
            }
        }

        writeThread.start()

        try {
            // Timeout de 800ms para la operación de escritura
            writeThread.join(800)
            if (writeThread.isAlive) {
                try { socket.close() } catch (ignored: Exception) {}
                BluetoothActivity.bluetoothSocket = null
                conexionActual = TipoConexion.NINGUNA
                return false
            }
        } catch (e: InterruptedException) {
            return false
        }

        if (!exito[0]) {
            try { socket.close() } catch (ignored: Exception) {}
            BluetoothActivity.bluetoothSocket = null
            conexionActual = TipoConexion.NINGUNA
        }

        return exito[0]
    }

    /**
     * Realiza la impresión de una cadena de texto.
     * @param content Texto a imprimir.
     * @return True si la impresión se completó correctamente, false en caso de error.
     */
    fun imprimir(content: String): Boolean {
        if (!estaConectada()) return false

        try {
            // Comandos ESC/POS básicos: inicializar, saltos de línea y corte de papel
            val initPrinter = byteArrayOf(0x1B, 0x40)
            val textBytes = content.toByteArray(charset("UTF-8"))
            val lineFeeds = "\n\n".toByteArray()
            val cutPaper = byteArrayOf(0x1D, 0x56, 0x41, 0x10)

            return when (conexionActual) {
                TipoConexion.BLUETOOTH -> {
                    val paqueteCompleto = initPrinter + textBytes + lineFeeds + cutPaper
                    escribirBluetoothConTimeout(paqueteCompleto)
                }
                TipoConexion.USB -> {
                    var success = usbManager?.imprimir(initPrinter) ?: false
                    success = success && (usbManager?.imprimir(textBytes) ?: false)
                    success = success && (usbManager?.imprimir(lineFeeds) ?: false)
                    success = success && (usbManager?.imprimir(cutPaper) ?: false)
                    success
                }
                TipoConexion.NINGUNA -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Realiza la impresión de texto incluyendo una imagen (logo) al inicio.
     * @param content Texto a imprimir después del logo.
     * @param logo Imagen en formato [Bitmap] para imprimir.
     * @return True si el proceso fue exitoso, false en caso contrario.
     */
    fun imprimirConLogo(content: String, logo: Bitmap?): Boolean {
        if (!estaConectada()) return false

        try {
            val initPrinter = byteArrayOf(0x1B, 0x40)
            val alignCenter = byteArrayOf(0x1B, 0x61, 1)
            val alignLeft = byteArrayOf(0x1B, 0x61, 0)
            val textBytes = content.toByteArray(charset("UTF-8"))
            val lineFeeds = "\n\n".toByteArray()
            val cutPaper = byteArrayOf(0x1D, 0x56, 0x41, 0x10)

            val logoBytes = logo?.let { getBitmapToEscPosBytes(it) }

            return when (conexionActual) {
                TipoConexion.BLUETOOTH -> {
                    if (!escribirBluetoothConTimeout(initPrinter)) return false
                    logoBytes?.let {
                        escribirBluetoothConTimeout(alignCenter)
                        escribirBluetoothConTimeout(it)
                        escribirBluetoothConTimeout(alignLeft)
                    }
                    escribirBluetoothConTimeout(textBytes) &&
                            escribirBluetoothConTimeout(lineFeeds) &&
                            escribirBluetoothConTimeout(cutPaper)
                }
                TipoConexion.USB -> {
                    var success = usbManager?.imprimir(initPrinter) ?: false
                    logoBytes?.let {
                        success = success && (usbManager?.imprimir(alignCenter) ?: false)
                        success = success && (usbManager?.imprimir(it) ?: false)
                        success = success && (usbManager?.imprimir(alignLeft) ?: false)
                    }
                    success = success && (usbManager?.imprimir(textBytes) ?: false)
                    success = success && (usbManager?.imprimir(lineFeeds) ?: false)
                    success = success && (usbManager?.imprimir(cutPaper) ?: false)
                    success
                }
                TipoConexion.NINGUNA -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Convierte un objeto [Bitmap] a un arreglo de bytes compatible con comandos ESC/POS para impresión de imágenes.
     * Escala la imagen a un ancho estándar y procesa la luminancia para convertirla a blanco y negro.
     * @param bitmap Imagen original.
     * @return Arreglo de bytes listo para ser enviado a la impresora.
     */
    private fun getBitmapToEscPosBytes(bitmap: Bitmap): ByteArray {
        val targetWidth = 384
        val height = Math.round(targetWidth.toDouble() / bitmap.width * bitmap.height).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, height, false)

        val width = scaledBitmap.width
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
                    val pixelX = (x * 8) + bit
                    if (pixelX < width) {
                        val pixel = scaledBitmap.getPixel(pixelX, y)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val bColor = Color.blue(pixel)
                        val luminance = (0.299 * r + 0.587 * g + 0.114 * bColor).toInt()
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
