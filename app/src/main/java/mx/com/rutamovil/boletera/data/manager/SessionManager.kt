package mx.com.rutamovil.boletera.data.manager

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import mx.com.rutamovil.boletera.common.Utils
import mx.com.rutamovil.boletera.data.remote.ApiClient
import mx.com.rutamovil.boletera.data.remote.dto.LoginRequest
import mx.com.rutamovil.boletera.data.remote.dto.LoginResponse
import mx.com.rutamovil.boletera.presentation.ui.auth.MainActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Administrador de sesiones para la aplicación.
 * Se encarga de monitorear la validez de los tokens de autenticación y proporcionar
 * mecanismos de renovación automática o cierre de sesión forzado.
 */
class SessionManager private constructor(private var activity: Activity?) {

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        /**
         * Obtiene la instancia única de [SessionManager], actualizando la referencia de la actividad actual.
         */
        fun getInstance(activity: Activity): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(activity).also { instance = it }
            }.apply { this.activity = activity }
        }
    }

    /**
     * Muestra un diálogo informativo al usuario indicando que su sesión ha caducado.
     * Ofrece la opción de renovarla (re-login en background) o salir al menú principal.
     */
    fun showSessionExpiredDialog() {
        activity?.let { act ->
            if (!act.isFinishing) {
                AlertDialog.Builder(act)
                    .setTitle("Sesión expirada")
                    .setMessage("¿Deseas renovar tu sesión?")
                    .setPositiveButton("Sí") { _, _ -> refreshSessionToken() }
                    .setNegativeButton("No") { _, _ ->
                        Utils.mostrarToast(act, "Sesión finalizada")
                        // Navegación limpia hacia la pantalla de login borrando el stack de actividades
                        val intent = Intent(act, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        act.startActivity(intent)
                        act.finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Intenta renovar el token de acceso utilizando las credenciales guardadas en las preferencias.
     * Si el proceso es exitoso, actualiza el token en memoria persistente.
     */
    private fun refreshSessionToken() {
        val act = activity ?: return
        val prefs = act.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val usuario = prefs.getString("userUsuario", null)
        val password = prefs.getString("passwordUsuario", null)

        if (usuario != null && password != null) {
            val request = LoginRequest(usuario, password)
            ApiClient.getApiService().login(request).enqueue(object : Callback<LoginResponse> {
                override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val newToken = response.body()!!.data.accessToken
                        prefs.edit().putString("accessToken", newToken).apply()

                        AlertDialog.Builder(act)
                            .setTitle("Token actualizado")
                            .setMessage("Se ha renovado tu token de sesión.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Utils.mostrarToast(act, "Error al refrescar token")
                    }
                }

                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    Utils.mostrarToast(act, "Fallo al refrescar token: ${t.message}")
                }
            })
        }
    }
}

