package com.qwert2603.spenddemo.dialogs

import android.app.Activity
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.hannesdorfmann.fragmentargs.annotation.Arg
import com.hannesdorfmann.fragmentargs.annotation.FragmentWithArgs
import com.qwert2603.spenddemo.BuildConfig
import com.qwert2603.spenddemo.utils.hour
import com.qwert2603.spenddemo.utils.minute
import java.util.*

@FragmentWithArgs
class TimePickerDialogFragment : DialogFragment() {

    companion object {
        const val MILLIS_KEY = "${BuildConfig.APPLICATION_ID}.MILLIS_KEY"
    }

    @Arg
    var millis: Long = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val calendar = Calendar.getInstance().also { it.timeInMillis = millis }
        return TimePickerDialog(
                requireContext(),
                { _, h, m ->
                    calendar.hour = h
                    calendar.minute = m
                    targetFragment!!.onActivityResult(
                            targetRequestCode,
                            Activity.RESULT_OK,
                            Intent().putExtra(MILLIS_KEY, calendar.timeInMillis)
                    )
                },
                calendar.hour,
                calendar.minute,
                true
        )
    }
}