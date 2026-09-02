package mx.com.rutamovil.boletera.data.remote.dto

data class TransactionSyncRequest(
    val phone: String,
    val transactions: List<Transaction>
) {
    data class Transaction(
        val route_fare_id: Int,
        val cash_point_id: Int,
        val route_name: String,
        val unit_number: String,
        val fare_name: String,
        val passenger_type: String,
        val price: Double,
        val quantity: Int,
        val total_price: Double,
        val latitude: String?,
        val longitude: String?,
        val status: String,
        val event_date: String
    )
}
