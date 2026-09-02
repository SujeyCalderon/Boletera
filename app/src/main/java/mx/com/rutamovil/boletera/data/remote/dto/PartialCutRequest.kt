package mx.com.rutamovil.boletera.data.remote.dto

import mx.com.rutamovil.boletera.domain.model.SaleItem

data class PartialCutRequest(
    val device_identifier: String,
    val timestamp: String,
    val type: String,
    val user: String,
    val sales: List<SaleItem>
)
