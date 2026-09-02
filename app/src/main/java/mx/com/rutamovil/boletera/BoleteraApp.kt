package mx.com.rutamovil.boletera

import android.app.Application
import mx.com.rutamovil.boletera.common.CrashHandler

class BoleteraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}