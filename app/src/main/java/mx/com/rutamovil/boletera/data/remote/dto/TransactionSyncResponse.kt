package mx.com.rutamovil.boletera.data.remote.dto

data class TransactionSyncResponse(
    val status: Boolean,
    val message: String?,
    val data: Data?
) {
    data class Data(
        val success: Int,
        val failed: Int
    )
}
