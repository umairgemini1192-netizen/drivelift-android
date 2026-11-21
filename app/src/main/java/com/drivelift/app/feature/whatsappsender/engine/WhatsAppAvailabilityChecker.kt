package com.drivelift.app.feature.whatsappsender.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.drivelift.app.feature.whatsappsender.model.WhatsAppAvailability
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget

fun interface PackageInstalledLookup {
    fun isInstalled(packageName: String): Boolean
}

interface WhatsAppAvailabilityChecker {
    fun check(): WhatsAppAvailability
}

/** Detects WhatsApp Messenger and WhatsApp Business using manifest <queries> visibility. */
class AndroidWhatsAppAvailabilityChecker(
    private val lookup: PackageInstalledLookup,
) : WhatsAppAvailabilityChecker {

    constructor(context: Context) : this(
        PackageInstalledLookup { packageName ->
            isPackageInstalled(context.packageManager, packageName)
        },
    )

    override fun check(): WhatsAppAvailability {
        val personalInstalled = lookup.isInstalled(WhatsAppTarget.PERSONAL.packageName!!)
        val businessInstalled = lookup.isInstalled(WhatsAppTarget.BUSINESS.packageName!!)
        return when {
            personalInstalled && businessInstalled -> WhatsAppAvailability.BOTH
            personalInstalled -> WhatsAppAvailability.PERSONAL
            businessInstalled -> WhatsAppAvailability.BUSINESS
            else -> WhatsAppAvailability.NONE
        }
    }

    companion object {
        private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean =
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
                true
            }.getOrDefault(false)
    }
}
