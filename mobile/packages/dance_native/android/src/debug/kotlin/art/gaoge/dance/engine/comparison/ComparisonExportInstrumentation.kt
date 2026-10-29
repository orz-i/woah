package art.gaoge.dance.engine.comparison

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** SDK-only runner for the installed Debug app; no second app or test-only dependencies. */
class ComparisonExportInstrumentation : Instrumentation() {
    private var managerMode=false
    private var interruptBeforeRetry=false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("comparisonExportFixtures")!="true") {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("error","Explicit comparisonExportFixtures=true is required") })
            return
        }
        interruptBeforeRetry=arguments.getString("mode")=="manager-interrupted"
        managerMode=arguments.getString("mode")=="manager" || interruptBeforeRetry
        start()
    }
    override fun onStart() {
        try {
            if (managerMode) ComparisonExportManagerProbe.run(targetContext,interruptBeforeRetry)
            else ComparisonExportDeviceProbe.run(targetContext,File(targetContext.filesDir,"comparison-export-20261026"),
                File(targetContext.cacheDir,"comparison-export-probe"))
            finish(Activity.RESULT_OK,Bundle().apply { putString("comparisonExport",if (managerMode) "foreground service, cancel, retry and album passed" else "device outputs and cancellation passed") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("comparisonExportError",e.stackTraceToString()) })
        }
    }
}
