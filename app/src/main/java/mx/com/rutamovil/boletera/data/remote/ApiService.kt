package mx.com.rutamovil.boletera.data.remote

import mx.com.rutamovil.boletera.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    @POST("auth/login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    @POST("driver/report/create")
    fun enviarCorteParcial(
        @Header("Authorization") authHeader: String,
        @Body request: PartialCutRequest
    ): Call<Void>

    @POST("driver/report/create")
    fun enviarCorteTotal(
        @Header("Authorization") authHeader: String,
        @Body body: RequestBody
    ): Call<Void>

    @POST("driver/fares/get")
    fun obtenerTarifas(
        @Header("Authorization") authHeader: String,
        @Body body: TarifasRequest
    ): Call<TarifasResponse>

    @POST("driver/transactions/sync")
    fun sincronizarTransacciones(
        @Header("Authorization") authHeader: String,
        @Body body: TransactionSyncRequest
    ): Call<TransactionSyncResponse>

    @Multipart
    @POST("logs/upload")
    fun subirLogErrores(
        @Header("Authorization") authHeader: String,
        @Part file: MultipartBody.Part
    ): Call<Void>
}
