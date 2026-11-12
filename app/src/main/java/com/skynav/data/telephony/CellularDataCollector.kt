package com.skynav.data.telephony

import android.annotation.SuppressLint
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.RadioTechnology
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Reads LTE and NR cells. Requires ACCESS_FINE_LOCATION and READ_PHONE_STATE.
 *
 * [snapshot] returns whatever the modem last cached. [requestFresh] asks for a new measurement;
 * if the modem refuses or times out, it falls back to the cache. Callers must still ignore a
 * result whose timestamp did not change.
 *
 * Timing Advance is only read for LTE. A reported value of 0 is kept as 0 so the diagnostic
 * screen can show it; the position estimator treats 0 as "not measured".
 */
class CellularDataCollector(private val telephony: TelephonyManager) {

    @SuppressLint("MissingPermission")
    fun snapshot(): List<CellMeasurement> = map(telephony.allCellInfo.orEmpty())

    /** Asks the modem for a new measurement. Resumes with the cache when the request fails. */
    @SuppressLint("MissingPermission")
    suspend fun requestFresh(): List<CellMeasurement> = suspendCancellableCoroutine { cont ->
        val executor = Executor { command -> command.run() }
        val callback = object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                if (cont.isActive) cont.resume(map(cellInfo))
            }

            override fun onError(errorCode: Int, detail: Throwable?) {
                if (cont.isActive) cont.resume(runCatching { snapshot() }.getOrDefault(emptyList()))
            }
        }
        try {
            telephony.requestCellInfoUpdate(executor, callback)
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    private fun map(cells: List<CellInfo>): List<CellMeasurement> =
        cells.mapNotNull { cell ->
            when (cell) {
                is CellInfoLte -> lte(cell)
                is CellInfoNr -> nr(cell)
                else -> null
            }
        }


    private fun lte(cell: CellInfoLte): CellMeasurement {
        val id = cell.cellIdentity
        val ss = cell.cellSignalStrength
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val ci = id.ci.valid()
        return CellMeasurement(
            timestampMs = cell.timestampMillis,
            key = if (mcc != null && mnc != null && ci != null) {
                CellKey(mcc, mnc, RadioTechnology.LTE, ci.toLong())
            } else {
                null
            },
            tac = id.tac.valid(),
            pci = id.pci.valid(),
            rsrpDbm = ss.rsrp.valid(),
            rsrqDb = ss.rsrq.valid(),
            sinrDb = ss.rssnr.valid(),
            timingAdvance = ss.timingAdvance.valid(),
            registered = cell.isRegistered,
        )
    }

    private fun nr(cell: CellInfoNr): CellMeasurement? {
        // CellInfoNr declares the base types; the NR-specific classes are the runtime types.
        val id = cell.cellIdentity as? CellIdentityNr ?: return null
        val ss = cell.cellSignalStrength as? CellSignalStrengthNr
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val nci = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG }
        return CellMeasurement(
            timestampMs = cell.timestampMillis,
            key = if (mcc != null && mnc != null && nci != null) {
                CellKey(mcc, mnc, RadioTechnology.NR, nci)
            } else {
                null
            },
            tac = id.tac.valid(),
            pci = id.pci.valid(),
            rsrpDbm = ss?.ssRsrp?.valid(),
            rsrqDb = ss?.ssRsrq?.valid(),
            sinrDb = ss?.ssSinr?.valid(),
            timingAdvance = null,
            registered = cell.isRegistered,
        )
    }

    private fun Int.valid(): Int? = takeIf { it != CellInfo.UNAVAILABLE }
}
