package mx.com.rutamovil.boletera.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    val status: Boolean,
    val data: Data
) {
    data class Data(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("token_type") val tokenType: String,
        @SerializedName("expires_in") val expiresIn: Int,
        val user: User,
        val role: String,
        @SerializedName("cash_points") val cashPoints: List<CashPoint>?
    )

    data class User(
        val id: Int,
        val name: String,
        val first_last_name: String?,
        val second_last_name: String?,
        val email: String,
        val company_name: String?,
        val status: String?,
        val created_at: String?,
        val updated_at: String?,
        val payment_code: String?,
        val phone: String?,
        val deleted_at: String?
    )

    data class CashPoint(
        val id: Int,
        @SerializedName("device_uuid") val deviceUuid: String?,
        @SerializedName("device_identifier") val deviceIdentifier: String?,
        val status: String,
        val unit: Unit?
    )

    data class Unit(
        val number: String?,
        val route: Route?
    )

    data class Route(
        val name: String?
    )
}
