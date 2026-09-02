package mx.com.rutamovil.boletera.data.remote.dto

data class TarifasResponse(
    val status: Boolean,
    val data: List<Fare>
) {
    data class Fare(
        var id: Int = 0,
        var fare: String? = null,
        var passenger_type: String? = null,
        var price: String? = null,
        var payment_type: String? = null
    )
}
