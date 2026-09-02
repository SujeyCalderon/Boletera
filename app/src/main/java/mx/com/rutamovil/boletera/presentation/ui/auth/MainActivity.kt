package mx.com.rutamovil.boletera.presentation.ui.auth

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import mx.com.rutamovil.boletera.R
import mx.com.rutamovil.boletera.common.CrashHandler
import mx.com.rutamovil.boletera.data.manager.LogUploader
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.local.ControlCortes
import mx.com.rutamovil.boletera.data.local.DatabaseHelper
import mx.com.rutamovil.boletera.data.remote.ApiClient
import mx.com.rutamovil.boletera.data.remote.dto.LoginRequest
import mx.com.rutamovil.boletera.data.remote.dto.LoginResponse
import mx.com.rutamovil.boletera.data.remote.dto.TarifasRequest
import mx.com.rutamovil.boletera.data.remote.dto.TarifasResponse
import mx.com.rutamovil.boletera.presentation.ui.core.CobroActivity
import mx.com.rutamovil.boletera.presentation.ui.device.BluetoothActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Actividad de autenticación inicial.
 * Gestiona el inicio de sesión de operadores, validación de credenciales locales y remotas,
 * y la descarga de catálogos de tarifas necesarios para la operación.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var etUsuario: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnIngresar: Button
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        // Configuración del manejador de errores fatales para trazabilidad de fallos
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        super.onCreate(savedInstanceState)

        try {
            startLockTask() // Intento de fijar la pantalla para control de flota
        } catch (e: Exception) {
            Log.e("SEGURIDAD", "No se pudo fijar la pantalla: ${e.message}")
        }

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val tokenGuardado = prefs.getString("accessToken", null)

        // Si ya existe un token, se omite el login y se pasa directo a la operativa
        if (tokenGuardado != null) {
            startActivity(Intent(this, CobroActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        etUsuario = findViewById(R.id.textInputEditTextUsuario)
        etPassword = findViewById(R.id.textInputEditTextPassword)
        btnIngresar = findViewById(R.id.button)
        dbHelper = DatabaseHelper(this)

        findViewById<TextView>(R.id.textView4).setOnClickListener {
            Utils.mostrarToast(this, "Crear cuenta")
            Utils.reproducirSonidoClick(this)
            startActivity(Intent(this, CrearUsuarioActivity::class.java))
        }

        btnIngresar.setOnClickListener {
            Utils.reproducirSonidoClick(this)
            validarLogin()
        }
    }

    /**
     * Procesa el intento de acceso validando campos, conectividad y sincronización con el servidor.
     */
    private fun validarLogin() {
        val usuario = etUsuario.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (usuario.isEmpty() || password.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Ingrese usuario y contraseña")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }.show()
            return
        }

        if (hayConexionInternet()) {
            // Flujo con Internet: Verificación en servidor y descarga de tarifas actualizada
            if (!dbHelper.verificarEmail(usuario)) {
                AlertDialog.Builder(this)
                    .setTitle("Registro local requerido")
                    .setMessage("Este usuario no está registrado localmente. Debes registrarte primero.")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }.show()
                return
            }

            if (!dbHelper.verificarUsuario(usuario, password)) {
                Utils.mostrarToast(this, "Contraseña incorrecta")
                return
            }

            val progressDialog = AlertDialog.Builder(this)
                .setTitle("Cargando...").setMessage("Verificando credenciales").setCancelable(false).create()
            progressDialog.show()

            val request = LoginRequest(usuario, password)
            ApiClient.getApiService().login(request).enqueue(object : Callback<LoginResponse> {
                override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val loginData = response.body()!!.data
                        val token = loginData.accessToken
                        val phone = loginData.user.phone
                        val userIdLocal = dbHelper.obtenerIdUsuarioPorEmail(usuario)

                        var identificador = ""
                        val cursor = dbHelper.obtenerUsuarioPorId(userIdLocal)
                        if (cursor.moveToFirst()) {
                            identificador = cursor.getString(cursor.getColumnIndexOrThrow("identificador"))
                            cursor.close()
                        }

                        // Identificación del punto de venta asignado al dispositivo
                        var cashPointId = 1
                        loginData.cashPoints?.let {
                            for (cp in it) {
                                if (cp.deviceIdentifier?.equals(identificador, ignoreCase = true) == true) {
                                    cashPointId = cp.id
                                    break
                                }
                            }
                        }

                        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                        sharedPreferences.edit().apply {
                            putString("userUsuario", usuario)
                            putString("passwordUsuario", password)
                            putString("accessToken", token)
                            putString("userPhone", phone)
                            putInt("userIdLocal", userIdLocal)
                            putInt("cashPointId", cashPointId)
                            apply()
                        }

                        progressDialog.setMessage("Descargando tarifas de la ruta...")

                        // Sincronización obligatoria de tarifas tras login exitoso
                        val getTarifas = TarifasRequest(identificador)
                        ApiClient.getApiService().obtenerTarifas("Bearer $token", getTarifas)
                            .enqueue(object : Callback<TarifasResponse> {
                                override fun onResponse(call: Call<TarifasResponse>, responseTarifas: Response<TarifasResponse>) {
                                    progressDialog.dismiss()
                                    if (responseTarifas.isSuccessful && responseTarifas.body() != null && responseTarifas.body()!!.status) {
                                        val tarifas = responseTarifas.body()!!.data
                                        val dbCortes = ControlCortes(this@MainActivity)
                                        dbCortes.limpiarTarifasCache()
                                        for (t in tarifas) {
                                            dbCortes.guardarTarifaCache(t.id, t.passenger_type ?: "", t.price ?: "0", t.fare ?: "")
                                        }
                                        Utils.mostrarToast(this@MainActivity, "Inicio de sesión exitoso")
                                    }

                                    LogUploader.sincronizarLogsPendientes(this@MainActivity)
                                    startActivity(Intent(this@MainActivity, BluetoothActivity::class.java))
                                }

                                override fun onFailure(call: Call<TarifasResponse>, t: Throwable) {
                                    progressDialog.dismiss()
                                    startActivity(Intent(this@MainActivity, BluetoothActivity::class.java))
                                }
                            })
                    } else {
                        progressDialog.dismiss()
                        Utils.mostrarToast(this@MainActivity, "Usuario o contraseña incorrectos")
                    }
                }

                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    progressDialog.dismiss()
                    Utils.mostrarToast(this@MainActivity, "Error de red: ${t.message}")
                }
            })

        } else {
            // Flujo Offline: Validación contra base de datos local para permitir operación en zonas sin cobertura
            if (dbHelper.verificarUsuario(usuario, password)) {
                val userIdLocal = dbHelper.obtenerIdUsuarioPorEmail(usuario)
                getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().apply {
                    putString("userUsuario", usuario)
                    putString("passwordUsuario", password)
                    putInt("userIdLocal", userIdLocal)
                    apply()
                }
                startActivity(Intent(this, BluetoothActivity::class.java))
            } else {
                Utils.mostrarToast(this, "Credenciales incorrectas (sin conexión)")
            }
        }
    }

    /**
     * Verifica si el dispositivo cuenta con una conexión activa a internet.
     */
    private fun hayConexionInternet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            Utils.activarPantallaCompleta(window)
        }
    }
}
