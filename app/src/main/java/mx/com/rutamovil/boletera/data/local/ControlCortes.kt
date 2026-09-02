package mx.com.rutamovil.boletera.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import mx.com.rutamovil.boletera.data.remote.dto.TarifasResponse
import mx.com.rutamovil.boletera.data.remote.dto.TransactionSyncRequest
import mx.com.rutamovil.boletera.domain.model.CorteTotal
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Gestor principal de la base de datos SQLite para el control de operaciones.
 * Administra boletos, cortes parciales, cortes totales, caché de tarifas y logs de errores.
 */
class ControlCortes(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "control_cortes.db"
        private const val DATABASE_VERSION = 27

        private const val TABLE_PARCIALES = "cortesParciales"
        private const val TABLE_DETALLE_PARCIAL = "DetalleCorteParcial"
        private const val TABLE_CORTE_TOTAL = "corte_total"
        private const val TABLE_BOLETOS = "boletos_vendidos"
        private const val TABLE_TARIFAS_CACHE = "tarifas_cache"
        private const val TABLE_LOG_ERRORES = "log_errores"
    }

    /**
     * Crea las tablas necesarias para el funcionamiento del sistema de boletaje.
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE_DETALLE_PARCIAL (id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT, timestamp TEXT, route_fare_id INTEGER, quantity INTEGER, price REAL, status INTEGER);")
        db.execSQL("CREATE TABLE $TABLE_CORTE_TOTAL(id INTEGER PRIMARY KEY AUTOINCREMENT, numero_corte_total INTEGER, fecha_hora TEXT, tipo TEXT, cantidad INTEGER, total REAL, status INTEGER);")
        db.execSQL("CREATE TABLE $TABLE_BOLETOS (id INTEGER PRIMARY KEY AUTOINCREMENT, tipo TEXT, routeFareId INTEGER, precio REAL, fecha TEXT, latitud TEXT DEFAULT '0.0', longitud TEXT DEFAULT '0.0', status INTEGER DEFAULT 0, enviado_web INTEGER DEFAULT 0, numero_corte INTEGER DEFAULT 0);")
        db.execSQL("CREATE TABLE $TABLE_PARCIALES (id INTEGER PRIMARY KEY AUTOINCREMENT, numero_corte INTEGER, tipo TEXT, cantidad INTEGER, total REAL, fechaHora TEXT, status INTEGER);")
        db.execSQL("CREATE TABLE $TABLE_TARIFAS_CACHE (id INTEGER PRIMARY KEY AUTOINCREMENT, route_fare_id INTEGER, passenger_type TEXT, price TEXT, fare_name TEXT);")
        db.execSQL("CREATE TABLE $TABLE_LOG_ERRORES (id INTEGER PRIMARY KEY AUTOINCREMENT, fecha TEXT, mensaje TEXT, stacktrace TEXT, enviado INTEGER DEFAULT 0);")
    }

    /**
     * Maneja la evolución del esquema de la base de datos entre versiones.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 27) {
            try {
                // Actualizaciones incrementales del esquema
                if (oldVersion < 23) {
                    db.execSQL("ALTER TABLE $TABLE_BOLETOS ADD COLUMN latitud TEXT DEFAULT '0.0'")
                    db.execSQL("ALTER TABLE $TABLE_BOLETOS ADD COLUMN longitud TEXT DEFAULT '0.0'")
                }
                if (oldVersion < 24) db.execSQL("ALTER TABLE $TABLE_BOLETOS ADD COLUMN enviado_web INTEGER DEFAULT 0")
                if (oldVersion < 25) db.execSQL("ALTER TABLE $TABLE_BOLETOS ADD COLUMN numero_corte INTEGER DEFAULT 0")
                if (oldVersion < 26) db.execSQL("CREATE TABLE $TABLE_LOG_ERRORES (id INTEGER PRIMARY KEY AUTOINCREMENT, fecha TEXT, mensaje TEXT, stacktrace TEXT, enviado INTEGER DEFAULT 0);")
                if (oldVersion < 27) db.execSQL("ALTER TABLE $TABLE_LOG_ERRORES ADD COLUMN enviado INTEGER DEFAULT 0")
            } catch (e: Exception) {
                Log.e("DB", "Error al añadir columnas", e)
            }
        } else {
            // Re-creación total si la versión es muy antigua o hay inconsistencias
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DETALLE_PARCIAL")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CORTE_TOTAL")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_BOLETOS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PARCIALES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TARIFAS_CACHE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOG_ERRORES")
            onCreate(db)
        }
    }

    // --- LOGS ---

    /**
     * Inserta un registro de error o evento significativo en la base de datos.
     * @param mensaje Descripción breve del error.
     * @param stacktrace Detalle técnico de la excepción.
     */
    fun insertarLog(mensaje: String, stacktrace: String) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("fecha", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("mensaje", mensaje)
            put("stacktrace", stacktrace)
            put("enviado", 0)
        }
        db.insert(TABLE_LOG_ERRORES, null, v)
        db.close()
    }

    /**
     * Obtiene todos los logs almacenados de forma descendente por ID.
     * @return [Cursor] con los registros de logs.
     */
    fun obtenerLogs(): Cursor = readableDatabase.rawQuery("SELECT * FROM $TABLE_LOG_ERRORES ORDER BY id DESC", null)

    /**
     * Elimina todos los registros de la tabla de logs.
     */
    fun limpiarLogs() {
        val db = writableDatabase
        db.execSQL("DELETE FROM $TABLE_LOG_ERRORES")
        db.close()
    }

    /**
     * Recupera los logs que aún no han sido sincronizados con el servidor.
     * @return [Cursor] con logs pendientes de envío.
     */
    fun obtenerLogsPendientes(): Cursor? = readableDatabase.rawQuery("SELECT * FROM $TABLE_LOG_ERRORES WHERE enviado = 0 ORDER BY id ASC", null)

    /**
     * Marca una lista de logs como sincronizados correctamente.
     * @param ids Lista de identificadores de los logs.
     */
    fun marcarLogsComoEnviados(ids: List<Int>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        val v = ContentValues().apply { put("enviado", 1) }
        db.update(TABLE_LOG_ERRORES, v, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
        db.close()
    }

    // --- TARIFAS CACHE ---

    /**
     * Borra la información de tarifas almacenada localmente.
     */
    fun limpiarTarifasCache() {
        val db = writableDatabase
        db.execSQL("DELETE FROM $TABLE_TARIFAS_CACHE")
        db.close()
    }

    /**
     * Almacena una tarifa descargada del servidor en la caché local.
     * @param routeFareId ID único de la tarifa en la ruta.
     * @param passengerType Tipo de pasajero (ej. Estudiante).
     * @param price Precio en formato texto.
     * @param fareName Nombre descriptivo de la tarifa.
     */
    fun guardarTarifaCache(routeFareId: Int, passengerType: String, price: String, fareName: String) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("route_fare_id", routeFareId)
            put("passenger_type", passengerType)
            put("price", price)
            put("fare_name", fareName)
        }
        db.insert(TABLE_TARIFAS_CACHE, null, v)
        db.close()
    }

    /**
     * Recupera la lista de tarifas almacenadas en la caché local.
     * @return Lista de objetos de tipo [TarifasResponse.Fare].
     */
    fun obtenerTarifasCache(): List<TarifasResponse.Fare> {
        val lista = mutableListOf<TarifasResponse.Fare>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_TARIFAS_CACHE", null)
        if (cursor.moveToFirst()) {
            do {
                val tarifa = TarifasResponse.Fare(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("route_fare_id")),
                    passenger_type = cursor.getString(cursor.getColumnIndexOrThrow("passenger_type")),
                    price = cursor.getString(cursor.getColumnIndexOrThrow("price")),
                    fare = cursor.getString(cursor.getColumnIndexOrThrow("fare_name"))
                )
                lista.add(tarifa)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return lista
    }

    // --- CORTES Y DETALLES ---

    /**
     * Registra un nuevo corte parcial en la base de datos.
     * @return El ID de la fila insertada o -1 en caso de error.
     */
    fun insertarCorteParcialNuevo(num: Int, tipo: String, cant: Int, total: Double, fecha: String, st: Int): Long {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("numero_corte", num)
            put("tipo", tipo)
            put("cantidad", cant)
            put("total", total)
            put("fechaHora", fecha)
            put("status", st)
        }
        val res = db.insert(TABLE_PARCIALES, null, v)
        db.close()
        return res
    }

    /**
     * Registra un encabezado o detalle para un corte total.
     * @return El ID de la fila insertada.
     */
    fun insertarCorteTotalDetalle(num: Int, fecha: String, tipo: String, cant: Int, total: Double, st: Int): Long {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("numero_corte_total", num)
            put("fecha_hora", fecha)
            put("tipo", tipo)
            put("cantidad", cant)
            put("total", total)
            put("status", st)
        }
        val res = db.insert(TABLE_CORTE_TOTAL, null, v)
        db.close()
        return res
    }

    /**
     * Guarda el detalle estructurado de un corte para su sincronización.
     */
    fun guardarDetalleCorte(user: String, ts: String, id: Int, cant: Int, precio: Double, st: Int): Long {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("user", user)
            put("timestamp", ts)
            put("route_fare_id", id)
            put("quantity", cant)
            put("price", precio)
            put("status", st)
        }
        val res = db.insert(TABLE_DETALLE_PARCIAL, null, v)
        db.close()
        return res
    }

    /**
     * Inserta un boleto vendido en la base de datos, incluyendo su ubicación geográfica.
     */
    fun insertarBoleto(tipo: String, routeFareId: Int, precio: Double, fecha: String, lat: String, lon: String) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("tipo", tipo)
            put("routeFareId", routeFareId)
            put("precio", precio)
            put("fecha", fecha)
            put("latitud", lat)
            put("longitud", lon)
            put("status", 0)
            put("enviado_web", 0)
        }
        db.insert(TABLE_BOLETOS, null, v)
        Log.d("BOLETO_INSERT", "Insertado: $tipo - $$precio - ID: $routeFareId")
        db.close()
    }

    /**
     * Asocia todos los boletos actualmente en estado 'pendiente' a un número de corte específico.
     */
    fun asignarCorteABoletosPendientes(numeroCorte: Int) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("numero_corte", numeroCorte)
            put("status", 5) // BLOQUEADO PARA CORTE
        }
        db.update(TABLE_BOLETOS, v, "status = 0", null)
        db.close()
    }

    /**
     * Obtiene un resumen consolidado de los pasajeros y montos recaudados por tipo de tarifa.
     * @return [Cursor] con el resumen agrupado.
     */
    fun getResumenCortesParciales(): Cursor? = readableDatabase.rawQuery("SELECT tipo, SUM(cantidad) AS totalPasajeros, SUM(total) AS totalRecaudado FROM $TABLE_PARCIALES WHERE status = 1 GROUP BY tipo", null)

    /**
     * Recupera los boletos vendidos que aún no han sido incluidos en ningún corte, normalizando los nombres de tipo.
     * @return [Cursor] con boletos agrupados por ID de tarifa y tipo.
     */
    fun obtenerBoletosVendidosAgrupados(): Cursor? {
        val db = writableDatabase
        // Normalización de tipos de pasajeros para asegurar consistencia en reportes
        db.execSQL("UPDATE $TABLE_BOLETOS SET tipo = 'REGULAR' WHERE tipo LIKE 'REGULAR%'")
        db.execSQL("UPDATE $TABLE_BOLETOS SET tipo = 'ESTUDIANTE' WHERE tipo LIKE 'ESTUDIANTE%'")
        db.execSQL("UPDATE $TABLE_BOLETOS SET tipo = '3ERA. EDAD' WHERE tipo LIKE '3ERA. EDAD%'")
        db.execSQL("UPDATE $TABLE_BOLETOS SET tipo = 'PERSONA CON DISCAPACIDAD' WHERE tipo LIKE 'PERSONA CON DISCAPACIDAD%' OR tipo LIKE 'PCD%'")

        return readableDatabase.rawQuery("SELECT routeFareId, tipo, COUNT(*) AS cantidad, SUM(precio) AS total FROM $TABLE_BOLETOS WHERE status = 0 GROUP BY routeFareId, tipo", null)
    }

    /**
     * Genera una lista de objetos JSON que representan los cortes parciales con sus ventas anidadas.
     */
    fun obtenerTodosLosCortesParcialesEstructurado(): List<JSONObject> = empaquetarCortes("SELECT * FROM $TABLE_DETALLE_PARCIAL WHERE status = 1 ORDER BY timestamp DESC")
    
    /**
     * Obtiene los cortes parciales que fallaron en su sincronización previa.
     */
    fun cortesParcialesNoEnviados(): List<JSONObject> = empaquetarCortes("SELECT * FROM $TABLE_DETALLE_PARCIAL WHERE status = 3 ORDER BY timestamp DESC")

    /**
     * Proceso interno para transformar filas de la tabla de detalles en una estructura jerárquica JSON.
     */
    private fun empaquetarCortes(query: String): List<JSONObject> {
        val lista = mutableListOf<JSONObject>()
        val db = readableDatabase
        try {
            db.rawQuery(query, null).use { c ->
                val mapa = mutableMapOf<String, JSONObject>()
                if (c.moveToFirst()) {
                    do {
                        val u = c.getString(c.getColumnIndexOrThrow("user"))
                        val ts = c.getString(c.getColumnIndexOrThrow("timestamp"))
                        val id = c.getInt(c.getColumnIndexOrThrow("route_fare_id"))
                        val q = c.getInt(c.getColumnIndexOrThrow("quantity"))
                        val p = c.getDouble(c.getColumnIndexOrThrow("price"))
                        val key = "${u}_$ts"
                        if (!mapa.containsKey(key)) {
                            val obj = JSONObject().apply {
                                put("user", u)
                                put("timestamp", ts)
                                put("sales", JSONArray())
                            }
                            mapa[key] = obj
                        }
                        val v = JSONObject().apply {
                            put("route_fare_id", id)
                            put("quantity", q)
                            put("price", p.toInt())
                        }
                        mapa[key]?.getJSONArray("sales")?.put(v)
                    } while (c.moveToNext())
                }
                lista.addAll(mapa.values)
            }
        } catch (e: Exception) {
            Log.e("DB", "Error empaquetando", e)
        }
        db.close()
        return lista
    }

    /**
     * Prepara la lista de transacciones individuales para el servicio de sincronización web.
     */
    fun obtenerTransaccionesNoSincronizadas(routeName: String, unitNumber: String, cashPointId: Int): List<TransactionSyncRequest.Transaction> {
        val transacciones = mutableListOf<TransactionSyncRequest.Transaction>()
        val db = readableDatabase
        db.rawQuery("SELECT id, tipo, routeFareId, precio, fecha, latitud, longitud FROM $TABLE_BOLETOS WHERE enviado_web = 0", null).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val routeFareId = cursor.getInt(cursor.getColumnIndexOrThrow("routeFareId"))
                    val passengerType = cursor.getString(cursor.getColumnIndexOrThrow("tipo"))
                    val precio = cursor.getDouble(cursor.getColumnIndexOrThrow("precio"))
                    if (precio < 0.01) continue

                    val fecha = cursor.getString(cursor.getColumnIndexOrThrow("fecha"))
                    var latitud: String? = cursor.getString(cursor.getColumnIndexOrThrow("latitud"))
                    var longitud: String? = cursor.getString(cursor.getColumnIndexOrThrow("longitud"))

                    // Validación de coordenadas nulas o vacías
                    if (latitud == "0.0" || latitud?.isEmpty() == true) latitud = null
                    if (longitud == "0.0" || longitud?.isEmpty() == true) longitud = null

                    val fareName = "Tarifa ${passengerType.lowercase()}"
                    transacciones.add(TransactionSyncRequest.Transaction(
                        route_fare_id = routeFareId,
                        cash_point_id = cashPointId,
                        route_name = routeName,
                        unit_number = unitNumber,
                        fare_name = fareName,
                        passenger_type = passengerType,
                        price = precio,
                        quantity = 1,
                        total_price = precio,
                        latitude = latitud,
                        longitude = longitud,
                        status = "approved",
                        event_date = fecha
                    ))
                } while (cursor.moveToNext())
            }
        }
        db.close()
        return transacciones
    }

    /**
     * Marca todos los boletos actuales como sincronizados exitosamente con la plataforma web.
     */
    fun marcarTransaccionesComoEnviadasWeb() {
        val db = writableDatabase
        val v = ContentValues().apply { put("enviado_web", 1) }
        db.update(TABLE_BOLETOS, v, "enviado_web = ?", arrayOf("0"))
        db.close()
    }

    /**
     * Cambia el estado de los cortes parciales activos a un estado de 'totalizado' o archivado.
     */
    fun marcarParcialesComoTotalizados() {
        val db = writableDatabase
        val v = ContentValues().apply { put("status", 2) }
        db.update(TABLE_PARCIALES, v, "status = ?", arrayOf("1"))
        db.update(TABLE_DETALLE_PARCIAL, v, "status = ?", arrayOf("1"))
        db.close()
    }

    // Métodos de actualización de estatus simplificados
    fun actualizarEstatusDetalleCorte(st: Int) { actualizarEstado(TABLE_DETALLE_PARCIAL, st, "status = ?", arrayOf("1")) }
    fun actualizarEstatusCortesNoEnviados(st: Int) { actualizarEstado(TABLE_DETALLE_PARCIAL, st, "status = ?", arrayOf("3")) }
    fun actualizarEstatusCorteTotal(st: Int) { actualizarEstado(TABLE_CORTE_TOTAL, st, "status = ?", arrayOf("1")) }
    fun actualizarEstatusCorteTotalNoEnviado(st: Int) { actualizarEstado(TABLE_CORTE_TOTAL, st, "status = ?", arrayOf("3")) }

    fun actualizarEstatusCortesParcialesPorNumero(st: Int, numeroCorte: Int) {
        actualizarEstado(TABLE_PARCIALES, st, "numero_corte = ? AND status = 0", arrayOf(numeroCorte.toString()))
    }

    fun actualizarEstatusCortesParcialesNoSincronizados(st: Int, numeroCorte: Int) {
        actualizarEstado(TABLE_PARCIALES, st, "numero_corte = ? AND status = 0", arrayOf(numeroCorte.toString()))
    }

    fun actualizarEstatusCortesParcialesASincronizado(st: Int) { actualizarEstado(TABLE_PARCIALES, st, "status = ?", arrayOf("3")) }

    fun actualizarEstatusBoletosPorCorte(st: Int, numeroCorte: Int) {
        actualizarEstado(TABLE_BOLETOS, st, "numero_corte = ? AND status = 5", arrayOf(numeroCorte.toString()))
    }

    fun actualizarEstatusBoletosReenviados(st: Int) { actualizarEstado(TABLE_BOLETOS, st, "status = ?", arrayOf("3")) }

    /**
     * Método genérico para actualizar el campo status de una tabla basándose en criterios.
     */
    private fun actualizarEstado(tabla: String, nuevoStatus: Int, where: String, args: Array<String>) {
        val db = writableDatabase
        val v = ContentValues().apply { put("status", nuevoStatus) }
        db.update(tabla, v, where, args)
        db.close()
    }

    /**
     * Recupera el historial completo de cortes totales realizados.
     */
    fun getCortesTotales(): List<CorteTotal> = armarListaCortesTotales("SELECT numero_corte_total, fecha_hora, status FROM $TABLE_CORTE_TOTAL WHERE status IN (1, 2, 3) GROUP BY numero_corte_total ORDER BY fecha_hora DESC", null, true)
    
    /**
     * Busca cortes totales que coincidan con una fecha específica.
     */
    fun getCortesPorFecha(fecha: String): List<CorteTotal> = armarListaCortesTotales("SELECT numero_corte_total, fecha_hora, status FROM $TABLE_CORTE_TOTAL WHERE status IN (1, 2, 3) AND fecha_hora LIKE ? GROUP BY numero_corte_total ORDER BY fecha_hora DESC", arrayOf("${formatearFecha(fecha)}%"), true)
    
    /**
     * Busca cortes parciales que coincidan con una fecha específica.
     */
    fun getCortesParcialesPorFecha(fecha: String): List<CorteTotal> = armarListaCortesTotales("SELECT numero_corte, fechaHora, status FROM $TABLE_PARCIALES WHERE status IN (1, 2, 3) AND fechaHora LIKE ? GROUP BY numero_corte ORDER BY fechaHora DESC", arrayOf("${formatearFecha(fecha)}%"), false)

    /**
     * Genera objetos de modelo de vista para mostrar en las pantallas de historial, consolidando la información de cada corte.
     */
    private fun armarListaCortesTotales(query: String, args: Array<String>?, esTotal: Boolean): List<CorteTotal> {
        val cortes = mutableListOf<CorteTotal>()
        val db = readableDatabase
        db.rawQuery(query, args).use { c ->
            if (c.moveToFirst()) {
                do {
                    val colNum = if (esTotal) "numero_corte_total" else "numero_corte"
                    val colFec = if (esTotal) "fecha_hora" else "fechaHora"
                    val tabla = if (esTotal) TABLE_CORTE_TOTAL else TABLE_PARCIALES
                    val num = c.getInt(c.getColumnIndexOrThrow(colNum))
                    val fec = c.getString(c.getColumnIndexOrThrow(colFec))
                    val st = c.getInt(c.getColumnIndexOrThrow("status"))
                    val sb = StringBuilder("Fecha y hora: $fec\n")
                    var totalC = 0.0

                    // Subconsulta para agrupar ventas dentro del mismo corte
                    val subQuery = "SELECT CASE WHEN tipo LIKE 'REGULAR%' THEN 'REGULAR' WHEN tipo LIKE 'ESTUDIANTE%' THEN 'ESTUDIANTE' WHEN tipo LIKE '3ERA. EDAD%' THEN '3ERA. EDAD' WHEN tipo LIKE 'PERSONA CON DISCAPACIDAD%' OR tipo LIKE 'PCD%' THEN 'PCD' ELSE tipo END as tipo_limpio, SUM(cantidad) AS c, SUM(total) AS t FROM $tabla WHERE $colNum = ? AND status IN (1, 2, 3) GROUP BY tipo_limpio"

                    db.rawQuery(subQuery, arrayOf(num.toString())).use { d ->
                        if (d.moveToFirst()) {
                            do {
                                val tipo = d.getString(0)
                                val cant = d.getInt(1)
                                val tot = d.getDouble(2)
                                sb.append("$tipo: $cant - $${String.format(Locale.US, "%.2f", tot)}\n")
                                totalC += tot
                            } while (d.moveToNext())
                        }
                    }
                    sb.append("Total Recaudado: $${String.format(Locale.US, "%.2f", totalC)}")
                    cortes.add(CorteTotal("${if (esTotal) "Corte Total #" else "Corte Parcial #"}$num", sb.toString(), st))
                } while (c.moveToNext())
            }
        }
        db.close()
        return cortes
    }

    /**
     * Obtiene la lista de ventas individuales activas (que no pertenecen aún a un corte).
     */
    fun getVentas(): List<CorteTotal> = extraerVentasSimples("SELECT * FROM $TABLE_BOLETOS WHERE status = 0 ORDER BY fecha DESC", null)
    
    /**
     * Obtiene el historial de ventas individuales para una fecha determinada.
     */
    fun getVentasPorFecha(fecha: String): List<CorteTotal> {
        val fechaFormateada = formatearFecha(fecha)
        val hoy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val query: String
        val args: Array<String>
        if (fechaFormateada == hoy) {
            query = "SELECT * FROM $TABLE_BOLETOS WHERE (status IN (0, 1, 3) AND fecha LIKE ?) OR (status = 0) ORDER BY fecha DESC"
            args = arrayOf("$fechaFormateada%")
        } else {
            query = "SELECT * FROM $TABLE_BOLETOS WHERE status IN (0, 1, 3) AND fecha LIKE ? ORDER BY fecha DESC"
            args = arrayOf("$fechaFormateada%")
        }
        return extraerVentasSimples(query, args)
    }

    /**
     * Extrae ventas individuales y las formatea para su visualización en una lista.
     */
    private fun extraerVentasSimples(query: String, args: Array<String>?): List<CorteTotal> {
        val ventas = mutableListOf<CorteTotal>()
        val db = readableDatabase
        db.rawQuery(query, args).use { c ->
            if (c.moveToFirst()) {
                do {
                    val t = c.getString(c.getColumnIndexOrThrow("tipo"))
                    val f = c.getString(c.getColumnIndexOrThrow("fecha"))
                    val p = c.getDouble(c.getColumnIndexOrThrow("precio"))
                    val st = c.getInt(c.getColumnIndexOrThrow("status"))
                    var numCorte = 0
                    val colIndex = c.getColumnIndex("numero_corte")
                    if (colIndex != -1) numCorte = c.getInt(colIndex)
                    var infoAdicional = "Fecha: $f\nTipo: $t\nPrecio: $${String.format(Locale.US, "%.2f", p)}"
                    if (numCorte > 0) infoAdicional += "\nCorte Parcial Asociado: #$numCorte"
                    else if (st == 0) infoAdicional += "\n(Boleto sin corte)"
                    ventas.add(CorteTotal("Venta de boleto: $t", infoAdicional, st))
                } while (c.moveToNext())
            }
        }
        db.close()
        return ventas
    }

    /**
     * Indica si existen cortes parciales pendientes de ser sincronizados con el servidor.
     */
    fun existenCortesPendientes(): Boolean {
        readableDatabase.rawQuery("SELECT id FROM $TABLE_PARCIALES WHERE status = 3 LIMIT 1", null).use {
            return it.moveToFirst()
        }
    }

    /**
     * Convierte una fecha de formato visual dd/MM/yyyy a formato de base de datos yyyy-MM-dd.
     */
    private fun formatearFecha(ddMMyyyy: String): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(ddMMyyyy)!!)
        } catch (e: ParseException) { ddMMyyyy }
    }

    /**
     * Verifica si existen ventas o cortes de días previos que no se han cerrado o sincronizado.
     */
    fun existenPendientesDeDiasAnteriores(): Boolean {
        val fechaHoy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val db = readableDatabase
        val cBoletos = db.rawQuery("SELECT id FROM $TABLE_BOLETOS WHERE status = 0 AND fecha NOT LIKE ?", arrayOf("$fechaHoy%"))
        val hayBoletosAtrasados = cBoletos.moveToFirst()
        cBoletos.close()

        val cParciales = db.rawQuery("SELECT id FROM $TABLE_PARCIALES WHERE status = 1 AND fechaHora NOT LIKE ?", arrayOf("$fechaHoy%"))
        val hayParcialesAtrasados = cParciales.moveToFirst()
        cParciales.close()

        db.close()
        return hayBoletosAtrasados || hayParcialesAtrasados
    }
}

