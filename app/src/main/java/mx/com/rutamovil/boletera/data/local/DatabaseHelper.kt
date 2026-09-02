package mx.com.rutamovil.boletera.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Ayudante de base de datos para la gestión de usuarios locales.
 * Administra la tabla 'usuarios' donde se guarda la información de los operadores,
 * contraseñas y detalles de la unidad asignada.
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "usuarios.db"
        private const val DATABASE_VERSION = 9
    }

    /**
     * Crea la tabla de usuarios con los campos necesarios para la autenticación y operación.
     */
    override fun onCreate(db: SQLiteDatabase) {
        val createTableUsuarios = ("CREATE TABLE usuarios( " +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "usuario TEXT UNIQUE, " +
                "contraseña TEXT, " +
                "identificador TEXT, " +
                "phone TEXT, " +
                "unidad_ruta TEXT)")
        db.execSQL(createTableUsuarios)
    }

    /**
     * Actualiza la base de datos eliminando la tabla existente y volviéndola a crear.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS usuarios")
        onCreate(db)
    }

    /**
     * Inserta un nuevo usuario en la base de datos local.
     * @param usuario Nombre de usuario (email).
     * @param contrasena Contraseña del usuario.
     * @param identificador Nombre real o ID del operador.
     * @param userPhone Teléfono del usuario.
     * @param unidadRuta Información de la unidad o ruta asignada.
     * @return True si la inserción fue exitosa, false en caso contrario.
     */
    fun insertarUsuario(usuario: String, contrasena: String, identificador: String, userPhone: String, unidadRuta: String): Boolean {
        val db = this.writableDatabase
        val valores = ContentValues().apply {
            put("usuario", usuario)
            put("contraseña", contrasena)
            put("identificador", identificador)
            put("phone", userPhone)
            put("unidad_ruta", unidadRuta)
        }
        val resultado = db.insert("usuarios", null, valores)
        db.close()
        return resultado != -1L
    }

    /**
     * Verifica si existe un usuario con las credenciales proporcionadas.
     * @param usuario Nombre de usuario.
     * @param contrasena Contraseña a validar.
     * @return True si coinciden las credenciales, false en caso contrario.
     */
    fun verificarUsuario(usuario: String, contrasena: String): Boolean {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM usuarios WHERE usuario=? AND contraseña=?", arrayOf(usuario, contrasena))
        val existe = cursor.count > 0
        cursor.close()
        db.close()
        return existe
    }

    /**
     * Comprueba si un correo electrónico ya está registrado en la base de datos local.
     * @param usuario Email a verificar.
     * @return True si ya existe, false si está disponible.
     */
    fun verificarEmail(usuario: String): Boolean {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM usuarios WHERE usuario=?", arrayOf(usuario))
        val existe = cursor.count > 0
        cursor.close()
        db.close()
        return existe
    }

    /**
     * Borra todos los registros de la tabla de usuarios.
     */
    fun borrarUsuarios() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM usuarios")
        db.close()
    }

    /**
     * Busca el ID local de un usuario dado su correo electrónico.
     * @param email Correo electrónico del usuario.
     * @return El ID numérico o -1 si no se encuentra.
     */
    fun obtenerIdUsuarioPorEmail(email: String): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT id FROM usuarios WHERE usuario = ?", arrayOf(email))
        var userId = -1
        if (cursor.moveToFirst()) {
            userId = cursor.getInt(0)
        }
        cursor.close()
        db.close()
        return userId
    }

    /**
     * Obtiene la información detallada de un usuario a partir de su ID local.
     * @param idUsuario ID del usuario.
     * @return Un [Cursor] con identificador, phone y unidad_ruta.
     */
    fun obtenerUsuarioPorId(idUsuario: Int): Cursor {
        val db = this.readableDatabase
        return db.rawQuery("SELECT identificador, phone, unidad_ruta FROM usuarios WHERE id = ?", arrayOf(idUsuario.toString()))
    }
}

