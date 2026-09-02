package mx.com.rutamovil.boletera.presentation.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import mx.com.rutamovil.boletera.R
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.local.DatabaseHelper
import mx.com.rutamovil.boletera.data.remote.ApiClient
import mx.com.rutamovil.boletera.data.remote.dto.LoginRequest
import mx.com.rutamovil.boletera.data.remote.dto.LoginResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Actividad encargada del registro local de nuevos usuarios (operadores).
 * Valida la existencia del operador en el servidor central antes de permitir el registro
 * en la base de datos local del dispositivo.
 */
class CrearUsuarioActivity : AppCompatActivity() {

    private lateinit var usuarioInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var identificadorInput: EditText
    private lateinit var btnCrear: Button
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crear)

        usuarioInput = findViewById(R.id.usuarioInput)
        passwordInput = findViewById(R.id.passwordInput)
        identificadorInput = findViewById(R.id.identificadorInput)
        btnCrear = findViewById(R.id.butto)
        dbHelper = DatabaseHelper(this)

        btnCrear.setOnClickListener {
            Utils.reproducirSonidoClick(this)
            registrarUsuario()
        }
    }

    /**
     * Ejecuta el flujo de registro: validación de sintaxis, comprobación en API
     * y persistencia local si el dispositivo y usuario son válidos.
     */
    private fun registrarUsuario() {
        val usuario = usuarioInput.text.toString().trim()
        val contrasena = passwordInput.text.toString().trim()
        val identificador = identificadorInput.text.toString().trim()

        if (usuario.isEmpty() || contrasena.isEmpty() || identificador.isEmpty()) {
            Utils.mostrarToast(this, "Completa todos los campos")
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(usuario).matches()) {
            Utils.mostrarToast(this, "Introduce un correo electrónico válido")
            return
        }

        val request = LoginRequest(usuario, contrasena)

        // Verificación proactiva con el servidor para asegurar que las credenciales son válidas y están autorizadas
        ApiClient.getApiService().login(request).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val userFromApi = body.data.user.email
                    val userPhone = body.data.user.phone
                    val cashPoints = body.data.cashPoints

                    val identificadorIngresado = identificadorInput.text.toString().trim()
                    var encontrado = false
                    var activo = false
                    var unidadRutaExtraida = "Sin Unidad"

                    // Validación del identificador físico del dispositivo contra los asignados al usuario en la nube
                    cashPoints?.let {
                        for (cp in it) {
                            if (cp.deviceIdentifier?.equals(identificadorIngresado, ignoreCase = true) == true) {
                                encontrado = true
                                if (cp.status.equals("active", ignoreCase = true)) {
                                    activo = true
                                    cp.unit?.let { unit ->
                                        val num = unit.number ?: "S/N"
                                        val ruta = unit.route?.name ?: ""
                                        unidadRutaExtraida = "$num - $ruta"
                                    }
                                }
                                break
                            }
                        }
                    }

                    if (!encontrado) {
                        Utils.mostrarToast(this@CrearUsuarioActivity, "Identificador no válido")
                        return
                    }

                    if (!activo) {
                        Utils.mostrarToast(this@CrearUsuarioActivity, "Error al registrar, Dispositivo bloqueado")
                        return
                    }

                    if (userFromApi == usuario) {
                        // Persistencia final en SQLite local
                        val insertado = dbHelper.insertarUsuario(usuario, contrasena, identificadorIngresado, userPhone ?: "", unidadRutaExtraida)
                        if (insertado) {
                            Utils.mostrarToast(this@CrearUsuarioActivity, "Usuario registrado con éxito\n$unidadRutaExtraida")
                            usuarioInput.setText("")
                            passwordInput.setText("")
                            identificadorInput.setText("")
                            startActivity(Intent(this@CrearUsuarioActivity, MainActivity::class.java))
                            finish()
                        } else {
                            Utils.mostrarToast(this@CrearUsuarioActivity, "Error: El usuario ya existe")
                        }
                    } else {
                        Utils.mostrarToast(this@CrearUsuarioActivity, "Para poder realizar el registro debe haber un registro previo en el servidor")
                    }
                } else {
                    Utils.mostrarToast(this@CrearUsuarioActivity, "Para poder realizar el registro debe haber un registro previo en el servidor")
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Utils.mostrarToast(this@CrearUsuarioActivity, "Error de red: ${t.message}")
            }
        })
    }
}
