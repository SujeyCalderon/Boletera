package mx.com.rutamovil.boletera.domain.model

import java.util.*

class TarifaControl {
    data class Nivel(
        val id: Int,
        val precio: Double,
        val nombreBackend: String
    )

    private val niveles = mutableListOf<Nivel>()
    private var indiceActual = 0

    fun getListaNiveles(): List<Nivel> = niveles

    fun agregarNivel(id: Int, precio: Double, nombre: String) {
        niveles.add(Nivel(id, precio, nombre))
        ordenarNiveles()
    }

    private fun ordenarNiveles() {
        Collections.sort(niveles) { o1, o2 -> o2.precio.compareTo(o1.precio) }

        if (niveles.isNotEmpty()) {
            for (i in niveles.indices) {
                val nombre = niveles[i].nombreBackend.uppercase(Locale.getDefault())
                if (nombre == "REGULAR" || nombre == "NORMAL") {
                    indiceActual = i
                    break
                }
            }
        }
    }

    fun getPrecioActual(): Double = if (niveles.isEmpty()) 0.0 else niveles[indiceActual].precio

    fun getIdActual(): Int = if (niveles.isEmpty()) 0 else niveles[indiceActual].id

    fun esDinamica(): Boolean = niveles.size > 1

    fun subirNivel() {
        if (indiceActual < niveles.size - 1) {
            indiceActual++
        }
    }

    fun bajarNivel() {
        if (indiceActual > 0) {
            indiceActual--
        }
    }
}
